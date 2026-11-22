package com.gathin.tvhome;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** App-private copies of verified series art for startup before Jellyfin's search is available. */
final class JellyfinLandscapeCache {
    private static final int MAX_ENTRIES = 24;
    private static final long MAX_BYTES = 8 * 1024 * 1024;

    static synchronized byte[] read(File cacheDir, String seriesKey) {
        File image = file(cacheDir, seriesKey);
        long size = image.length();
        if (size <= 0 || size > SuggestionArtwork.MAX_IMAGE_BYTES) return null;
        try {
            return Files.readAllBytes(image.toPath());
        } catch (IOException ignored) {
            return null;
        }
    }

    static synchronized void write(File cacheDir, String seriesKey, byte[] bytes) {
        if (bytes.length == 0 || bytes.length > SuggestionArtwork.MAX_IMAGE_BYTES) return;
        File image = file(cacheDir, seriesKey);
        File dir = image.getParentFile();
        if (dir == null || (!dir.isDirectory() && !dir.mkdir())) return;
        File staging = new File(dir, "landscape.tmp");
        try {
            Files.write(staging.toPath(), bytes);
            Files.move(staging.toPath(), image.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            prune(dir, image);
        } catch (IOException ignored) {
            staging.delete();
        }
    }

    private static File file(File cacheDir, String seriesKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(seriesKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder name = new StringBuilder(digest.length * 2);
            for (byte b : digest) name.append(Character.forDigit((b & 0xff) >> 4, 16))
                    .append(Character.forDigit(b & 0xf, 16));
            return new File(new File(cacheDir, "jellyfin-landscapes"), name.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static void prune(File dir, File newest) {
        File[] images = dir.listFiles();
        if (images == null) return;
        Arrays.sort(images, Comparator.comparingLong(File::lastModified).reversed());
        int entries = 1;
        long bytes = newest.length();
        for (File image : images) {
            if (image.equals(newest)) continue;
            long size = image.length();
            if (entries >= MAX_ENTRIES || size > MAX_BYTES - bytes) {
                image.delete();
            } else {
                entries++;
                bytes += size;
            }
        }
    }
}
