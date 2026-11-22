package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.SQLException;
import android.media.tv.TvContract.PreviewPrograms;
import android.media.tv.TvContract.WatchNextPrograms;
import android.net.Uri;
import android.os.CancellationSignal;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reads the entries other apps publish to Android TV's local provider. */
final class Suggestions {
    static final String KEY = "show_suggestions";
    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";
    private static final int MAX_CARDS_PER_APP = 5;

    private static final String[] COLUMNS = {
            PreviewPrograms.COLUMN_PACKAGE_NAME, PreviewPrograms.COLUMN_TITLE,
            PreviewPrograms.COLUMN_INTENT_URI, PreviewPrograms.COLUMN_THUMBNAIL_URI,
            PreviewPrograms.COLUMN_POSTER_ART_URI, PreviewPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
            PreviewPrograms.COLUMN_DURATION_MILLIS, PreviewPrograms.COLUMN_TYPE
    };
    private static final String[] WATCH_NEXT_COLUMNS = watchNextColumns();
    static final int PROGRESS_MAX = 1000;

    static boolean enabled(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, false)
                && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static List<String> mediaApps(Context context) {
        return Arrays.asList(context.getResources().getStringArray(R.array.suggestion_media_apps));
    }

    static boolean tracksApp(Context context, String pkg) {
        return enabled(context) && mediaApps(context).contains(pkg);
    }

    static void recordOpen(Context context, String pkg) {
        if (tracksApp(context, pkg)) LastOpened.record(context, pkg, true);
    }

    static final class Program {
        final String pkg;
        final String title;
        final String link;
        final Intent intent;
        final String thumbnailUri, posterUri;
        final String jellyfinSeries;
        final int progress;

        Program(String pkg, String title, String link, Intent intent,
                String thumbnailUri, String posterUri, int progress, String jellyfinSeries) {
            this.pkg = pkg;
            this.title = title;
            this.link = link;
            this.intent = intent;
            this.thumbnailUri = thumbnailUri;
            this.posterUri = posterUri;
            this.progress = progress;
            this.jellyfinSeries = jellyfinSeries;
        }

        boolean sameTitle(Program other) {
            return other != null && pkg.equals(other.pkg) && link.equals(other.link);
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof Program)) return false;
            Program program = (Program) other;
            return sameTitle(program) && title.equals(program.title) && progress == program.progress
                    && Objects.equals(thumbnailUri, program.thumbnailUri) && Objects.equals(posterUri, program.posterUri)
                    && Objects.equals(jellyfinSeries, program.jellyfinSeries);
        }

        @Override public int hashCode() {
            return Objects.hash(pkg, title, link, thumbnailUri, posterUri, progress, jellyfinSeries);
        }
    }

    static List<Program> read(Context context, Set<String> packages, CancellationSignal cancel) {
        if (packages.isEmpty()) return Collections.emptyList();
        Map<String, Map<String, Program>> byApp = new LinkedHashMap<>();
        Map<String, Long> lastUsed = new HashMap<>();
        long now = System.currentTimeMillis();
        for (String pkg : packages) lastUsed.put(pkg, activityTime(LastOpened.get(context, pkg), now));
        readTable(context, WatchNextPrograms.CONTENT_URI, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
                + " DESC, _id DESC", packages, byApp, lastUsed, now, cancel);
        readTable(context, PreviewPrograms.CONTENT_URI, PreviewPrograms.COLUMN_WEIGHT + " DESC, _id DESC",
                packages, byApp, lastUsed, now, cancel);
        Map<String, List<Program>> feeds = new LinkedHashMap<>();
        Map<String, Long> opens = new HashMap<>();
        for (Map.Entry<String, Map<String, Program>> feed : byApp.entrySet()) {
            String pkg = feed.getKey();
            feeds.put(pkg, new ArrayList<>(feed.getValue().values()));
            opens.put(pkg, LastOpened.recentOpens(context, pkg, now));
        }
        return rankCards(feeds, opens, lastUsed, now);
    }

    private static void readTable(Context context, Uri uri, String sort, Set<String> packages,
                                  Map<String, Map<String, Program>> byApp, Map<String, Long> lastUsed,
                                  long now, CancellationSignal cancel) {
        try (Cursor rows = query(context, uri, sort, cancel)) {
            if (rows == null) return;
            int watchType = rows.getColumnIndex(WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE);
            int engagementTime = rows.getColumnIndex(WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS);
            while (!cancel.isCanceled() && rows.moveToNext()) {
                String pkg = rows.getString(0);
                if (!packages.contains(pkg)) continue;
                if (watchType >= 0 && engagementTime >= 0 && !rows.isNull(watchType)) {
                    long playedAt = playbackTime(rows.getInt(watchType), rows.getLong(engagementTime), now);
                    if (playedAt > 0) lastUsed.merge(pkg, playedAt, Math::max);
                }
                Map<String, Program> feed = byApp.computeIfAbsent(pkg, ignored -> new LinkedHashMap<>());
                if (feed.size() >= MAX_CARDS_PER_APP) continue;
                String link = rows.getString(2);
                if (TextUtils.isEmpty(link) || feed.containsKey(link)) continue;
                String originalTitle = rows.getString(1);
                String title = Cards.cleanLabel(originalTitle, "");
                if (title.isEmpty()) continue;
                Intent intent = publisherIntent(pkg, link);
                if (intent != null) {
                    boolean jellyfin = JellyfinArtwork.isJellyfin(pkg);
                    if (jellyfin && intent.getBooleanExtra("ItemIsUserView", false)) continue;
                    String series = jellyfin && rows.getInt(7) == PreviewPrograms.TYPE_TV_EPISODE ? originalTitle : null;
                    feed.put(link, new Program(pkg, title, link, intent, rows.getString(3), rows.getString(4),
                            playbackProgress(rows.getLong(5), rows.getLong(6)), series));
                }
            }
        } catch (SecurityException | IllegalArgumentException | SQLException | UnsupportedOperationException ignored) {
            // Some TV providers block access or omit one of these tables. Try the other table independently.
        }
    }

    private static Cursor query(Context context, Uri uri, String sort, CancellationSignal cancel) {
        if (WatchNextPrograms.CONTENT_URI.equals(uri)) {
            try {
                Cursor rows = context.getContentResolver().query(uri, WATCH_NEXT_COLUMNS, null, null, sort, cancel);
                if (rows != null) return rows;
            } catch (IllegalArgumentException | SQLException | UnsupportedOperationException ignored) {
                // Some TV providers still accept the original projection without the activity columns.
            }
        }
        return context.getContentResolver().query(uri, COLUMNS, null, null, sort, cancel);
    }

    private static String[] watchNextColumns() {
        String[] columns = Arrays.copyOf(COLUMNS, COLUMNS.length + 2);
        columns[COLUMNS.length] = WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE;
        columns[COLUMNS.length + 1] = WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS;
        return columns;
    }

    static long playbackTime(int watchType, long timestamp, long now) {
        if (watchType != WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
                && watchType != WatchNextPrograms.WATCH_NEXT_TYPE_NEXT) return 0L;
        return activityTime(timestamp, now);
    }

    private static long activityTime(long timestamp, long now) {
        return timestamp > 0 && timestamp <= now ? timestamp : 0L;
    }

    static int playbackProgress(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        if (position >= duration) return PROGRESS_MAX;
        return Math.max(1, (int) (PROGRESS_MAX * ((double) position / duration)));
    }

    static <T> List<T> rankCards(Map<String, List<T>> feeds, Map<String, Long> opens,
                                 Map<String, Long> lastUsed, long now) {
        List<RankedCard<T>> ranked = new ArrayList<>(feeds.size() * MAX_CARDS_PER_APP);
        for (Map.Entry<String, List<T>> entry : feeds.entrySet()) {
            String pkg = entry.getKey();
            long usedAt = lastUsed.getOrDefault(pkg, 0L);
            long weight = opens.getOrDefault(pkg, 0L);
            // A recent launch or playback date is one signal when the launcher has no counts yet.
            if (weight == 0 && LastOpened.recentlyUsed(usedAt, now)) weight = 1;
            List<T> feed = entry.getValue();
            int count = Math.min(feed.size(), MAX_CARDS_PER_APP);
            for (int rank = 0; rank < count; rank++) {
                // An app with weight three gets a card every third of a round. Inactive apps follow all active cards.
                double position = weight == 0 ? MAX_CARDS_PER_APP + rank + 1.0 : (rank + 1.0) / weight;
                ranked.add(new RankedCard<>(feed.get(rank), position, weight, usedAt));
            }
        }
        ranked.sort(Comparator.<RankedCard<T>>comparingDouble(card -> card.position)
                .thenComparing(Comparator.comparingLong((RankedCard<T> card) -> card.weight).reversed())
                .thenComparing(Comparator.comparingLong((RankedCard<T> card) -> card.usedAt).reversed()));
        List<T> result = new ArrayList<>(ranked.size());
        for (RankedCard<T> card : ranked) result.add(card.program);
        return result;
    }

    private static final class RankedCard<T> {
        final T program;
        final double position;
        final long weight;
        final long usedAt;

        RankedCard(T program, double position, long weight, long usedAt) {
            this.program = program;
            this.position = position;
            this.weight = weight;
            this.usedAt = usedAt;
        }
    }

    private static Intent publisherIntent(String pkg, String link) {
        Intent intent;
        try {
            intent = Intent.parseUri(link, Intent.URI_INTENT_SCHEME);
        } catch (Exception malformed) {
            return null;
        }
        if (intent.getSelector() != null
                || (intent.getPackage() != null && !pkg.equals(intent.getPackage()))
                || (intent.getComponent() != null && !pkg.equals(intent.getComponent().getPackageName()))) {
            return null;
        }
        return intent.setPackage(pkg).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
}
