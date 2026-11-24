package com.gathin.tvhome;

import android.app.Activity;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Displays one current reading. Refreshes are tied to Home's resumed state. */
final class HomeWeather {
    private static final long REFRESH_MS = TimeUnit.HOURS.toMillis(1);
    private static final long MAX_AGE_MS = TimeUnit.HOURS.toMillis(2);

    private final Activity activity;
    private final Handler main;
    private final TextView temperature;
    private final Runnable tick = this::refresh;
    private WeatherForecast.Place place;
    private WeatherForecast.Reading reading;
    private long readAt;
    private long refreshAt;
    private ExecutorService io;

    static HomeWeather create(Activity activity, View clock, Handler main) {
        return new HomeWeather(activity, clock, main);
    }

    private HomeWeather(Activity activity, View clock, Handler main) {
        this.activity = activity;
        this.main = main;
        LinearLayout row = (LinearLayout) clock.getParent();
        temperature = (TextView) activity.getLayoutInflater().inflate(R.layout.home_weather, row, false);
        row.addView(temperature, row.indexOfChild(clock));
    }

    static void bindSettings(Activity activity, LinearLayout clockChoices) {
        WeatherSettings.bind(activity, clockChoices);
    }

    void start() {
        WeatherForecast.Place saved = WeatherSettings.place(activity);
        if (!Objects.equals(place, saved)) {
            place = saved;
            reading = null;
            refreshAt = 0;
        }
        render();
        if (place != null) schedule();
    }

    void stop() {
        main.removeCallbacks(tick);
        if (io != null) {
            io.shutdownNow();
            io = null;
        }
    }

    private void refresh() {
        render();
        if (SystemClock.elapsedRealtime() < refreshAt) {
            schedule();
            return;
        }
        if (io == null) io = Executors.newSingleThreadExecutor();
        ExecutorService active = io;
        WeatherForecast.Place requested = place;
        active.execute(() -> {
            WeatherForecast.Reading result = fetch(requested);
            if (!Thread.currentThread().isInterrupted()) main.post(() -> receive(active, result));
        });
    }

    private static WeatherForecast.Reading fetch(WeatherForecast.Place place) {
        try {
            return WeatherForecast.current(place);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void receive(ExecutorService active, WeatherForecast.Reading result) {
        if (io != active) return;
        long now = SystemClock.elapsedRealtime();
        if (result != null) {
            reading = result;
            readAt = now;
        }
        refreshAt = now + REFRESH_MS;
        render();
        schedule();
    }

    private void schedule() {
        long next = reading == null ? refreshAt : Math.min(refreshAt, readAt + MAX_AGE_MS);
        main.postDelayed(tick, Math.max(0, next - SystemClock.elapsedRealtime()));
    }

    private void render() {
        if (reading != null && SystemClock.elapsedRealtime() - readAt >= MAX_AGE_MS) reading = null;
        if (reading == null) {
            temperature.setVisibility(View.GONE);
            return;
        }
        WeatherForecast.Condition condition = reading.condition();
        String degrees = activity.getString(R.string.weather_degrees, reading.degreesC());
        temperature.setText(degrees);
        temperature.setCompoundDrawablesRelativeWithIntrinsicBounds(condition.icon, 0, 0, 0);
        temperature.setContentDescription(activity.getString(R.string.weather_description,
                place.label(), degrees, activity.getString(condition.label)));
        temperature.setVisibility(View.VISIBLE);
    }
}
