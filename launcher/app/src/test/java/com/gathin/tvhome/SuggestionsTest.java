package com.gathin.tvhome;

import android.media.tv.TvContract.WatchNextPrograms;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SuggestionsTest {
    private static final long DAY = TimeUnit.DAYS.toMillis(1);
    private static final long NOW = 100 * DAY;
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void groupsFrequentlyOpenedAppsInProportionToTheirUse() {
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        feeds.put("france.tv", Arrays.asList("fr1", "fr2", "fr3"));
        feeds.put("YouTube", Arrays.asList("yt1", "yt2", "yt3", "yt4", "yt5"));
        Map<String, Long> opens = new HashMap<>();
        opens.put("france.tv", 10L);
        opens.put("YouTube", 30L);

        assertEquals(Arrays.asList("yt1", "yt2", "yt3", "fr1", "yt4", "yt5", "fr2", "fr3"),
                Suggestions.rankCards(feeds, opens, Collections.emptyMap(), NOW));
    }

    @Test public void equallyUsedAppsAlternateAndGetAtMostFiveCardsEach() {
        int appCount = 6;
        Map<String, List<Integer>> feeds = new LinkedHashMap<>();
        for (int app = 0; app < appCount; app++) {
            List<Integer> feed = new ArrayList<>();
            for (int rank = 0; rank < 6; rank++) feed.add(rank * appCount + app);
            feeds.put("app-" + app, feed);
        }

        List<Integer> suggestions = Suggestions.rankCards(feeds, Collections.emptyMap(), Collections.emptyMap(), NOW);
        assertEquals(30, suggestions.size());
        for (int index = 0; index < suggestions.size(); index++) {
            assertEquals(index, suggestions.get(index).intValue());
        }
    }

    @Test public void skipsEmptyFeedsAndStopsWhenTheyRunOut() {
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        feeds.put("empty", Collections.emptyList());
        feeds.put("other", Collections.singletonList("next"));
        assertEquals(Collections.singletonList("next"),
                Suggestions.rankCards(feeds, Collections.emptyMap(), Collections.emptyMap(), NOW));
        assertTrue(Suggestions.rankCards(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), NOW)
                .isEmpty());
    }

    @Test public void putsInactiveAppsLastAndUsesRecentPlaybackWhenLaunchCountsAreMissing() {
        Map<String, List<String>> feeds = new LinkedHashMap<>();
        feeds.put("france.tv", Arrays.asList("fr1", "fr2"));
        feeds.put("Jellyfin", Arrays.asList("jf1", "jf2"));
        feeds.put("Netflix", Collections.singletonList("nf1"));
        feeds.put("Okoo", Collections.singletonList("ok1"));
        Map<String, Long> lastUsed = new HashMap<>();
        lastUsed.put("france.tv", NOW - 30 * DAY);
        lastUsed.put("Jellyfin", NOW - 2 * DAY);
        lastUsed.put("Netflix", NOW - DAY);

        assertEquals(Arrays.asList("nf1", "jf1", "jf2", "fr1", "ok1", "fr2"),
                Suggestions.rankCards(feeds, Collections.emptyMap(), lastUsed, NOW));

        lastUsed.put("france.tv", NOW);
        assertEquals(Arrays.asList("fr1", "nf1", "jf1", "fr2", "jf2", "ok1"),
                Suggestions.rankCards(feeds, Collections.emptyMap(), lastUsed, NOW));
    }

    @Test public void countsRepeatedOpensAndExpiresDaysOutsideTheRollingWindow() {
        String history = LastOpened.countOpen("", NOW - 30 * DAY);
        history = LastOpened.countOpen(history, NOW - 29 * DAY);
        history = LastOpened.countOpen(history, NOW - 29 * DAY + 1_000L);
        history = LastOpened.countOpen(history, NOW);

        assertEquals(3L, LastOpened.recentOpens(history, NOW));
        assertEquals(1L, LastOpened.recentOpens(history, NOW + DAY));
        assertEquals(0L, LastOpened.recentOpens(history, NOW + 30 * DAY));
        assertEquals(0L, LastOpened.recentOpens("", NOW));
    }

    @Test public void usesWatchNextPlaybackDatesAndIgnoresReleaseAndWatchlistDates() {
        assertEquals(900L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, 900L, 1_000L));
        assertEquals(900L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_NEXT, 900L, 1_000L));
        assertEquals(0L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_NEW, 950L, 1_000L));
        assertEquals(0L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_WATCHLIST, 980L, 1_000L));
        assertEquals(0L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, 0L, 1_000L));
        assertEquals(0L, Suggestions.playbackTime(WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE, 1_001L, 1_000L));
    }

    @Test public void progressIsScaledAndClampedToPlaybackBounds() {
        assertEquals(0, Suggestions.playbackProgress(0, 60_000));
        assertEquals(0, Suggestions.playbackProgress(5_000, 0));
        assertEquals(0, Suggestions.playbackProgress(-1, 60_000));
        assertEquals(1, Suggestions.playbackProgress(1, 60_000));
        assertEquals(250, Suggestions.playbackProgress(15_000, 60_000));
        assertEquals(Suggestions.PROGRESS_MAX, Suggestions.playbackProgress(75_000, 60_000));
        assertEquals(500, Suggestions.playbackProgress(Long.MAX_VALUE / 2 + 1, Long.MAX_VALUE));
    }

    @Test public void eachSupportedColumnCountFillsTheViewportWithoutPartialCards() {
        for (int columns = 4; columns <= 7; columns++) {
            int width = 1733;
            int total = 0;
            for (int column = 0; column < columns; column++) {
                int cell = SuggestionsRow.cellWidth(width, columns, column);
                assertTrue(cell == width / columns || cell == width / columns + 1);
                total += cell;
            }
            assertEquals(width, total);
        }
    }

    @Test public void jellyfinThumbUsesTheSameItemAndServerPath() {
        String item = "/jellyfin/Items/0123456789abcdef0123456789abcdef/Images/";
        assertEquals(item + "Thumb", JellyfinArtwork.thumbPath(item + "Primary"));
        assertEquals(item + "Thumb", JellyfinArtwork.thumbPath(item + "Backdrop/0"));
        assertEquals(item + "Thumb", JellyfinArtwork.thumbPath(item + "Thumb"));
        assertNull(JellyfinArtwork.thumbPath("/Items/movie/Images/Primary"));
        assertNull(JellyfinArtwork.thumbPath("/assets/Primary"));
    }

    @Test public void jellyfinLandscapeCanBeReadFromDiskAndIsScopedToItsServer() throws IOException {
        File cache = files.newFolder();
        String series = "server-a\nseries";
        byte[] landscape = {1, 2, 3};

        JellyfinLandscapeCache.write(cache, series, landscape);

        assertArrayEquals(landscape, JellyfinLandscapeCache.read(new File(cache.getPath()), series));
        assertNull(JellyfinLandscapeCache.read(cache, "server-b\nseries"));
    }

    @Test public void jellyfinLandscapeCacheKeepsTheNewestImagesWithinItsByteBudget() throws IOException {
        File cache = files.newFolder();
        byte[] landscape = new byte[SuggestionArtwork.MAX_IMAGE_BYTES];
        for (int i = 0; i < 3; i++) JellyfinLandscapeCache.write(cache, "series-" + i, landscape);

        assertArrayEquals(landscape, JellyfinLandscapeCache.read(cache, "series-2"));
        File[] images = new File(cache, "jellyfin-landscapes").listFiles();
        assertEquals(2, images.length);
    }
}
