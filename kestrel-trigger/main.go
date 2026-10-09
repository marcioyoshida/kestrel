// Command kestrel-trigger runs as the container of every Kestrel CronJob (ADR 0001 §2). It sends
// one message per firing to the SQS FIFO fire queue and exits; runner pods do the rest.
//
// The scheduled minute comes from the Job name, which the CronJob controller sets to
// "<cronjob>-<minutes since epoch>", so a late start or a retried pod names the same firing and
// SQS deduplicates it. Credentials come from EKS Pod Identity.
package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/sqs"
)

// fire is the message body; kestrel-scheduler's FireMessage parses it.
type fire struct {
	V           int    `json:"v"`
	Bucket      string `json:"bucket"`
	Schedule    string `json:"schedule"`
	TimeZone    string `json:"timeZone"`
	ScheduledAt string `json:"scheduledAt"`
}

// scheduledMinute extracts the minutes-since-epoch suffix the CronJob controller appends to the
// Job name. Jobs created by hand (kubectl create job --from=cronjob/...) have no such suffix and
// are refused: a firing must name a real scheduled minute.
func scheduledMinute(bucket, jobName string) (int64, error) {
	prefix := bucket + "-"
	if !strings.HasPrefix(jobName, prefix) {
		return 0, fmt.Errorf("job %q was not created by CronJob %q", jobName, bucket)
	}
	m, err := strconv.ParseInt(strings.TrimPrefix(jobName, prefix), 10, 64)
	if err != nil || m <= 0 {
		return 0, fmt.Errorf("job %q has no scheduled-minute suffix", jobName)
	}
	return m, nil
}

func message(bucket, schedule, zone string, minute int64) (string, error) {
	b, err := json.Marshal(fire{
		V: 1, Bucket: bucket, Schedule: schedule, TimeZone: zone,
		ScheduledAt: time.Unix(minute*60, 0).UTC().Format(time.RFC3339),
	})
	return string(b), err
}

func env(name string) string {
	v := os.Getenv(name)
	if v == "" {
		log.Fatalf("%s is not set", name)
	}
	return v
}

func main() {
	log.SetFlags(0)
	queue, bucket, jobName := env("KESTREL_QUEUE_URL"), env("KESTREL_BUCKET"), env("KESTREL_JOB_NAME")
	minute, err := scheduledMinute(bucket, jobName)
	if err != nil {
		log.Fatal(err)
	}
	body, err := message(bucket, os.Getenv("KESTREL_SCHEDULE"), os.Getenv("KESTREL_TIME_ZONE"), minute)
	if err != nil {
		log.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	cfg, err := config.LoadDefaultConfig(ctx)
	if err != nil {
		log.Fatalf("aws config: %v", err)
	}
	out, err := sqs.NewFromConfig(cfg).SendMessage(ctx, &sqs.SendMessageInput{
		QueueUrl:               aws.String(queue),
		MessageBody:            aws.String(body),
		MessageGroupId:         aws.String(bucket),
		MessageDeduplicationId: aws.String(fmt.Sprintf("%s-%d", bucket, minute)),
	})
	if err != nil {
		log.Fatalf("send: %v", err) // non-zero exit: the Job retries; SQS dedupes the retry
	}
	log.Printf("fired %s at %s (message %s)", bucket, time.Unix(minute*60, 0).UTC().Format(time.RFC3339), aws.ToString(out.MessageId))
}
