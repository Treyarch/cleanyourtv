package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/** Standard builds ship with local wallpapers. */
final class WallpaperFeed {
    private WallpaperFeed() {}

    static WallpaperFeed create(Context context, String url) {
        return null;
    }

    String key() {
        throw new AssertionError();
    }

    List<File> cached() {
        throw new AssertionError();
    }

    void refresh(Consumer<List<File>> onReady) {
        throw new AssertionError();
    }
}
