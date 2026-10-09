package org.rundeck.kestrel.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates a Rundeck (Quartz) cron expression into the 5-field dialect of Kubernetes
 * CronJobs. Only exact translations are produced: anything Kubernetes cannot express with
 * identical firing times (non-zero seconds, years, {@code L}, {@code W}, {@code #}, wrapping
 * ranges) is rejected with {@link UnsupportedScheduleException}. A schedule is never
 * approximated.
 *
 * <p>Quartz fields: second minute hour day-of-month month day-of-week [year]. Quartz numbers
 * days of the week 1-7 from Sunday; cron numbers them 0-6, so numeric days are shifted.
 */
public final class CronTranslator {

    private static final Pattern ITEM = Pattern.compile(
        "^(\\*|\\?|[0-9]{1,2}|[A-Z]{3})(?:-([0-9]{1,2}|[A-Z]{3}))?(?:/([0-9]{1,2}))?$");
    private static final List<String> MONTHS = List.of(
        "JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    /** Field rules: name, inclusive range in Quartz numbering, optional names. */
    private enum Field {
        MINUTE("minute", 0, 59, null),
        HOUR("hour", 0, 23, null),
        DAY_OF_MONTH("day-of-month", 1, 31, null),
        MONTH("month", 1, 12, MONTHS),
        DAY_OF_WEEK("day-of-week", 1, 7, DAYS);

        final String label;
        final int min;
        final int max;
        final List<String> names;

        Field(String label, int min, int max, List<String> names) {
            this.label = label;
            this.min = min;
            this.max = max;
            this.names = names;
        }
    }

    private CronTranslator() {
    }

    /**
     * Translates a Quartz cron expression.
     *
     * @param quartz expression with 6 or 7 whitespace-separated fields
     * @return the equivalent 5-field Kubernetes schedule
     * @throws UnsupportedScheduleException if the expression has no exact Kubernetes equivalent
     */
    public static String toKubernetes(String quartz) throws UnsupportedScheduleException {
        if (quartz == null || quartz.isBlank()) {
            throw new UnsupportedScheduleException(quartz, "the expression is empty");
        }
        String[] f = quartz.trim().toUpperCase(Locale.ROOT).split("\\s+");
        if (f.length != 6 && f.length != 7) {
            throw new UnsupportedScheduleException(quartz, "expected 6 or 7 fields, found " + f.length);
        }
        if (!f[0].matches("0{1,2}")) {
            throw new UnsupportedScheduleException(quartz, "seconds must be 0 (Kubernetes fires on whole minutes)");
        }
        if (f.length == 7 && !"*".equals(f[6])) {
            throw new UnsupportedScheduleException(quartz, "a year field is not supported");
        }
        boolean domRestricted = !isWildcard(f[3]);
        boolean dowRestricted = !isWildcard(f[5]);
        if (domRestricted && dowRestricted) {
            throw new UnsupportedScheduleException(quartz,
                "day-of-month and day-of-week cannot both be set (use ? in one of them)");
        }
        return String.join(" ",
            field(quartz, f[1], Field.MINUTE),
            field(quartz, f[2], Field.HOUR),
            field(quartz, f[3], Field.DAY_OF_MONTH),
            field(quartz, f[4], Field.MONTH),
            field(quartz, f[5], Field.DAY_OF_WEEK));
    }

    /**
     * Returns whether the expression can be scheduled on Kubernetes.
     *
     * @param quartz Quartz cron expression
     * @return true if {@link #toKubernetes(String)} would succeed
     */
    public static boolean isSupported(String quartz) {
        try {
            toKubernetes(quartz);
            return true;
        } catch (UnsupportedScheduleException e) {
            return false;
        }
    }

    private static boolean isWildcard(String field) {
        return "*".equals(field) || "?".equals(field);
    }

    private static String field(String expr, String value, Field field) throws UnsupportedScheduleException {
        List<String> out = new ArrayList<>();
        for (String item : value.split(",", -1)) {
            out.add(item(expr, item, field));
        }
        return String.join(",", out);
    }

    private static String item(String expr, String item, Field field) throws UnsupportedScheduleException {
        Matcher m = ITEM.matcher(item);
        if (!m.matches()) {
            throw new UnsupportedScheduleException(expr,
                "unsupported " + field.label + " value '" + item + "' (L, W, # and other Quartz-only forms have no Kubernetes equivalent)");
        }
        String start = m.group(1);
        String end = m.group(2);
        String step = m.group(3);
        if ("?".equals(start)) {
            if (field != Field.DAY_OF_MONTH && field != Field.DAY_OF_WEEK) {
                throw new UnsupportedScheduleException(expr, "? is only valid for day-of-month or day-of-week");
            }
            start = "*";
        }
        if (step != null) {
            int s = Integer.parseInt(step);
            if (s < 1 || s > field.max) {
                throw new UnsupportedScheduleException(expr, "invalid " + field.label + " step '" + step + "'");
            }
        }
        if ("*".equals(start)) {
            if (end != null) {
                throw new UnsupportedScheduleException(expr, "invalid " + field.label + " range '" + item + "'");
            }
            return step == null ? "*" : "*/" + step;
        }
        int from = value(expr, start, field);
        int to;
        if (end != null) {
            to = value(expr, end, field);
        } else if (step != null) {
            to = field.max;  // Quartz "a/n" = from a to the end of the range, every n
        } else {
            to = from;
        }
        if (to < from) {
            throw new UnsupportedScheduleException(expr,
                "wrapping " + field.label + " range '" + item + "' is not supported; split it into two ranges");
        }
        int shift = field == Field.DAY_OF_WEEK ? 1 : 0;
        String range = (from - shift) + (to == from && end == null && step == null ? "" : "-" + (to - shift));
        return step == null ? range : range + "/" + step;
    }

    private static int value(String expr, String token, Field field) throws UnsupportedScheduleException {
        int v;
        if (Character.isDigit(token.charAt(0))) {
            v = Integer.parseInt(token);
        } else {
            int idx = field.names == null ? -1 : field.names.indexOf(token);
            if (idx < 0) {
                throw new UnsupportedScheduleException(expr, "unknown " + field.label + " name '" + token + "'");
            }
            v = idx + field.min;
        }
        if (v < field.min || v > field.max) {
            throw new UnsupportedScheduleException(expr,
                field.label + " value " + v + " is outside " + field.min + "-" + field.max);
        }
        return v;
    }
}
