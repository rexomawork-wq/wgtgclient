package org.telegram.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.TextSettingsCell;
import java.io.File;
import java.util.Locale;

public class WgtgThemeBuilderActivity extends BaseFragment {
    private final int[] colors = {Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4),
            Theme.getColor(Theme.key_windowBackgroundGray), Theme.getColor(Theme.key_chat_inBubble),
            Theme.getColor(Theme.key_chat_outBubble)};
    private int radius = SharedConfig.bubbleRadius;
    private LinearLayout preview;

    @Override public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgThemeBuilder));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(root);
        preview = new LinearLayout(context);
        preview.setOrientation(LinearLayout.VERTICAL);
        preview.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        root.addView(preview);
        int[] labels = {R.string.WgtgThemeAccent, R.string.WgtgThemeBackground, R.string.WgtgThemeIncoming, R.string.WgtgThemeOutgoing};
        for (int i = 0; i < colors.length; i++) {
            final int index = i;
            TextSettingsCell row = WgtgSettingsActivity.row(root, labels[i], true);
            row.setTextAndValue(LocaleController.getString(labels[i]), hex(colors[i]), true);
            row.setOnClickListener(v -> {
                EditText input = new EditText(context);
                input.setSingleLine(true);
                input.setText(hex(colors[index]));
                input.setSelectAllOnFocus(true);
                input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                AlertDialog dialog = new AlertDialog.Builder(context).setTitle(LocaleController.getString(labels[index]))
                        .setView(input).setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                        .setPositiveButton(LocaleController.getString(R.string.OK), null).create();
                showDialog(dialog);
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
                    String value = input.getText().toString().trim();
                    if (!value.matches("#?[0-9a-fA-F]{6}")) {
                        input.setError(LocaleController.getString(R.string.WgtgThemeHexError));
                        return;
                    }
                    colors[index] = Color.parseColor(value.startsWith("#") ? value : "#" + value);
                    row.setTextAndValue(LocaleController.getString(labels[index]), hex(colors[index]), true);
                    redraw();
                    dialog.dismiss();
                });
            });
        }
        WgtgSettingsActivity.slider(root, R.string.WgtgThemeRadius, radius, 0, 20, " dp", value -> {
            radius = value;
            redraw();
        });
        WgtgSettingsActivity.row(root, R.string.WgtgThemeSave, true).setOnClickListener(v -> {
            if (Theme.applyWgtgCustomTheme(colors, "WGTG Custom.attheme") == null) {
                error();
                return;
            }
            SharedConfig.bubbleRadius = radius;
            MessagesController.getGlobalMainSettings().edit().putInt("bubbleRadius", radius).apply();
            if (parentLayout != null) parentLayout.rebuildAllFragmentViews(true, true);
            finishFragment();
        });
        WgtgSettingsActivity.row(root, R.string.WgtgThemeExport, false).setOnClickListener(v -> {
            if (getParentActivity() == null) return;
            try {
                File file = Theme.createWgtgThemeFile(colors);
                android.net.Uri uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("application/x-tgtheme-android");
                intent.putExtra(Intent.EXTRA_STREAM, uri);
                intent.setClipData(android.content.ClipData.newRawUri("WGTG theme", uri));
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.WgtgThemeExport)));
            } catch (Exception e) { FileLog.e(e); error(); }
        });
        WgtgSettingsActivity.info(root, R.string.WgtgThemeBuilderInfo);
        redraw();
        fragmentView = scroll;
        return scroll;
    }

    private static String hex(int color) { return String.format(Locale.ROOT, "#%06X", color & 0xffffff); }

    private void error() {
        showDialog(new AlertDialog.Builder(getParentActivity()).setMessage(LocaleController.getString(R.string.WgtgThemeError))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private void redraw() {
        preview.removeAllViews();
        preview.setBackgroundColor(colors[1]);
        TextView title = WgtgSettingsActivity.text(preview.getContext(), LocaleController.getString(R.string.WgtgThemePreview));
        title.setTextColor(colors[0]);
        preview.addView(title);
        for (int i = 2; i < 4; i++) {
            TextView bubble = WgtgSettingsActivity.text(preview.getContext(), LocaleController.getString(
                    i == 2 ? R.string.WgtgThemeIncoming : R.string.WgtgThemeOutgoing));
            bubble.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            bubble.setTextColor(ColorUtils.calculateLuminance(colors[i]) > 0.179 ? Color.BLACK : Color.WHITE);
            GradientDrawable background = new GradientDrawable();
            background.setColor(colors[i]);
            background.setCornerRadius(AndroidUtilities.dp(radius));
            bubble.setBackground(background);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
            params.gravity = i == 2 ? Gravity.LEFT : Gravity.RIGHT;
            params.topMargin = AndroidUtilities.dp(10);
            preview.addView(bubble, params);
        }
    }
}
