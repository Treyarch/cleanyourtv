package com.gathin.tvhome;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SuggestionsTest {
    @Test public void sharesTheRowBetweenAppsInTheirPublishedOrder() {
        assertEquals(Arrays.asList("next", "recommended", "featured", "episode"),
                Suggestions.interleave(Arrays.asList(
                        Arrays.asList("next", "episode", "preview"),
                        Collections.singletonList("recommended"),
                        Collections.singletonList("featured")), 4));
    }

    @Test public void skipsEmptyFeedsAndStopsWhenTheyRunOut() {
        assertEquals(Collections.singletonList("next"),
                Suggestions.interleave(Arrays.asList(Collections.emptyList(),
                        Collections.singletonList("next")), 12));
        assertTrue(Suggestions.interleave(Collections.emptyList(), 12).isEmpty());
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
}
