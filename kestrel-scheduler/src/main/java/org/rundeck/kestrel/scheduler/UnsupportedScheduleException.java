package org.rundeck.kestrel.scheduler;

/**
 * A Quartz cron expression that has no exact Kubernetes CronJob equivalent.
 */
public class UnsupportedScheduleException extends Exception {
    private final String expression;
    private final String reason;

    /**
     * @param expression the rejected Quartz expression
     * @param reason     why it cannot be translated, phrased for the job author
     */
    public UnsupportedScheduleException(String expression, String reason) {
        super("Schedule '" + expression + "' cannot run on Kubernetes: " + reason);
        this.expression = expression;
        this.reason = reason;
    }

    /** @return the rejected Quartz expression */
    public String getExpression() {
        return expression;
    }

    /** @return the reason, phrased for the job author */
    public String getReason() {
        return reason;
    }
}
