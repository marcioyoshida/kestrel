package main

import (
	"encoding/json"
	"testing"
)

func TestScheduledMinute(t *testing.T) {
	m, err := scheduledMinute("kestrel-0123456789ab", "kestrel-0123456789ab-29870640")
	if err != nil || m != 29870640 {
		t.Fatalf("got %d, %v", m, err)
	}
	for _, bad := range []string{"kestrel-0123456789ab-manual-x7k2p", "other-29870640", "kestrel-0123456789ab-"} {
		if _, err := scheduledMinute("kestrel-0123456789ab", bad); err == nil {
			t.Errorf("%q: expected an error", bad)
		}
	}
}

func TestMessage(t *testing.T) {
	body, err := message("kestrel-0123456789ab", "*/5 * * * *", "UTC", 29870640)
	if err != nil {
		t.Fatal(err)
	}
	var f fire
	if err := json.Unmarshal([]byte(body), &f); err != nil {
		t.Fatal(err)
	}
	if f.V != 1 || f.ScheduledAt != "2026-10-17T12:00:00Z" || f.Bucket != "kestrel-0123456789ab" {
		t.Fatalf("unexpected %s", body)
	}
}
