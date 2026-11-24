package com.gathin.tvhome;

import org.junit.Test;

import static com.gathin.tvhome.WeatherForecast.Condition.*;
import static org.junit.Assert.*;

public class WeatherForecastTest {
    @Test
    public void clearAndPartlyCloudyUseDayAndNightIcons() {
        assertEquals(SUNNY, WeatherForecast.condition(0, true));
        assertEquals(CLEAR_NIGHT, WeatherForecast.condition(0, false));
        assertEquals(PARTLY_CLOUDY_DAY, WeatherForecast.condition(1, true));
        assertEquals(PARTLY_CLOUDY_NIGHT, WeatherForecast.condition(2, false));
    }

    @Test
    public void precipitationAndUnknownConditionsHaveDistinctIcons() {
        assertEquals(RAIN, WeatherForecast.condition(57, true));
        assertEquals(RAIN, WeatherForecast.condition(82, false));
        assertEquals(SNOW, WeatherForecast.condition(77, true));
        assertEquals(SNOW, WeatherForecast.condition(86, false));
        assertEquals(STORM, WeatherForecast.condition(97, false));
        assertEquals(FOG, WeatherForecast.condition(48, true));
        assertEquals(CLOUDY, WeatherForecast.condition(3, false));
        assertEquals(UNKNOWN, WeatherForecast.condition(100, true));
    }

    @Test
    public void rejectsMissingOrInvalidCoordinatesButAcceptsZero() {
        assertTrue(new WeatherForecast.Place("Place", 0, 0).valid());
        assertFalse(new WeatherForecast.Place("", 0, 0).valid());
        assertFalse(new WeatherForecast.Place("Place", Double.NaN, 0).valid());
        assertFalse(new WeatherForecast.Place("Place", 0, -181).valid());
        assertFalse(new WeatherForecast.Place("Place", 91, 0).valid());
    }
}
