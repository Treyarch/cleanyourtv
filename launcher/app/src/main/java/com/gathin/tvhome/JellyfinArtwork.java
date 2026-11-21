package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.CancellationSignal;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Requests Jellyfin's Landscape (Thumb) via the same image proxy that published its poster. */
final class JellyfinArtwork {
    private static final Pattern IMAGE_PATH = Pattern.compile(
            "(?i)(.*/Items/[0-9a-f-]{32,36}/Images/)(?:Primary|Backdrop|Thumb)(?:/[0-9]+)?");
    private static final int SAMPLE = 16;
    private final Map<String, int[]> placeholders = new HashMap<>();

    static boolean isJellyfin(String pkg) {
        return pkg.equals("org.jellyfin.androidtv") || pkg.equals("org.jellyfin.androidtv.debug");
    }

    static boolean isProxy(String pkg, Uri uri) {
        return isJellyfin(pkg) && "content".equals(uri.getScheme())
                && (pkg + ".integration.provider.ImageProvider").equals(uri.getAuthority());
    }

    static String thumb(String pkg, String poster) {
        if (poster == null) return null;
        Uri proxy = Uri.parse(poster);
        if (!isProxy(pkg, proxy)) return null;
        String source = proxy.getQueryParameter("src");
        if (source == null) return null;
        Uri image = Uri.parse(source);
        if (image.getHost() == null
                || (!"http".equalsIgnoreCase(image.getScheme()) && !"https".equalsIgnoreCase(image.getScheme()))) return null;
        String path = thumbPath(image.getEncodedPath());
        if (path == null) return null;
        if (path.equals(image.getEncodedPath())) return poster;
        Uri.Builder thumb = image.buildUpon().encodedPath(path).clearQuery();
        for (String name : image.getQueryParameterNames()) {
            if ("tag".equals(name) || "width".equals(name) || "height".equals(name)
                    || "maxWidth".equals(name) || "maxHeight".equals(name)) continue;
            for (String value : image.getQueryParameters(name)) thumb.appendQueryParameter(name, value);
        }
        thumb.appendQueryParameter("maxWidth", "480");
        Uri.Builder request = proxy.buildUpon().clearQuery().appendQueryParameter("src", thumb.build().toString());
        String version = proxy.getQueryParameter("v");
        if (version != null) request.appendQueryParameter("v", version);
        return request.build().toString();
    }

    static String thumbPath(String path) {
        if (path == null) return null;
        Matcher match = IMAGE_PATH.matcher(path);
        return match.matches() ? match.group(1) + "Thumb" : null;
    }

    /** The episode's published poster may be a still. Ask Jellyfin's search provider for the parent series. */
    static String seriesLandscape(Context context, Suggestions.Program program, CancellationSignal cancel) {
        String series = program.jellyfinSeries;
        if (series == null || series.length() > 300 || cancel.isCanceled()) return null;
        String authority = program.pkg + ".content";
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        if (provider == null || !program.pkg.equals(provider.packageName)) return null;
        Uri uri = new Uri.Builder().scheme("content").authority(authority).appendPath("suggestions")
                .appendPath(SearchManager.SUGGEST_URI_PATH_QUERY).appendPath(series)
                .appendQueryParameter(SearchManager.SUGGEST_PARAMETER_LIMIT, "10").build();
        String episodeId = program.intent.getStringExtra("ItemId");
        try (Cursor results = context.getContentResolver().query(uri, null, null, null, null, cancel)) {
            if (results == null) return null;
            int titleCol = results.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_TEXT_1);
            int itemCol = results.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_INTENT_DATA);
            int imageCol = results.getColumnIndexOrThrow(SearchManager.SUGGEST_COLUMN_RESULT_CARD_IMAGE);
            String landscape = null;
            while (!cancel.isCanceled() && results.moveToNext()) {
                // Jellyfin can publish a duration on a series. Match its name and exclude the episode itself.
                String item = results.getString(itemCol);
                if (item == null || item.equalsIgnoreCase(episodeId)
                        || !series.equalsIgnoreCase(results.getString(titleCol))) continue;
                String candidate = thumb(program.pkg, results.getString(imageCol));
                if (candidate == null) continue;
                if (landscape != null && !landscape.equals(candidate)) return null;
                landscape = candidate;
            }
            return landscape;
        } catch (IllegalArgumentException | SecurityException | SQLException ignored) {
            return null;
        }
    }

    /** The proxy returns its packaged fallback bitmap when the requested image is missing. */
    boolean isPlaceholder(Context context, String pkg, Bitmap image) {
        int[] placeholder = placeholder(context, pkg);
        if (placeholder == null) return false;
        Bitmap sample = Bitmap.createScaledBitmap(image, SAMPLE, SAMPLE, true);
        int[] actual = new int[placeholder.length];
        sample.getPixels(actual, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
        if (sample != image) sample.recycle();
        int difference = 0;
        for (int i = 0; i < actual.length; i++) {
            difference += Math.abs(Color.red(actual[i]) - Color.red(placeholder[i]))
                    + Math.abs(Color.green(actual[i]) - Color.green(placeholder[i]))
                    + Math.abs(Color.blue(actual[i]) - Color.blue(placeholder[i]));
        }
        return difference < actual.length * 3 * 9;
    }

    @SuppressLint("DiscouragedApi") // The resource belongs to the separately installed Jellyfin APK.
    private synchronized int[] placeholder(Context context, String pkg) {
        if (placeholders.containsKey(pkg)) return placeholders.get(pkg);
        int[] pixels = null;
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(pkg);
            int id = resources.getIdentifier("placeholder_icon", "drawable", pkg);
            if (id != 0) {
                Drawable drawable = resources.getDrawable(id, null);
                Bitmap sample = Bitmap.createBitmap(SAMPLE, SAMPLE, Bitmap.Config.ARGB_8888);
                drawable.setBounds(0, 0, SAMPLE, SAMPLE);
                drawable.draw(new Canvas(sample));
                pixels = new int[SAMPLE * SAMPLE];
                sample.getPixels(pixels, 0, SAMPLE, 0, 0, SAMPLE, SAMPLE);
                sample.recycle();
            }
        } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
        }
        placeholders.put(pkg, pixels);
        return pixels;
    }
}
