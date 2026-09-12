package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.WgtgFontConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/** Bundled app-font previews; no downloads or platform font aliases. */
public class WgtgFontSettingsActivity extends BaseFragment {
    private final ArrayList<LinearLayout> cards = new ArrayList<>();
    private TextView heading;
    private TextView preview;

    @Override
    public View createView(Context context) {
        cards.clear();
        actionBar.setTitle(LocaleController.getString(R.string.FontType));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(28));
        scroll.addView(root);

        heading = new TextView(context);
        heading.setText(LocaleController.getString(R.string.MessagePreview));
        heading.setTextSize(14);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(heading, LayoutHelper.createLinear(-1, -2, 8, 0, 8, 12));
        preview = new TextView(context);
        preview.setText(LocaleController.getString(R.string.FontSizePreviewLine1) + "\n" +
                LocaleController.getString(R.string.FontSizePreviewLine2));
        preview.setTextSize(22);
        preview.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20), AndroidUtilities.dp(20));
        root.addView(preview, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 20));

        for (int index = -1; index < WgtgFontConfig.NAMES.length; index++) {
            final int font = index;
            LinearLayout card = new LinearLayout(context);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setMinimumHeight(AndroidUtilities.dp(88));
            card.setPadding(AndroidUtilities.dp(20), AndroidUtilities.dp(14), AndroidUtilities.dp(20), AndroidUtilities.dp(14));
            card.setClipToOutline(true);
            TextView name = new TextView(context);
            name.setText(index < 0 ? LocaleController.getString(R.string.Default) : WgtgFontConfig.NAMES[index]);
            name.setTextSize(14);
            card.addView(name, LayoutHelper.createLinear(-1, -2));
            TextView sample = new TextView(context);
            sample.setText(LocaleController.getString(R.string.FontSizePreviewLine1));
            sample.setTextSize(18);
            sample.setTypeface(WgtgFontConfig.typeface(index));
            WgtgFontConfig.preservePreview(sample);
            card.addView(sample, LayoutHelper.createLinear(-1, -2, 0, 6, 0, 0));
            root.addView(card, LayoutHelper.createLinear(-1, -2, 0, 0, 0, 10));
            cards.add(card);
            card.setOnClickListener(v -> {
                WgtgFontConfig.select(font);
                updateColors();
                if (parentLayout != null) parentLayout.rebuildAllFragmentViews(false, false);
            });
        }
        fragmentView = scroll;
        updateColors();
        return fragmentView;
    }

    private void updateColors() {
        if (fragmentView == null) return;
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        int text = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        heading.setTextColor(accent);
        preview.setTextColor(text);
        preview.setTypeface(WgtgFontConfig.selectedTypeface());
        GradientDrawable previewBackground = new GradientDrawable();
        previewBackground.setColor(ColorUtils.blendARGB(surface, accent, .12f));
        previewBackground.setCornerRadius(AndroidUtilities.dp(22));
        preview.setBackground(previewBackground);
        for (int i = 0; i < cards.size(); i++) {
            LinearLayout card = cards.get(i);
            boolean selected = i - 1 == WgtgFontConfig.selected();
            card.setSelected(selected);
            GradientDrawable background = new GradientDrawable();
            background.setColor(selected ? ColorUtils.blendARGB(surface, accent, .08f) : surface);
            background.setCornerRadius(AndroidUtilities.dp(18));
            background.setStroke(AndroidUtilities.dp(selected ? 2 : 1), selected ? accent : ColorUtils.blendARGB(surface, accent, .18f));
            card.setBackground(background);
            card.setForeground(Theme.getSelectorDrawable(false));
            ((TextView) card.getChildAt(0)).setTextColor(selected ? accent : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            ((TextView) card.getChildAt(1)).setTextColor(text);
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        for (int key : new int[]{Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlueText4, Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_windowBackgroundWhiteGrayText2, Theme.key_listSelector}) {
            descriptions.add(new ThemeDescription(null, 0, null, null, null, this::updateColors, key));
        }
        return descriptions;
    }
}
