package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import java.io.File;
import java.util.ArrayList;

public class WgtgDeletedMessagesActivity extends BaseFragment {
    private LinearLayout content;
    private TextView more;
    private int offset, generation;
    private String query = "";
    private long selectedDialog;
    private int selectedType;
    private final Runnable search = this::reload;
    private boolean loading;
    private boolean clearing;
    private boolean destroyed;

    @Override public boolean onFragmentCreate() {
        return WgtgPasscode.canAccessArchive() && super.onFragmentCreate();
    }

    @Override public void onResume() {
        super.onResume();
        if (!WgtgPasscode.canAccessArchive()) {
            generation++;
            if (content != null) content.removeAllViews();
            dismissCurrentDialog();
            removeSelfFromStack(true);
        }
    }

    @Override public View createView(Context context) {
        if (!WgtgPasscode.canAccessArchive()) return fragmentView = new View(context);
        actionBar.setTitle(LocaleController.getString(R.string.WgtgArchive));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        android.widget.EditText searchField = new android.widget.EditText(context);
        searchField.setSingleLine(true);
        searchField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        searchField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        searchField.setHint(LocaleController.getString(R.string.Search));
        searchField.setText(query);
        root.addView(searchField);
        searchField.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                generation++;
                AndroidUtilities.cancelRunOnUIThread(search);
                AndroidUtilities.runOnUIThread(search, 250);
            }
            public void afterTextChanged(android.text.Editable s) {}
        });
        TextView chatFilter = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgArchiveAllChats));
        if (selectedDialog != 0) chatFilter.setText(dialogName(selectedDialog) + " / " + LocaleController.getString(R.string.WgtgArchiveResetChat));
        root.addView(chatFilter);
        chatFilter.setOnClickListener(v -> {
            if (!WgtgPasscode.canAccessArchive()) return;
            if (selectedDialog != 0) {
                selectedDialog = 0;
                chatFilter.setText(LocaleController.getString(R.string.WgtgArchiveAllChats));
                reload();
                return;
            }
            android.os.Bundle args = new android.os.Bundle();
            args.putBoolean("onlySelect", true);
            args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_WIDGET);
            args.putBoolean("checkCanWrite", false);
            args.putBoolean("allowSwitchAccount", false);
            args.putBoolean("canSelectTopics", false);
            DialogsActivity picker = new DialogsActivity(args);
            picker.setCurrentAccount(currentAccount);
            picker.setDelegate((fragment, dids, message, param, notify, date, repeat, topics) -> {
                if (!dids.isEmpty()) {
                    selectedDialog = dids.get(0).dialogId;
                    chatFilter.setText(dialogName(selectedDialog) + " / " + LocaleController.getString(R.string.WgtgArchiveResetChat));
                    reload();
                }
                fragment.finishFragment();
                return true;
            });
            presentFragment(picker);
        });
        int[] types = {R.string.WgtgArchiveAllTypes, R.string.WgtgArchiveNoFile, R.string.WgtgArchiveWithFile,
                R.string.WgtgArchiveAudio, R.string.WgtgArchiveVideo, R.string.WgtgEditHistory, R.string.WgtgDeletedOnly};
        CharSequence[] typeNames = new CharSequence[types.length];
        for (int i = 0; i < types.length; i++) typeNames[i] = LocaleController.getString(types[i]);
        TextView typeFilter = WgtgSettingsActivity.text(context, typeNames[selectedType]);
        root.addView(typeFilter);
        typeFilter.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.WgtgArchiveAllTypes))
                .setItems(typeNames, (dialog, which) -> {
                    selectedType = which;
                    typeFilter.setText(typeNames[which]);
                    reload();
                }).create()));
        ScrollView scroll = new ScrollView(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        TextView clear = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgClear));
        clear.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.WgtgClear))
            .setMessage(LocaleController.getString(R.string.WgtgClearConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                if (clearing || !WgtgPasscode.canAccessArchive()) return;
                clearing = true;
                generation++;
                loading = true;
                getMessagesStorage().getStorageQueue().postRunnable(() -> {
                    boolean success = true;
                    try { WgtgArchive.clearFromUi(currentAccount); } catch (Exception e) { FileLog.e(e); success = false; }
                    final boolean cleared = success;
                    AndroidUtilities.runOnUIThread(() -> {
                        loading = false;
                        clearing = false;
                        if (destroyed) return;
                        if (cleared) {
                            reload();
                        } else more.setText(LocaleController.getString(R.string.WgtgArchiveError));
                    });
                });
            }).create()));
        root.addView(clear);
        fragmentView = root;
        reload();
        return fragmentView;
    }

    private String dialogName(long dialog) {
        org.telegram.tgnet.TLRPC.User user = dialog > 0 ? getMessagesController().getUser(dialog) : null;
        org.telegram.tgnet.TLRPC.Chat chat = dialog < 0 ? getMessagesController().getChat(-dialog) : null;
        return user != null ? UserObject.getUserName(user) : chat != null ? chat.title : Long.toString(dialog);
    }

    private void reload() {
        generation++;
        if (destroyed || clearing || !WgtgPasscode.canAccessArchive()) return;
        AndroidUtilities.cancelRunOnUIThread(search);
        loading = false;
        offset = 0;
        content.removeAllViews();
        more = null;
        load();
    }

    private void load() {
        if (loading || clearing || destroyed || !WgtgPasscode.canAccessArchive()) return;
        loading = true;
        if (more != null) content.removeView(more);
        more = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.Loading));
        content.addView(more);
        final int cursor = offset, request = generation, type = selectedType;
        final long dialog = selectedDialog;
        final String needle = query;
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            ArrayList<WgtgArchive.Entry> result;
            try { result = WgtgArchive.search(currentAccount, needle, dialog, type, cursor); }
            catch (Exception e) { FileLog.e(e); result = null; }
            final ArrayList<WgtgArchive.Entry> entries = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || request != generation || !WgtgPasscode.canAccessArchive()) return;
                loading = false;
                content.removeView(more);
                if (entries != null) for (WgtgArchive.Entry entry : entries) {
                    TextView text = WgtgSettingsActivity.text(content.getContext(), dialogName(entry.dialog) + "\n"
                            + LocaleController.getString(entry.history ? R.string.WgtgPreviousVersion : R.string.WgtgDeletedOnly) + " #" + entry.mid + "\n"
                            + java.text.DateFormat.getDateTimeInstance().format(new java.util.Date(entry.row)) + "\n" + entry.text);
                    text.setTextIsSelectable(true);
                    content.addView(text);
                    if (entry.path != null && !entry.path.isEmpty()) {
                        TextView open = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgOpenMedia));
                        open.setOnClickListener(v -> {
                            if (getParentActivity() == null || !WgtgPasscode.canAccessArchive()) return;
                            try {
                                if (!AndroidUtilities.openForView(new File(entry.path), new File(entry.path).getName(), entry.mime, getParentActivity(), getResourceProvider(), false)) {
                                    open.setText(LocaleController.getString(R.string.WgtgNoMedia));
                                }
                            } catch (Exception e) {
                                FileLog.e(e);
                                open.setText(LocaleController.getString(R.string.WgtgViewerMissing));
                            }
                        });
                        content.addView(open);
                    }
                    offset++;
                }
                more.setText(LocaleController.getString(entries == null ? R.string.WgtgArchiveError : entries.size() == 50 ? R.string.WgtgLoadMore : offset == 0 ? R.string.WgtgEmpty : R.string.WgtgArchive));
                more.setOnClickListener(entries == null || entries.size() == 50 ? v -> load() : null);
                content.addView(more);
            });
        });
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        AndroidUtilities.cancelRunOnUIThread(search);
        super.onFragmentDestroy();
    }
}
