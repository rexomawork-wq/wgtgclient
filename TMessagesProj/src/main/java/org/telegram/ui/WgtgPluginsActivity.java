package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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

    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgPlugins));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        fragmentView = scroll;
        redraw();
        return fragmentView;
    }

    private void redraw() {
        content.removeAllViews();
        if (lastResult != null) content.addView(WgtgSettingsActivity.text(content.getContext(), lastResult));
        content.addView(WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgPluginsWarning)));
        TextView install = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgInstallPlugin));
        install.setOnClickListener(v -> choosePlugin());
        content.addView(install);
        if (!WgtgPluginsController.isReady()) {
            String error = WgtgPluginsController.getStartupError();
            content.addView(WgtgSettingsActivity.text(content.getContext(), error == null ? LocaleController.getString(R.string.WgtgPluginsStarting) : error));
            if (error == null) content.postDelayed(() -> { if (!destroyed) redraw(); }, 1000);
            return;
        }
        try {
            JSONArray plugins = new JSONArray(WgtgPluginsController.listJson());
            if (plugins.length() == 0) content.addView(WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgNoPlugins)));
            for (int i = 0; i < plugins.length(); i++) {
                JSONObject plugin = plugins.getJSONObject(i);
                String id = plugin.getString("id");
                Switch enabled = new Switch(content.getContext());
                enabled.setText(plugin.optString("name", id) + "  " + plugin.optString("version", ""));
                enabled.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
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
                content.addView(enabled);
                String details = plugin.optString("author");
                if (!plugin.optString("description").isEmpty()) details += "\n" + plugin.optString("description");
                if (!plugin.optString("error").isEmpty()) details += "\n\n" + LocaleController.getString(R.string.WgtgPluginError) + ":\n" + plugin.optString("error");
                TextView info = WgtgSettingsActivity.text(content.getContext(), details);
                info.setTextIsSelectable(true);
                content.addView(info);
                if (plugin.optBoolean("enabled") && plugin.optString("error").isEmpty()) {
                    TextView settings = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.Settings));
                    settings.setOnClickListener(v -> showSettings(id));
                    content.addView(settings);
                }
                TextView remove = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgRemovePlugin));
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
                content.addView(remove);
            }
        } catch (Exception e) {
            content.addView(WgtgSettingsActivity.text(content.getContext(), e.toString()));
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
        try {
            JSONArray rows = new JSONArray(WgtgPluginsController.settingsRows(id));
            LinearLayout layout = new LinearLayout(content.getContext());
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = AndroidUtilities.dp(20);
            layout.setPadding(padding, padding, padding, padding);
            ScrollView scroll = new ScrollView(content.getContext());
            scroll.addView(layout);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String key = row.optString("key");
                if ("Switch".equals(row.optString("type"))) {
                    Switch toggle = new Switch(content.getContext());
                    toggle.setText(row.optString("text"));
                    toggle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    toggle.setChecked(row.optBoolean("value"));
                    toggle.setOnCheckedChangeListener((button, value) -> WgtgPluginsController.setSetting(id, key, Boolean.toString(value)));
                    layout.addView(toggle);
                } else {
                    layout.addView(WgtgSettingsActivity.text(content.getContext(), row.optString("text")));
                    if ("Input".equals(row.optString("type"))) {
                        EditText input = new EditText(content.getContext());
                        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                        input.setText(row.optString("value"));
                        layout.addView(input);
                        TextView save = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.Save));
                        save.setOnClickListener(v -> WgtgPluginsController.setSetting(id, key, JSONObject.quote(input.getText().toString())));
                        layout.addView(save);
                    }
                }
                if (!row.isNull("subtext")) layout.addView(WgtgSettingsActivity.text(content.getContext(), row.optString("subtext")));
            }
            showDialog(new AlertDialog.Builder(content.getContext()).setTitle(id).setView(scroll)
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
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
