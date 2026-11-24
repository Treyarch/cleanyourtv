package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.InputFilter;
import android.text.InputType;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The city selected on this TV, independently of the current theme. */
final class WeatherSettings {
    private static final String PREFS = "home_weather";
    private static final String LABEL = "label";
    private static final String LATITUDE = "latitude";
    private static final String LONGITUDE = "longitude";

    private final Activity activity;
    private final TextView row;

    static void bind(Activity activity, LinearLayout clockChoices) {
        LinearLayout settings = (LinearLayout) clockChoices.getParent();
        TextView row = (TextView) activity.getLayoutInflater().inflate(R.layout.weather_settings_row, settings, false);
        settings.addView(row, settings.indexOfChild(clockChoices) + 1);
        new WeatherSettings(activity, row);
    }

    private WeatherSettings(Activity activity, TextView row) {
        this.activity = activity;
        this.row = row;
        updateRow();
        row.setOnClickListener(v -> pickCity());
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static WeatherForecast.Place place(Context context) {
        SharedPreferences saved = preferences(context);
        String label = saved.getString(LABEL, "");
        if (label.isEmpty()) return null;
        long missing = Double.doubleToLongBits(Double.NaN);
        WeatherForecast.Place place = new WeatherForecast.Place(label,
                Double.longBitsToDouble(saved.getLong(LATITUDE, missing)),
                Double.longBitsToDouble(saved.getLong(LONGITUDE, missing)));
        return place.valid() ? place : null;
    }

    private void updateRow() {
        WeatherForecast.Place place = place(activity);
        row.setText(place == null ? activity.getString(R.string.weather_off)
                : activity.getString(R.string.weather_selected, place.label()));
    }

    private void pickCity() {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setHint(R.string.weather_city_hint);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        name.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        name.setFilters(new InputFilter[]{new InputFilter.LengthFilter(100)});
        int padding = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        LinearLayout field = new LinearLayout(activity);
        field.setOrientation(LinearLayout.VERTICAL);
        field.setPadding(padding, 0, padding, 0);
        field.addView(name, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        Button source = new Button(activity, null, android.R.attr.borderlessButtonStyle);
        source.setText(R.string.weather_credit);
        source.setAllCaps(false);
        source.setOnClickListener(v -> openCredit());
        field.addView(source, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);

        AlertDialog.Builder builder = dialog()
                .setTitle(R.string.weather_location)
                .setMessage(R.string.weather_search_info)
                .setView(field)
                .setPositiveButton(R.string.weather_search, null)
                .setNegativeButton(android.R.string.cancel, null);
        if (place(activity) != null) {
            builder.setNeutralButton(R.string.weather_turn_off, (dialog, which) -> {
                preferences(activity).edit().clear().apply();
                updateRow();
            });
        }
        AlertDialog picker = builder.create();
        picker.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        ExecutorService io = Executors.newSingleThreadExecutor();
        picker.setOnDismissListener(dialog -> io.shutdownNow());
        picker.show();
        name.requestFocus();
        Button searchButton = picker.getButton(DialogInterface.BUTTON_POSITIVE);
        searchButton.setOnClickListener(v -> search(picker, name, searchButton, io));
        name.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            search(picker, name, searchButton, io);
            return true;
        });
    }

    private void search(AlertDialog picker, EditText name, Button button, ExecutorService io) {
        if (!picker.isShowing() || !button.isEnabled()) return;
        String query = name.getText().toString().trim();
        if (query.length() < 2) {
            picker.setMessage(activity.getString(R.string.weather_search_short));
            return;
        }
        button.setEnabled(false);
        picker.setMessage(activity.getString(R.string.weather_searching));
        String language = activity.getResources().getConfiguration().getLocales().get(0).getLanguage();
        io.execute(() -> {
            List<WeatherForecast.Place> found = searchPlaces(query, language);
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed() || !picker.isShowing()) return;
                button.setEnabled(true);
                if (found == null) {
                    picker.setMessage(activity.getString(R.string.weather_search_failed));
                } else if (found.isEmpty()) {
                    picker.setMessage(activity.getString(R.string.weather_search_empty));
                } else {
                    choose(picker, found);
                }
            });
        });
    }

    private static List<WeatherForecast.Place> searchPlaces(String query, String language) {
        try {
            return WeatherForecast.search(query, language);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    private void openCredit() {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(WeatherForecast.SOURCE_URL)));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, WeatherForecast.SOURCE_URL, Toast.LENGTH_SHORT).show();
        }
    }

    private void choose(AlertDialog picker, List<WeatherForecast.Place> found) {
        picker.dismiss();
        CharSequence[] labels = new CharSequence[found.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = found.get(i).label();
        AlertDialog matches = dialog().setTitle(R.string.weather_location)
                .setItems(labels, (dialog, which) -> {
                    WeatherForecast.Place chosen = found.get(which);
                    preferences(activity).edit().putString(LABEL, chosen.label())
                            .putLong(LATITUDE, Double.doubleToLongBits(chosen.latitude()))
                            .putLong(LONGITUDE, Double.doubleToLongBits(chosen.longitude())).apply();
                    updateRow();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        matches.getListView().requestFocus();
        matches.getListView().setSelection(0);
    }

    private AlertDialog.Builder dialog() {
        return new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }
}
