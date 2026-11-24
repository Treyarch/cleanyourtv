package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SnowViewTest {
    @Test
    public void flakesContinueFromTheTopAfterLeavingTheBottom() {
        assertEquals(0.75f, SnowView.advanceFall(0.5f, 0.25f), 0f);
        assertEquals(0f, SnowView.advanceFall(0.75f, 0.25f), 0f);
        assertEquals(0.125f, SnowView.advanceFall(0.875f, 0.25f), 0f);
    }
}
