package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.WgtgPluginsController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

public class WgtgPluginsActivity extends BaseFragment {
    private static final int REQUEST_PLUGIN = 9142;
    private LinearLayout content;
    private boolean destroyed;
    private String lastResult;
    private AlertDialog settingsDialog;

    private TextView text(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(16);
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        return view;
    }

    private void action(TextView view, boolean destructive) {
        view.setTextColor(getThemedColor(destructive ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteBlueText));
        view.setMinHeight(AndroidUtilities.dp(48));
        view.setBackground(Theme.getSelectorDrawable(false));
    }

    private void styleSwitch(Switch view) {
        view.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setTextSize(16);
        view.setMinHeight(AndroidUtilities.dp(56));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        int[][] states = {new int[]{android.R.attr.state_checked}, new int[]{}};
        view.setThumbTintList(new ColorStateList(states, new int[]{getThemedColor(Theme.key_switchTrackChecked), getThemedColor(Theme.key_windowBackgroundWhiteGrayText)}));
        view.setTrackTintList(new ColorStateList(states, new int[]{getThemedColor(Theme.key_switchTrackChecked), getThemedColor(Theme.key_divider)}));
    }

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgPlugins));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        scroll.setFillViewport(true);
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(12);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        fragmentView = scroll;
        redraw();
        return fragmentView;
    }

    private void redraw() {
        content.removeAllViews();
        if (lastResult != null) content.addView(text(content.getContext(), lastResult));
        TextView warning = text(content.getContext(), LocaleController.getString(R.string.WgtgPluginsWarning));
        warning.setTextSize(14);
        warning.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        content.addView(warning);
        TextView install = text(content.getContext(), LocaleController.getString(R.string.WgtgInstallPlugin));
        action(install, false);
        install.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        install.setOnClickListener(v -> choosePlugin());
        content.addView(install);
        if (!WgtgPluginsController.isReady()) {
            String error = WgtgPluginsController.getStartupError();
            content.addView(text(content.getContext(), error == null ? LocaleController.getString(R.string.WgtgPluginsStarting) : error));
            if (error == null) content.postDelayed(() -> { if (!destroyed) redraw(); }, 1000);
            return;
        }
        try {
            JSONArray plugins = new JSONArray(WgtgPluginsController.listJson());
            if (plugins.length() == 0) content.addView(text(content.getContext(), LocaleController.getString(R.string.WgtgNoPlugins)));
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject plugin = plugins.getJSONObject(i);
                String id = plugin.getString("id");
                LinearLayout card = new LinearLayout(content.getContext());
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(12), getThemedColor(Theme.key_windowBackgroundWhite)));
                LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
                cardParams.topMargin = AndroidUtilities.dp(12);
                content.addView(card, cardParams);
                Switch enabled = new Switch(content.getContext());
                enabled.setText(plugin.optString("name", id) + "  " + plugin.optString("version", ""));
                styleSwitch(enabled);
                enabled.setChecked(plugin.optBoolean("enabled"));
                enabled.setOnCheckedChangeListener((button, checked) -> {
                    AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
                    progress.setCanCancel(false);
                    progress.show();
                    Utilities.globalQueue.postRunnable(() -> {
                        String error = WgtgPluginsController.setEnabled(id, checked);
                        AndroidUtilities.runOnUIThread(() -> {
                            progress.dismiss();
                            if (destroyed) return;
                            lastResult = error;
                            redraw();
                        });
                    });
                });
                card.addView(enabled);
                String details = plugin.optString("author");
                if (!plugin.optString("description").isEmpty()) details += "\n" + plugin.optString("description");
                if (!plugin.optString("error").isEmpty()) details += "\n\n" + LocaleController.getString(R.string.WgtgPluginError) + ":\n" + plugin.optString("error");
                TextView info = text(content.getContext(), details.trim());
                info.setTextSize(14);
                info.setTextColor(getThemedColor(plugin.optString("error").isEmpty() ? Theme.key_windowBackgroundWhiteGrayText : Theme.key_text_RedRegular));
                info.setTextIsSelectable(true);
                if (!details.trim().isEmpty()) card.addView(info);
                if (plugin.optBoolean("enabled") && plugin.optString("error").isEmpty()) {
                    TextView settings = text(content.getContext(), LocaleController.getString(R.string.Settings));
                    action(settings, false);
                    settings.setOnClickListener(v -> showSettings(id));
                    card.addView(settings);
                }
                TextView remove = text(content.getContext(), LocaleController.getString(R.string.WgtgRemovePlugin));
                action(remove, true);
                remove.setOnClickListener(v -> new AlertDialog.Builder(content.getContext())
                    .setTitle(LocaleController.getString(R.string.WgtgRemovePlugin))
                    .setMessage(id)
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                        Utilities.globalQueue.postRunnable(() -> {
                            String error = WgtgPluginsController.uninstall(id);
                            AndroidUtilities.runOnUIThread(() -> {
                                if (destroyed) return;
                                lastResult = error;
                                redraw();
                            });
                        });
                    })
                    .show());
                card.addView(remove);
            }
        } catch (Exception e) {
            content.addView(text(content.getContext(), e.toString()));
        }
    }

    private void choosePlugin() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/octet-stream", "text/x-python", "text/plain"});
        startActivityForResult(intent, REQUEST_PLUGIN);
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_PLUGIN || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        org.telegram.ui.Components.WgtgPluginInstaller.show(getParentActivity(), uri, getResourceProvider(), () -> {
            if (!destroyed) redraw();
        });
    }

    private void showSettings(String id) {
        showSettings(id, null);
    }

    private boolean settingsAction(String id, String token, String action, String json, View view) {
        try {
            boolean result = WgtgPluginsController.settingsAction(id, token, action, json, view);
            return "change".equals(action) || result;
        } catch (Exception e) {
            showDialog(new AlertDialog.Builder(content.getContext()).setTitle(LocaleController.getString(R.string.WgtgPluginError))
                .setMessage(e.toString()).setPositiveButton(LocaleController.getString(R.string.OK), null).create());
            return false;
        }
    }

    private void showSettings(String id, String parent) {
        try {
            JSONArray rows = new JSONArray(parent == null ? WgtgPluginsController.settingsRows(id) : WgtgPluginsController.settingsRows(id, parent));
            LinearLayout layout = new LinearLayout(content.getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = AndroidUtilities.dp(20);
            layout.setPadding(padding, padding, padding, padding);
            ScrollView scroll = new ScrollView(content.getContext());
            scroll.addView(layout);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String token = row.getString("token");
                String type = row.optString("type");
                View control;
                if ("Switch".equals(row.optString("type"))) {
                    Switch toggle = new Switch(content.getContext());
                    toggle.setText(row.optString("text"));
                    styleSwitch(toggle);
                    toggle.setChecked(row.optBoolean("value"));
                    toggle.setOnCheckedChangeListener((button, value) -> settingsAction(id, token, "change", Boolean.toString(value), button));
                    layout.addView(toggle);
                    control = toggle;
                } else {
                    TextView label = text(content.getContext(), row.optString("text", ""));
                    control = label;
                    layout.addView(label);
                    if ("Header".equals(type)) {
                        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
                    } else if ("Divider".equals(type)) {
                        label.setText(row.isNull("text") ? "" : row.optString("text"));
                        label.setTextSize(14);
                        label.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                        label.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
                    } else if ("Selector".equals(type)) {
                        JSONArray items = row.getJSONArray("items");
                        CharSequence[] options = new CharSequence[items.length()];
                        for (int j = 0; j < items.length(); j++) options[j] = items.getString(j);
                        int selected = row.optInt("value");
                        String title = row.optString("text");
                        label.setText(title + (selected >= 0 && selected < options.length ? "\n" + options[selected] : ""));
                        action(label, false);
                        label.setOnClickListener(v -> new AlertDialog.Builder(content.getContext()).setTitle(title)
                            .setItems(options, (dialog, which) -> {
                                if (settingsAction(id, token, "change", Integer.toString(which), v)) {
                                    label.setText(title + "\n" + options[which]);
                                }
                            }).show());
                    } else if ("Text".equals(type)) {
                        if (row.optBoolean("accent") || row.optBoolean("red")) action(label, row.optBoolean("red"));
                        if (row.optBoolean("on_click") || row.optBoolean("create_sub_fragment")) {
                            label.setBackground(Theme.getSelectorDrawable(false));
                            label.setOnClickListener(v -> {
                                settingsAction(id, token, "on_click", "null", v);
                                if (row.optBoolean("create_sub_fragment")) showSettings(id, token);
                            });
                        }
                    }
                    if ("Input".equals(type) || "EditText".equals(type)) {
                        EditText input = new EditText(content.getContext());
                        control = input;
                        if ("EditText".equals(type)) label.setVisibility(View.GONE);
                        input.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
                        input.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                        input.setBackgroundTintList(ColorStateList.valueOf(getThemedColor(Theme.key_windowBackgroundWhiteBlueText)));
                        input.setHint(row.optString("hint", ""));
                        boolean multiline = row.optBoolean("multiline");
                        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
                        input.setSingleLine(!multiline);
                        if (row.optInt("max_length") > 0) input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(row.optInt("max_length"))});
                        input.setText(row.optString("value"));
                        layout.addView(input);
                        TextView save = text(content.getContext(), LocaleController.getString(R.string.Save));
                        action(save, false);
                        save.setOnClickListener(v -> settingsAction(id, token, "change", JSONObject.quote(input.getText().toString()), input));
                        layout.addView(save);
                    }
                }
                if (row.optBoolean("on_long_click")) control.setOnLongClickListener(v -> settingsAction(id, token, "on_long_click", "null", v));
                if (!row.isNull("icon") && control instanceof TextView) {
                    int icon = content.getResources().getIdentifier(row.optString("icon"), "drawable", content.getContext().getPackageName());
                    if (icon != 0) {
                        android.graphics.drawable.Drawable drawable = content.getResources().getDrawable(icon, content.getContext().getTheme()).mutate();
                        drawable.setTint(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                        drawable.setBounds(0, 0, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
                        ((TextView) control).setCompoundDrawablesRelative(drawable, null, null, null);
                        ((TextView) control).setCompoundDrawablePadding(AndroidUtilities.dp(12));
                    }
                }
                if (!row.isNull("subtext")) {
                    TextView caption = text(content.getContext(), row.optString("subtext"));
                    caption.setTextSize(14);
                    caption.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
                    layout.addView(caption);
                }
            }
            if (settingsDialog != null) settingsDialog.dismiss();
            settingsDialog = new AlertDialog.Builder(content.getContext()).setTitle(id).setView(scroll)
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create();
            showDialog(settingsDialog);
        } catch (Exception e) {
            lastResult = e.toString();
            redraw();
        }
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }
}
