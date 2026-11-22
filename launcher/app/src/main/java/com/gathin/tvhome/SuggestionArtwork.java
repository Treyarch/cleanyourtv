package com.gathin.tvhome;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.CancellationSignal;
import android.util.LruCache;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Small images for the Suggestions row. All reads and decoding run off Home's UI thread. */
final class SuggestionArtwork {
    static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_IMAGE_EDGE = 480;
    private final JellyfinArtwork jellyfin = new JellyfinArtwork();
    private final LruCache<String, CachedArtwork> cache = new LruCache<String, CachedArtwork>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, CachedArtwork art) { return art.image.getAllocationByteCount(); }
    };

    private static final class CachedArtwork {
        final Bitmap image;
        final boolean fromSeries;

        CachedArtwork(Bitmap image, boolean fromSeries) {
            this.image = image;
            this.fromSeries = fromSeries;
        }
    }

    Bitmap cached(Suggestions.Program program) {
        CachedArtwork art = cache.get(key(program));
        return art == null ? null : art.image;
    }

    Bitmap load(Context context, Suggestions.Program program, CancellationSignal cancel) {
        String key = key(program);
        CachedArtwork previous = cache.get(key);
        if (cancel.isCanceled()) return previous == null ? null : previous.image;
        if (previous != null && (program.jellyfinSeries == null || previous.fromSeries)) return previous.image;

        String seriesKey = JellyfinArtwork.seriesCacheKey(program);
        Bitmap image = seriesKey == null ? null : decode(JellyfinLandscapeCache.read(context.getCacheDir(), seriesKey));
        String seriesLandscape = null;
        if (image == null && !cancel.isCanceled()) {
            seriesLandscape = JellyfinArtwork.seriesLandscape(context, program, cancel);
            byte[] bytes = readImage(context, program.pkg, seriesLandscape, cancel);
            image = decodeArtwork(context, program.pkg, seriesLandscape, bytes);
            if (image != null && seriesKey != null && !cancel.isCanceled()) {
                JellyfinLandscapeCache.write(context.getCacheDir(), seriesKey, bytes);
            }
        }
        boolean fromSeries = image != null;
        // Keep a fallback on screen, and retry the series lookup on return to Home after Jellyfin signs in.
        if (!fromSeries && previous != null) return previous.image;
        if (image == null) image = loadPublishedArtwork(context, program, seriesLandscape, cancel);
        if (image != null && !cancel.isCanceled()) cache.put(key, new CachedArtwork(image, fromSeries));
        return image;
    }

    private Bitmap loadPublishedArtwork(Context context, Suggestions.Program program, String seriesLandscape,
                                        CancellationSignal cancel) {
        String publishedThumb = JellyfinArtwork.thumb(program.pkg, program.posterUri);
        if (publishedThumb == null) publishedThumb = JellyfinArtwork.thumb(program.pkg, program.thumbnailUri);
        Bitmap image = Objects.equals(publishedThumb, seriesLandscape)
                ? null : load(context, program.pkg, publishedThumb, cancel);
        if (image == null && !Objects.equals(publishedThumb, program.thumbnailUri)
                && !Objects.equals(seriesLandscape, program.thumbnailUri)) {
            image = load(context, program.pkg, program.thumbnailUri, cancel);
        }
        if (image == null && !Objects.equals(publishedThumb, program.posterUri)
                && !Objects.equals(program.thumbnailUri, program.posterUri)
                && !Objects.equals(seriesLandscape, program.posterUri)) {
            image = load(context, program.pkg, program.posterUri, cancel);
        }
        return image;
    }

    void clear() { cache.evictAll(); }

    private static String key(Suggestions.Program program) {
        return program.pkg + '\n' + program.thumbnailUri + '\n' + program.posterUri + '\n' + program.jellyfinSeries;
    }

    private Bitmap load(Context context, String pkg, String source, CancellationSignal cancel) {
        return decodeArtwork(context, pkg, source, readImage(context, pkg, source, cancel));
    }

    private static byte[] readImage(Context context, String pkg, String source, CancellationSignal cancel) {
        if (source == null || source.isEmpty() || cancel.isCanceled()) return null;
        Uri uri = Uri.parse(source);
        try {
            byte[] bytes;
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                bytes = SuggestionArtworkNetwork.read(uri, cancel);
            } else {
                if (!isAppArtwork(context, pkg, uri)) return null;
                try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                    bytes = readBytes(stream, cancel);
                }
            }
            if (bytes == null || cancel.isCanceled()) return null;
            return bytes;
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
    }

    private Bitmap decodeArtwork(Context context, String pkg, String source, byte[] bytes) {
        Bitmap image = decode(bytes);
        if (image != null && JellyfinArtwork.isProxy(pkg, Uri.parse(source)) && jellyfin.isPlaceholder(context, pkg, image)) {
            image.recycle();
            return null;
        }
        return image;
    }

    private static Bitmap decode(byte[] bytes) {
        if (bytes == null) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) return null;
        int edge = Math.max(options.outWidth, options.outHeight);
        options.inSampleSize = 1;
        while (edge / options.inSampleSize > MAX_IMAGE_EDGE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }

    private static boolean isAppArtwork(Context context, String pkg, Uri uri) {
        String authority = uri.getAuthority();
        if (authority == null) return false;
        if (ContentResolver.SCHEME_ANDROID_RESOURCE.equals(uri.getScheme())) return pkg.equals(authority);
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) return false;
        ProviderInfo provider = context.getPackageManager().resolveContentProvider(authority, 0);
        return provider != null && pkg.equals(provider.packageName);
    }

    static byte[] readBytes(InputStream stream, CancellationSignal cancel) throws IOException {
        if (stream == null) return null;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!cancel.isCanceled()) {
            int count = stream.read(buffer);
            if (count < 0) return bytes.toByteArray();
            if (count > MAX_IMAGE_BYTES - bytes.size()) return null;
            bytes.write(buffer, 0, count);
        }
        return null;
    }
}
