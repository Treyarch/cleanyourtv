package com.gathin.tvhome;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.widget.LinearLayout;

/** Weather is only included in personal builds that enable it. */
final class HomeWeather {
    private HomeWeather() {}

    static HomeWeather create(Activity activity, View clock, Handler handler) { return null; }

    static void bindSettings(Activity activity, LinearLayout clockChoices) {}

    void start() {}

    void stop() {}
}
