package org.rundeck.kestrel.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.util.List;

/**
 * Long-polls the fire queue and hands each message to a {@link Handler}. A message is deleted
 * only when the handler reports it fully handled; otherwise it becomes visible again after the
 * queue's visibility timeout and is retried (and dead-lettered by the queue's redrive policy).
 */
public class SqsFireConsumer implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(SqsFireConsumer.class);

    /** Processes one firing. */
    public interface Handler {
        /**
         * @param message the firing
         * @return true if every job in the bucket was handed off or deliberately skipped
         */
        boolean handle(FireMessage message) throws Exception;
    }

    private final SqsClient sqs;
    private final String queueUrl;
    private final Handler handler;
    private volatile boolean running = true;

    /**
     * @param sqs      client
     * @param queueUrl fire queue
     * @param handler  firing handler
     */
    public SqsFireConsumer(SqsClient sqs, String queueUrl, Handler handler) {
        this.sqs = sqs;
        this.queueUrl = queueUrl;
        this.handler = handler;
    }

    /** Stops after the current poll. */
    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        LOG.info("Fire consumer polling {}", queueUrl);
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                pollOnce();
            } catch (RuntimeException e) {
                LOG.warn("Fire queue poll failed: {}", e.getMessage());
                sleep(5000);
            }
        }
        LOG.info("Fire consumer stopped");
    }

    /** Receives and processes one batch. */
    public void pollOnce() {
        List<Message> batch = sqs.receiveMessage(b -> b.queueUrl(queueUrl).maxNumberOfMessages(10).waitTimeSeconds(20))
            .messages();
        for (Message m : batch) {
            boolean done = false;
            try {
                FireMessage fire = FireMessage.parse(m.body());
                done = handler.handle(fire);
                if (!done) {
                    LOG.warn("Firing {} not fully handled; it will be redelivered", fire);
                }
            } catch (Exception e) {
                LOG.error("Firing message {} failed: {}", m.messageId(), e.toString(), e);
            }
            if (done) {
                sqs.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(m.receiptHandle()));
            }
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
