package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.graphics.Bitmap;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.OneShotPreDrawListener;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.NumberFormat;
import java.util.List;

/** A full-width grid item. Each horizontal page shares the dock's columns and card size. */
final class SuggestionsRow extends RecyclerView.ViewHolder {
    private final ViewGroup grid;
    private final HorizontalScrollView scroll;
    private final LinearLayout cards;
    private final TextView heading;
    private final AppAdapter adapter;
    private final AppAdapter.TileListener listener;
    private final int columns;
    private int page;
    private int measuredWidth, measuredTileWidth;

    SuggestionsRow(ViewGroup parent, AppAdapter adapter, AppAdapter.TileListener listener, int columns, boolean rtl) {
        super(LayoutInflater.from(parent.getContext()).inflate(R.layout.row_suggestions, parent, false));
        grid = parent;
        scroll = itemView.findViewById(R.id.suggestionsScroll);
        scroll.setSmoothScrollingEnabled(false);
        cards = itemView.findViewById(R.id.suggestionsCards);
        heading = itemView.findViewById(R.id.suggestionsHeading);
        adapter.styleHeading(heading, parent, R.string.suggestions);
        itemView.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        this.adapter = adapter;
        this.listener = listener;
        this.columns = columns;
        itemView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != measuredWidth) itemView.post(() -> sizeCards(false));
        });
    }

    void bind(List<Suggestions.Program> programs, SuggestionArtwork artwork) {
        View previous = cards.findFocus();
        Suggestions.Program previousProgram = previous == null ? null : (Suggestions.Program) previous.getTag();
        int focusedIndex = previous == null ? -1 : cards.indexOfChild((View) previous.getParent());
        cards.removeAllViews();
        Context context = cards.getContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        NumberFormat percent = NumberFormat.getPercentInstance(
                context.getResources().getConfiguration().getLocales().get(0));
        for (int index = 0; index < programs.size(); index++) {
            Suggestions.Program program = programs.get(index);
            View cell = inflater.inflate(R.layout.item_suggestion, cards, false);
            View card = cell.findViewById(R.id.suggestionCard);
            ImageView thumbnail = card.findViewById(R.id.suggestionArtwork);
            AppAdapter.styleCard(card);
            card.setTag(program);
            if (program.sameTitle(previousProgram)) focusedIndex = index;
            TextView title = card.findViewById(R.id.suggestionTitle);
            title.setText(program.title);
            ProgressBar track = card.findViewById(R.id.suggestionProgress);
            track.setMax(Suggestions.PROGRESS_MAX);
            track.setProgress(program.progress);
            track.setVisibility(program.progress > 0 ? View.VISIBLE : View.GONE);
            card.setContentDescription(program.title);
            if (program.progress > 0) {
                ViewCompat.setStateDescription(card, percent.format((double) program.progress / Suggestions.PROGRESS_MAX));
            }
            Bitmap cached = artwork.cached(program);
            if (cached != null) showArtwork(card, cached);
            card.setOnClickListener(v -> {
                try {
                    context.startActivity(program.intent);
                    Suggestions.recordOpen(context, program.pkg);
                } catch (ActivityNotFoundException | SecurityException e) {
                    Toast.makeText(context, context.getString(R.string.cant_open, program.title),
                            Toast.LENGTH_SHORT).show();
                }
            });
            card.setOnFocusChangeListener((v, focused) -> {
                adapter.animateCardFocus(card, thumbnail, focused, false);
                if (focused) {
                    page = cards.indexOfChild(cell) / columns;
                    alignPage();
                    listener.onSuggestionFocused(card, thumbnail.getDrawable());
                }
            });
            card.setOnKeyListener((v, key, event) -> navigate(v, key, event));
            cards.addView(cell);
        }
        page = Math.min(page, (cards.getChildCount() - 1) / columns);
        sizeCards(true);
        if (focusedIndex >= 0) focusIndex(focusedIndex);
    }

    boolean focusCard(int column) {
        return focusIndex(page * columns + column);
    }

    void updateArtwork(Suggestions.Program program, Bitmap image) {
        for (int index = 0; index < cards.getChildCount(); index++) {
            View card = cards.getChildAt(index).findViewById(R.id.suggestionCard);
            if (program.equals(card.getTag())) {
                showArtwork(card, image);
                if (card.isFocused()) {
                    listener.onSuggestionFocused(card, ((ImageView) card.findViewById(R.id.suggestionArtwork)).getDrawable());
                }
            }
        }
    }

    private static void showArtwork(View card, Bitmap bitmap) {
        ImageView image = card.findViewById(R.id.suggestionArtwork);
        image.setScaleType(bitmap.getWidth() < bitmap.getHeight()
                ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        image.setImageBitmap(bitmap);
        card.findViewById(R.id.suggestionTitle).setVisibility(View.INVISIBLE);
    }

    private void sizeCards(boolean rebound) {
        int width = adapter.innerWidth(grid);
        int tileWidth = adapter.tileWidthFor(grid);
        if (width <= 0 || (!rebound && width == measuredWidth && tileWidth == measuredTileWidth)) return;
        measuredWidth = width;
        measuredTileWidth = tileWidth;
        adapter.styleHeading(heading, grid, R.string.suggestions);
        int height = AppAdapter.tileHeightPx(tileWidth);
        scroll.setPadding(0, adapter.suggestionTopPadding(height), 0, scroll.getPaddingBottom());
        int pages = (cards.getChildCount() + columns - 1) / columns;
        cards.setMinimumWidth(pages * width);
        for (int index = 0; index < cards.getChildCount(); index++) {
            View cell = cards.getChildAt(index);
            View card = cell.findViewById(R.id.suggestionCard);
            ViewGroup.LayoutParams cellSize = cell.getLayoutParams();
            cellSize.width = cellWidth(width, columns, index % columns);
            cellSize.height = height;
            cell.setLayoutParams(cellSize);
            ViewGroup.LayoutParams cardSize = card.getLayoutParams();
            cardSize.width = tileWidth;
            cardSize.height = height;
            card.setLayoutParams(cardSize);
        }
        OneShotPreDrawListener.add(scroll, this::alignPage);
    }

    static int cellWidth(int width, int columns, int column) {
        return (width * (column + 1)) / columns - (width * column) / columns;
    }

    private void alignPage() {
        boolean rtl = cards.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        int x = page * scroll.getWidth();
        scroll.scrollTo(rtl ? cards.getWidth() - scroll.getWidth() - x : x, 0);
    }

    private boolean focusIndex(int position) {
        int index = Math.min(position, cards.getChildCount() - 1);
        return index >= 0 && cards.getChildAt(index).findViewById(R.id.suggestionCard).requestFocus();
    }

    private boolean navigate(View card, int key, KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int index = cards.indexOfChild((View) card.getParent());
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
            listener.onSuggestionsExit(key, index % columns);
            return true;
        }
        if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) return false;
        boolean rtl = cards.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        boolean forward = rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT;
        int next = forward ? index + 1 : index - 1;
        if (next >= 0 && next < cards.getChildCount()) {
            focusIndex(next);
            card.playSoundEffect(key == KeyEvent.KEYCODE_DPAD_LEFT
                    ? SoundEffectConstants.NAVIGATION_LEFT : SoundEffectConstants.NAVIGATION_RIGHT);
        }
        return true;
    }
}
