package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Open-Meteo's geocoding and current conditions APIs. */
final class WeatherForecast {
    static final String SOURCE_URL = "https://open-meteo.com/";
    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private WeatherForecast() {}

    record Place(String label, double latitude, double longitude) {
        boolean valid() {
            return !label.isEmpty() && Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
        }
    }

    record Reading(int degreesC, Condition condition) {}

    static List<Place> search(String query, String language) throws IOException, JSONException {
        JSONObject reply = get("https://geocoding-api.open-meteo.com/v1/search?count=6&format=json&name="
                + URLEncoder.encode(query, "UTF-8") + "&language=" + URLEncoder.encode(language, "UTF-8"));
        List<Place> places = new ArrayList<>();
        JSONArray results = reply.optJSONArray("results");
        if (results == null) return places;
        for (int i = 0; i < results.length(); i++) {
            JSONObject result = results.getJSONObject(i);
            String name = result.getString("name");
            if (name.isEmpty()) continue;
            String region = result.optString("admin1", "");
            String country = result.optString("country", "");
            String label = name;
            if (!region.isEmpty() && !region.equals(name)) label += ", " + region;
            if (!country.isEmpty() && !country.equals(name) && !country.equals(region)) label += ", " + country;
            Place place = new Place(label, result.optDouble("latitude"), result.optDouble("longitude"));
            if (place.valid()) places.add(place);
        }
        return places;
    }

    static Reading current(Place place) throws IOException, JSONException {
        String url = String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f"
                + "&longitude=%.4f&current=temperature_2m,weather_code,is_day", place.latitude(), place.longitude());
        JSONObject current = get(url).getJSONObject("current");
        double temperature = current.getDouble("temperature_2m");
        if (!Double.isFinite(temperature)) throw new JSONException("Invalid temperature");
        return new Reading((int) Math.round(temperature),
                condition(current.getInt("weather_code"), current.getInt("is_day") == 1));
    }

    private static JSONObject get(String url) throws IOException, JSONException {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Weather request failed");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                for (int count; (count = in.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Weather request cancelled");
                    if (out.size() + count > MAX_RESPONSE_BYTES) throw new IOException("Weather response too large");
                    out.write(buffer, 0, count);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }

    enum Condition {
        SUNNY(R.drawable.home_weather_sun, R.string.weather_clear),
        CLEAR_NIGHT(R.drawable.home_weather_moon, R.string.weather_clear),
        PARTLY_CLOUDY_DAY(R.drawable.home_weather_cloud_sun, R.string.weather_partly_cloudy),
        PARTLY_CLOUDY_NIGHT(R.drawable.home_weather_cloud_moon, R.string.weather_partly_cloudy),
        CLOUDY(R.drawable.home_weather_cloud, R.string.weather_cloudy),
        FOG(R.drawable.home_weather_cloud_fog, R.string.weather_fog),
        RAIN(R.drawable.home_weather_cloud_rain, R.string.weather_rain),
        SNOW(R.drawable.home_weather_cloud_snow, R.string.weather_snow),
        STORM(R.drawable.home_weather_cloud_lightning, R.string.weather_storm),
        UNKNOWN(R.drawable.home_weather_thermometer, R.string.weather_unknown);

        final int icon;
        final int label;

        Condition(int icon, int label) {
            this.icon = icon;
            this.label = label;
        }
    }

    static Condition condition(int code, boolean day) {
        switch (code) {
            case 0: return day ? Condition.SUNNY : Condition.CLEAR_NIGHT;
            case 1: case 2: return day ? Condition.PARTLY_CLOUDY_DAY : Condition.PARTLY_CLOUDY_NIGHT;
            case 3: return Condition.CLOUDY;
            case 45: case 48: return Condition.FOG;
            case 51: case 53: case 55: case 56: case 57:
            case 61: case 63: case 65: case 66: case 67:
            case 80: case 81: case 82: return Condition.RAIN;
            case 71: case 73: case 75: case 77: case 85: case 86: return Condition.SNOW;
            case 95: case 96: case 97: case 99: return Condition.STORM;
            default: return Condition.UNKNOWN;
        }
    }
}
