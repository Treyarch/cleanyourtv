package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SeasonalParticlesViewTest {
    @Test
    public void particlesContinueFromTheTopAfterLeavingTheBottom() {
        assertEquals(0.75f, SeasonalParticlesView.advanceFall(0.5f, 0.25f), 0f);
        assertEquals(0f, SeasonalParticlesView.advanceFall(0.75f, 0.25f), 0f);
        assertEquals(0.125f, SeasonalParticlesView.advanceFall(0.875f, 0.25f), 0f);
    }
}
