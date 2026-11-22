package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Local launch history for the cinematic shelf and Suggestions. Suggestions keeps one count per day.
 */
final class LastOpened {
    private static final String PREFS = "last_opened";
    private static final String COUNTS_PREFIX = "suggestion_opens:";
    private static final int WINDOW_DAYS = 30;
    private static final long DAY_MILLIS = TimeUnit.DAYS.toMillis(1);

    private LastOpened() {
    }

    /** Notes that Home just opened this app. */
    static void record(Context ctx, String pkg, boolean countForSuggestions) {
        SharedPreferences prefs = prefs(ctx);
        long now = System.currentTimeMillis();
        SharedPreferences.Editor edit = prefs.edit().putLong(pkg, now);
        if (countForSuggestions) {
            String key = COUNTS_PREFIX + pkg;
            edit.putString(key, countOpen(prefs.getString(key, ""), now));
        }
        edit.apply();
    }

    /** Milliseconds since the epoch, or 0 when Home never opened the app. */
    static long get(Context ctx, String pkg) {
        return pkg == null ? 0L : prefs(ctx).getLong(pkg, 0L);
    }

    static long recentOpens(Context ctx, String pkg, long now) {
        return recentOpens(prefs(ctx).getString(COUNTS_PREFIX + pkg, ""), now);
    }

    static boolean recentlyUsed(long timestamp, long now) {
        return timestamp > 0 && timestamp <= now && now / DAY_MILLIS - timestamp / DAY_MILLIS < WINDOW_DAYS;
    }

    static String countOpen(String history, long now) {
        long today = now / DAY_MILLIS;
        int[] counts = dailyCounts(history, today);
        if (counts[0] < Integer.MAX_VALUE) counts[0]++;
        StringBuilder saved = new StringBuilder().append(today);
        for (int count : counts) saved.append(',').append(count);
        return saved.toString();
    }

    static long recentOpens(String history, long now) {
        long total = 0;
        for (int count : dailyCounts(history, now / DAY_MILLIS)) total += count;
        return total;
    }

    private static int[] dailyCounts(String history, long today) {
        int[] counts = new int[WINDOW_DAYS];
        if (history.isEmpty()) return counts;
        String[] saved = history.split(",");
        try {
            // The first field is the newest day, followed by counts going backwards from that day.
            long age = today - Long.parseLong(saved[0]);
            if (age < 0 || age >= WINDOW_DAYS) return counts;
            for (int i = 1; i < saved.length && age + i <= WINDOW_DAYS; i++) {
                counts[(int) age + i - 1] = Math.max(0, Integer.parseInt(saved[i]));
            }
        } catch (NumberFormatException ignored) {
        }
        return counts;
    }

    /**
     * "5 minutes ago" within the hour, then the day and the time: "today, 11:40 am", "yesterday, 9:40 pm",
     * "3 days ago, 9:40 pm", "Oct 12, 9:40 pm". The parts are Android's own wording in the TV's language.
     */
    static String describe(Context ctx, long when) {
        long now = System.currentTimeMillis();
        // Under a minute Android says "0 minutes ago"; one minute reads better.
        when = Math.min(when, now - DateUtils.MINUTE_IN_MILLIS);
        if (now - when < DateUtils.HOUR_IN_MILLIS) {
            return DateUtils.getRelativeTimeSpanString(when, now, DateUtils.MINUTE_IN_MILLIS).toString();
        }
        String day = DateUtils.getRelativeTimeSpanString(when, now, DateUtils.DAY_IN_MILLIS).toString();
        // It follows "Last opened" mid-sentence: "yesterday", not "Yesterday". Month names keep their capital.
        if (Locale.getDefault().getLanguage().equals("en") && (day.startsWith("Yesterday") || day.startsWith("Today"))) {
            day = Character.toLowerCase(day.charAt(0)) + day.substring(1);
        }
        return day + ", " + DateUtils.formatDateTime(ctx, when, DateUtils.FORMAT_SHOW_TIME);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
