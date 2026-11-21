package com.gathin.tvhome;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javax.net.ssl.HttpsURLConnection;

/** Downloads Overflight still images off the UI thread and publishes a complete collection at once. */
final class WallpaperFeed {
    private static final int MAX_IMAGES = 100;
    private static final long MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final long MAX_COLLECTION_BYTES = 64 * 1024 * 1024;
    private static final long REFRESH_MS = 6 * 60 * 60_000L;
    private static final long RETRY_MS = 15 * 60_000L;
    // Serial even when Home recreates while a refresh is in flight.
    private static final Executor WORKER = Executors.newSingleThreadExecutor();

    private final URI url;
    private final File directory;
    private final File index;
    private final String key;
    private boolean refreshing;
    private long nextCheck;

    private WallpaperFeed(Context context, URI url) {
        this.url = url;
        key = hash(url.toString());
        directory = new File(context.getFilesDir(), "wallpaper-feed");
        index = new File(directory, "collection.txt");
    }

    static WallpaperFeed create(Context context, String url) {
        URI uri = httpsUri(url, null);
        return uri == null ? null : new WallpaperFeed(context, uri);
    }

    String key() {
        return key;
    }

    List<File> cached() {
        return cached(readIndex());
    }

    private List<File> cached(List<String> entries) {
        List<File> photos = new ArrayList<>();
        if (!entries.isEmpty() && key.equals(entries.get(0))) {
            for (String name : entries.subList(1, entries.size())) {
                File photo = new File(directory, name);
                if (photo.isFile()) photos.add(photo);
            }
        }
        return photos;
    }

    /** Called from Home's existing minute tick and on return to the foreground. */
    void refresh(Consumer<List<File>> onReady) {
        if (refreshing || SystemClock.elapsedRealtime() < nextCheck) return;
        refreshing = true;
        Handler main = new Handler(Looper.getMainLooper());
        WORKER.execute(() -> {
            long delay = RETRY_MS;
            List<String> entries = readIndex();
            try {
                long age = System.currentTimeMillis() - index.lastModified();
                if (!cached(entries).isEmpty() && age >= 0 && age < REFRESH_MS) {
                    delay = REFRESH_MS - age;
                } else {
                    entries = downloadCollection();
                    delay = REFRESH_MS;
                }
            } catch (IOException | JSONException failure) {
                Log.w("TvHome.Wallpaper", "Feed refresh failed: " + failure.getClass().getSimpleName());
            }
            List<File> photos = cached(entries);
            long checkAt = SystemClock.elapsedRealtime() + delay;
            main.post(() -> {
                refreshing = false;
                nextCheck = checkAt;
                if (!photos.isEmpty()) onReady.accept(photos);
            });
        });
    }

    private List<String> downloadCollection() throws IOException, JSONException {
        ByteArrayOutputStream manifest = new ByteArrayOutputStream();
        if (!download(url, manifest, 256 * 1024)) throw new IOException("Wallpaper feed is too large");
        Map<String, URI> images = parseImages(new String(manifest.toByteArray(), StandardCharsets.UTF_8), url);
        if (images.isEmpty()) throw new IOException("Wallpaper feed has no HTTPS images");

        Files.createDirectories(directory.toPath());
        List<String> entries = new ArrayList<>();
        entries.add(key);
        long remaining = MAX_COLLECTION_BYTES;
        File temporaryIndex = new File(directory, "collection.tmp");
        try {
            for (Map.Entry<String, URI> image : images.entrySet()) {
                if (remaining == 0) break;
                File photo = new File(directory, image.getKey());
                if (!photo.isFile() && !downloadImage(image.getValue(), photo, Math.min(remaining, MAX_IMAGE_BYTES))) continue;
                if (photo.length() > remaining) continue;
                entries.add(photo.getName());
                remaining -= photo.length();
            }
            if (entries.size() == 1) throw new IOException("Wallpaper feed has no usable images");
            Files.write(temporaryIndex.toPath(), entries, StandardCharsets.UTF_8);
            Files.move(temporaryIndex.toPath(), index.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return entries;
        } finally {
            temporaryIndex.delete();
            // Keep the previous index and its images if fetching or publishing the replacement failed.
            Set<String> keep = new HashSet<>(readIndex());
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.getName().endsWith(".image") && !keep.contains(file.getName())) file.delete();
                }
            }
        }
    }

    private boolean downloadImage(URI image, File photo, long limit) throws IOException {
        File partial = new File(directory, "image.tmp");
        try {
            try (OutputStream output = Files.newOutputStream(partial.toPath())) {
                if (!download(image, output, limit)) return false;
            } catch (FileNotFoundException gone) {
                return false;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(partial.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            Files.move(partial.toPath(), photo.toPath(), StandardCopyOption.ATOMIC_MOVE);
            return true;
        } finally {
            partial.delete();
        }
    }

    private List<String> readIndex() {
        try {
            return Files.readAllLines(index.toPath(), StandardCharsets.UTF_8);
        } catch (IOException unavailable) {
            return Collections.emptyList();
        }
    }

    private static Map<String, URI> parseImages(String json, URI base) throws JSONException {
        JSONArray array = new JSONArray(json);
        Map<String, URI> images = new LinkedHashMap<>();
        for (int i = 0; i < array.length() && images.size() < MAX_IMAGES; i++) {
            JSONObject row = array.optJSONObject(i);
            if (row == null) continue;
            Object field = row.opt("url_img");
            URI image = field instanceof String ? httpsUri(((String) field).trim(), base) : null;
            if (image != null) images.putIfAbsent(hash(image.toString()) + ".image", image);
        }
        return images;
    }

    private static URI httpsUri(String value, URI base) {
        if (value == null || value.isEmpty()) return null;
        try {
            URI uri = URI.create(value);
            if (base != null) uri = base.resolve(uri);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null
                    ? uri : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static String hash(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(64);
            for (byte b : bytes) {
                name.append(Character.forDigit((b & 0xff) >> 4, 16));
                name.append(Character.forDigit(b & 15, 16));
            }
            return name.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new AssertionError(unavailable);
        }
    }

    private static boolean download(URI url, OutputStream output, long limit) throws IOException {
        for (int redirects = 0; redirects <= 3; redirects++) {
            HttpsURLConnection connection = (HttpsURLConnection) url.toURL().openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setInstanceFollowRedirects(false);
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    url = httpsUri(connection.getHeaderField("Location"), url);
                    if (url == null) throw new IOException("Invalid HTTPS redirect");
                    continue;
                }
                if (status == 404 || status == 410) throw new FileNotFoundException();
                if (status != 200) throw new IOException("Unexpected wallpaper response: " + status);
                if (connection.getContentLengthLong() > limit) return false;
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        limit -= count;
                        if (limit < 0) return false;
                        output.write(buffer, 0, count);
                    }
                }
                return true;
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many wallpaper redirects");
    }
}
