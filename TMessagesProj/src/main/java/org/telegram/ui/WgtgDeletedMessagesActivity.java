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
    private long before = Long.MAX_VALUE;
    private boolean loading;
    private boolean destroyed;

    @Override public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgArchive));
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
        TextView clear = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgClear));
        clear.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context)
            .setTitle(LocaleController.getString(R.string.WgtgClear))
            .setMessage(LocaleController.getString(R.string.WgtgClearConfirm))
            .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
            .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                if (loading) return;
                loading = true;
                getMessagesStorage().getStorageQueue().postRunnable(() -> {
                    boolean success = true;
                    try { WgtgArchive.clear(currentAccount); } catch (Exception e) { FileLog.e(e); success = false; }
                    final boolean cleared = success;
                    AndroidUtilities.runOnUIThread(() -> {
                        loading = false;
                        if (destroyed) return;
                        if (cleared) {
                            content.removeViews(1, content.getChildCount() - 1);
                            before = Long.MAX_VALUE;
                            load();
                        } else more.setText(LocaleController.getString(R.string.WgtgArchiveError));
                    });
                });
            }).create()));
        content.addView(clear);
        fragmentView = scroll;
        load();
        return fragmentView;
    }

    private void load() {
        if (loading) return;
        loading = true;
        if (more != null) content.removeView(more);
        more = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.Loading));
        content.addView(more);
        final long cursor = before;
        getMessagesStorage().getStorageQueue().postRunnable(() -> {
            ArrayList<WgtgArchive.Entry> result;
            try { result = WgtgArchive.load(currentAccount, cursor); }
            catch (Exception e) { FileLog.e(e); result = null; }
            final ArrayList<WgtgArchive.Entry> entries = result;
            AndroidUtilities.runOnUIThread(() -> {
                loading = false;
                if (destroyed) return;
                content.removeView(more);
                if (entries != null) for (WgtgArchive.Entry entry : entries) {
                    TextView text = WgtgSettingsActivity.text(content.getContext(), entry.text);
                    text.setTextIsSelectable(true);
                    content.addView(text);
                    if (entry.path != null && !entry.path.isEmpty()) {
                        TextView open = WgtgSettingsActivity.text(content.getContext(), LocaleController.getString(R.string.WgtgOpenMedia));
                        open.setOnClickListener(v -> {
                            if (getParentActivity() == null) return;
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
                    before = entry.row;
                }
                more.setText(LocaleController.getString(entries == null ? R.string.WgtgArchiveError : entries.size() == 50 ? R.string.WgtgLoadMore : before == Long.MAX_VALUE ? R.string.WgtgEmpty : R.string.WgtgArchive));
                more.setOnClickListener(entries == null || entries.size() == 50 ? v -> load() : null);
                content.addView(more);
            });
        });
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }
}
