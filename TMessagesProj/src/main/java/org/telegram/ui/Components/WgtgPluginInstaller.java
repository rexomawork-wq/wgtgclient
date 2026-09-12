package org.telegram.ui.Components;

import android.app.Activity;
import android.net.Uri;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.WgtgPluginsController;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import java.io.File;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class WgtgPluginInstaller {
    private static final Set<Activity> active = Collections.newSetFromMap(new WeakHashMap<>());

    public static boolean isPlugin(MessageObject message) {
        return message != null && message.getDocument() != null
            && message.getDocumentName().toLowerCase(java.util.Locale.ROOT).endsWith(".plugin");
    }

    public static void openMessage(Activity activity, MessageObject message, Theme.ResourcesProvider resources) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || active.contains(activity)) return;
        if (AndroidUtilities.openForView(message, activity, resources, false)) return;
        active.add(activity);
        NotificationCenter center = NotificationCenter.getInstance(message.currentAccount);
        String name = FileLoader.getAttachFileName(message.getDocument());
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, resources);
        boolean[] finished = {false};
        NotificationCenter.NotificationCenterDelegate[] observers = new NotificationCenter.NotificationCenterDelegate[1];
        Runnable cleanup = () -> {
            if (finished[0]) return;
            finished[0] = true;
            center.removeObserver(observers[0], NotificationCenter.fileLoaded);
            center.removeObserver(observers[0], NotificationCenter.fileLoadFailed);
            active.remove(activity);
        };
        NotificationCenter.NotificationCenterDelegate observer = (id, account, args) -> {
            if (finished[0] || args.length == 0 || !name.equals(args[0])) return;
            cleanup.run();
            progress.dismiss();
            if (activity.isFinishing() || activity.isDestroyed()) return;
            if (id == NotificationCenter.fileLoaded) {
                AndroidUtilities.openForView(message, activity, resources, false);
            } else {
                new AlertDialog.Builder(activity, resources).setTitle(LocaleController.getString(R.string.WgtgPluginError))
                    .setMessage(LocaleController.getString(R.string.WgtgPluginDownloadFailed))
                    .setPositiveButton(LocaleController.getString(R.string.OK), null).show();
            }
        };
        observers[0] = observer;
        progress.setOnDismissListener(d -> cleanup.run());
        center.addObserver(observer, NotificationCenter.fileLoaded);
        center.addObserver(observer, NotificationCenter.fileLoadFailed);
        progress.show();
        FileLoader.getInstance(message.currentAccount).loadFile(message.getDocument(), message, FileLoader.PRIORITY_HIGH, 0);
    }

    public static void show(Activity activity, Uri uri, Theme.ResourcesProvider resources, Runnable onInstalled) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed() || !active.add(activity)) return;
        AlertDialog progress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, resources);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            File incoming = null;
            try {
                incoming = WgtgPluginsController.prepareInstall(uri);
                JSONObject meta = new JSONObject(WgtgPluginsController.inspect(incoming));
                File prepared = incoming;
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    if (activity.isFinishing() || activity.isDestroyed()) {
                        prepared.delete();
                        active.remove(activity);
                        return;
                    }
                    String details = meta.optString("name") + "  " + meta.optString("version")
                        + "\nID: " + meta.optString("id") + "\n" + meta.optString("author")
                        + "\n" + AndroidUtilities.formatFileSize(prepared.length())
                        + "\n\n" + meta.optString("description");
                    if (!meta.isNull("installed_version")) details += "\n\n" + LocaleController.getString(R.string.WgtgPluginReplacing) + " " + meta.optString("installed_version");
                    String[] requirementKeys = {"app_version", "sdk_version", "min_version", "requirements", "requires"};
                    int[] requirementLabels = {R.string.WgtgPluginAppRequirement, R.string.WgtgPluginSdkRequirement,
                        R.string.WgtgPluginAppRequirement, R.string.WgtgPluginDependencies, R.string.WgtgPluginDependencies};
                    for (int i = 0; i < requirementKeys.length; i++) {
                        String value = meta.optString(requirementKeys[i]);
                        if (!value.isEmpty()) details += "\n" + LocaleController.getString(requirementLabels[i]) + ": " + value;
                    }
                    details += "\n\n" + LocaleController.getString(R.string.WgtgPluginRuntimeLimits)
                        + "\n\n" + LocaleController.getString(R.string.WgtgPluginAccessWarning);
                    SpannableString formatted = new SpannableString(details);
                    int headingEnd = details.indexOf('\n');
                    formatted.setSpan(new StyleSpan(Typeface.BOLD), 0, headingEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    formatted.setSpan(new RelativeSizeSpan(1.15f), 0, headingEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    boolean[] installing = {false};
                    AlertDialog dialog = new AlertDialog.Builder(activity, resources)
                        .setTitle(LocaleController.getString(R.string.WgtgInstallPlugin))
                        .setMessage(formatted)
                        .setNegativeButton(LocaleController.getString(R.string.No), null)
                        .setPositiveButton(LocaleController.getString(R.string.WgtgInstallPlugin), (d, which) -> {
                            installing[0] = true;
                            AlertDialog installProgress = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER, resources);
                            installProgress.setCanCancel(false);
                            installProgress.show();
                            Utilities.globalQueue.postRunnable(() -> {
                                String result;
                                boolean success;
                                try {
                                    WgtgPluginsController.installPrepared(prepared);
                                    result = LocaleController.getString(R.string.WgtgPluginInstalledDisabled);
                                    success = true;
                                } catch (Exception e) {
                                    result = e.getMessage();
                                    success = false;
                                } finally { prepared.delete(); }
                                String message = result;
                                boolean installed = success;
                                AndroidUtilities.runOnUIThread(() -> {
                                    installProgress.dismiss();
                                    active.remove(activity);
                                    if (activity.isFinishing() || activity.isDestroyed()) return;
                                    new AlertDialog.Builder(activity, resources)
                                        .setTitle(LocaleController.getString(installed ? R.string.WgtgPluginInstalled : R.string.WgtgPluginError))
                                        .setMessage(message).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
                                    if (installed && onInstalled != null) onInstalled.run();
                                });
                            });
                        }).create();
                    dialog.setOnDismissListener(d -> {
                        if (!installing[0]) { prepared.delete(); active.remove(activity); }
                    });
                    dialog.show();
                });
            } catch (Exception e) {
                if (incoming != null) incoming.delete();
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    active.remove(activity);
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    new AlertDialog.Builder(activity, resources).setTitle(LocaleController.getString(R.string.WgtgPluginError))
                        .setMessage(e.getMessage()).setPositiveButton(LocaleController.getString(R.string.OK), null).show();
                });
            }
        });
    }
}
