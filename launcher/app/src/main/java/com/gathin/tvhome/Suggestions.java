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
import java.util.Collections;
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
    static final int PROGRESS_MAX = 1000;

    static boolean enabled(Context context) {
        return context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE).getBoolean(KEY, false)
                && context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
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
        readTable(context, WatchNextPrograms.CONTENT_URI, WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS
                + " DESC, _id DESC", packages, byApp, cancel);
        readTable(context, PreviewPrograms.CONTENT_URI, PreviewPrograms.COLUMN_WEIGHT + " DESC, _id DESC",
                packages, byApp, cancel);
        List<List<Program>> feeds = new ArrayList<>();
        for (Map<String, Program> feed : byApp.values()) feeds.add(new ArrayList<>(feed.values()));
        return interleave(feeds);
    }

    private static void readTable(Context context, Uri uri, String sort, Set<String> packages,
                                  Map<String, Map<String, Program>> byApp, CancellationSignal cancel) {
        // An explicit projection avoids vendor-specific missing columns. The provider applies visibility.
        try (Cursor rows = context.getContentResolver().query(uri, COLUMNS, null, null, sort, cancel)) {
            if (rows == null) return;
            while (!cancel.isCanceled() && rows.moveToNext()) {
                String pkg = rows.getString(0);
                if (!packages.contains(pkg)) continue;
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

    static int playbackProgress(long position, long duration) {
        if (position <= 0 || duration <= 0) return 0;
        if (position >= duration) return PROGRESS_MAX;
        return Math.max(1, (int) (PROGRESS_MAX * ((double) position / duration)));
    }

    static <T> List<T> interleave(List<List<T>> feeds) {
        List<T> result = new ArrayList<>(feeds.size() * MAX_CARDS_PER_APP);
        for (int rank = 0; rank < MAX_CARDS_PER_APP; rank++) {
            for (List<T> feed : feeds) {
                if (rank < feed.size()) result.add(feed.get(rank));
            }
        }
        return result;
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
