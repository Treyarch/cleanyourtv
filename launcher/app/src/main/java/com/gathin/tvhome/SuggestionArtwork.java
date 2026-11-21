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

/** Small in-memory images for the current Suggestions row. All reads and decoding run off Home's UI thread. */
final class SuggestionArtwork {
    static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;
    private static final int MAX_IMAGE_EDGE = 480;
    private final JellyfinArtwork jellyfin = new JellyfinArtwork();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap image) { return image.getAllocationByteCount(); }
    };

    Bitmap cached(Suggestions.Program program) {
        return cache.get(key(program));
    }

    Bitmap load(Context context, Suggestions.Program program, CancellationSignal cancel) {
        Bitmap image = cached(program);
        if (image != null || cancel.isCanceled()) return image;
        String landscape = JellyfinArtwork.thumb(program.pkg, program.posterUri);
        if (landscape == null) landscape = JellyfinArtwork.thumb(program.pkg, program.thumbnailUri);
        image = load(context, program.pkg, landscape, cancel);
        if (image == null && program.jellyfinSeries != null && !cancel.isCanceled()) {
            String series = JellyfinArtwork.seriesLandscape(context, program, cancel);
            if (!Objects.equals(series, landscape)) image = load(context, program.pkg, series, cancel);
        }
        if (image == null && !Objects.equals(landscape, program.thumbnailUri)) {
            image = load(context, program.pkg, program.thumbnailUri, cancel);
        }
        if (image == null && !Objects.equals(landscape, program.posterUri)
                && !Objects.equals(program.thumbnailUri, program.posterUri)) {
            image = load(context, program.pkg, program.posterUri, cancel);
        }
        if (image != null && !cancel.isCanceled()) cache.put(key(program), image);
        return image;
    }

    void clear() { cache.evictAll(); }

    private static String key(Suggestions.Program program) {
        return program.pkg + '\n' + program.thumbnailUri + '\n' + program.posterUri + '\n' + program.jellyfinSeries;
    }

    private Bitmap load(Context context, String pkg, String source, CancellationSignal cancel) {
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
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int edge = Math.max(options.outWidth, options.outHeight);
            options.inSampleSize = 1;
            while (edge / options.inSampleSize > MAX_IMAGE_EDGE) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            Bitmap image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
            if (image != null && JellyfinArtwork.isProxy(pkg, uri) && jellyfin.isPlaceholder(context, pkg, image)) {
                image.recycle();
                return null;
            }
            return image;
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
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
