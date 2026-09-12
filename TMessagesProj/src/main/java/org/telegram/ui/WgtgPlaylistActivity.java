package org.telegram.ui;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import org.telegram.messenger.*;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.tgnet.TLRPC;
import java.util.ArrayList;

public class WgtgPlaylistActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final ArrayList<Long> sources = new ArrayList<>();
    private final ArrayList<MessageObject> music = new ArrayList<>();
    private TextSettingsCell play;
    private TextInfoPrivacyCell playbackInfo;
    private LinearLayout sourceRows;
    private final ArrayList<Long> loadingSources = new ArrayList<>();
    private final Runnable loadTimeout = () -> {
        stopLoading();
        if (playbackInfo != null) playbackInfo.setText(LocaleController.getString(R.string.WgtgPlaylistLoadError));
    };
    private int requestGuid;
    private int loadingIndex;

    @Override public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgPlaylist));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });
        sources.clear();
        sources.addAll(WgtgPlaylist.getSources(currentAccount));
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        WgtgSettingsActivity.header(content, R.string.WgtgPlaylistSources);
        TextSettingsCell add = WgtgSettingsActivity.row(content, R.string.WgtgAddChat, true);
        add.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        add.setOnClickListener(v -> selectChat());
        sourceRows = new LinearLayout(context);
        sourceRows.setOrientation(LinearLayout.VERTICAL);
        content.addView(sourceRows);
        WgtgSettingsActivity.info(content, R.string.WgtgPlaylistInfo);
        play = WgtgSettingsActivity.row(content, R.string.WgtgPlay, false);
        play.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        play.setOnClickListener(v -> loadSources());
        playbackInfo = WgtgSettingsActivity.info(content, R.string.WgtgPlaylistLimit);
        redraw(); fragmentView = scroll; return fragmentView;
    }

    private void selectChat() {
        Bundle args = new Bundle();
        args.putBoolean("onlySelect", true);
        args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_WIDGET);
        args.putBoolean("checkCanWrite", false);
        args.putBoolean("allowSwitchAccount", false);
        args.putBoolean("canSelectTopics", false);
        DialogsActivity picker = new DialogsActivity(args);
        picker.setCurrentAccount(currentAccount);
        picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
            for (MessagesStorage.TopicKey key : dids) {
                long did = key.dialogId;
                if (did != 0 && !DialogObject.isEncryptedDialog(did) && !sources.contains(did)) {
                    sources.add(did);
                }
            }
            WgtgPlaylist.setSources(currentAccount, sources);
            redraw();
            fragment.finishFragment();
            return true;
        });
        presentFragment(picker);
    }

    private void redraw() {
        if (sourceRows == null) return;
        sourceRows.removeAllViews();
        if (sources.isEmpty()) WgtgSettingsActivity.info(sourceRows, R.string.WgtgPlaylistEmpty);
        for (int i = 0; i < sources.size(); i++) {
            final long did = sources.get(i);
            TLRPC.User user = did > 0 ? getMessagesController().getUser(did) : null;
            TLRPC.Chat chat = did < 0 ? getMessagesController().getChat(-did) : null;
            String name = did == getUserConfig().getClientUserId() ? LocaleController.getString(R.string.SavedMessages)
                    : user != null ? UserObject.getUserName(user) : chat != null ? chat.title
                    : LocaleController.getString(R.string.WgtgPlaylistUnavailable);
            TextSettingsCell row = WgtgSettingsActivity.row(sourceRows, R.string.WgtgPlaylistUnavailable, i + 1 < sources.size());
            row.setTextAndValue(name, LocaleController.getString(R.string.Delete), i + 1 < sources.size());
            row.setOnClickListener(v -> showDialog(new AlertDialog.Builder(getParentActivity())
                    .setTitle(name)
                    .setMessage(LocaleController.getString(R.string.WgtgPlaylistRemove))
                    .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                    .setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                        sources.remove(Long.valueOf(did));
                        WgtgPlaylist.setSources(currentAccount, sources);
                        redraw();
                    }).create()));
        }
        play.setEnabled(!sources.isEmpty() && requestGuid == 0);
        play.setAlpha(play.isEnabled() ? 1f : 0.5f);
        play.setText(LocaleController.getString(requestGuid == 0 ? R.string.WgtgPlay : R.string.Loading), false);
    }

    private void loadSources() {
        if (sources.isEmpty() || requestGuid != 0) return;
        music.clear(); loadingIndex = 0; requestGuid = ConnectionsManager.generateClassGuid();
        loadingSources.clear();
        loadingSources.addAll(sources);
        playbackInfo.setText(LocaleController.getString(R.string.WgtgPlaylistLimit));
        redraw();
        getNotificationCenter().addObserver(this, NotificationCenter.mediaDidLoad); loadNext();
    }

    private void loadNext() {
        AndroidUtilities.cancelRunOnUIThread(loadTimeout);
        if (loadingIndex >= loadingSources.size()) {
            stopLoading();
            if (music.isEmpty()) { playbackInfo.setText(LocaleController.getString(R.string.WgtgNoMusic)); return; }
            MediaController.getInstance().setPlaylist(music, music.get(0), 0, false, null);
            play.setText(LocaleController.getString(R.string.WgtgPlay), false); return;
        }
        AndroidUtilities.runOnUIThread(loadTimeout, 30000);
        MediaDataController.getInstance(currentAccount).loadMedia(loadingSources.get(loadingIndex++), 100, Integer.MAX_VALUE, 0, MediaDataController.MEDIA_MUSIC, 0, 1, requestGuid, 0, null, null);
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || id != NotificationCenter.mediaDidLoad || requestGuid == 0 || (Integer) args[3] != requestGuid || (Integer) args[4] != MediaDataController.MEDIA_MUSIC) return;
        ArrayList<MessageObject> loaded = (ArrayList<MessageObject>) args[2];
        for (MessageObject object : loaded) { if (object.isMusic() && !music.contains(object)) music.add(object); }
        loadNext();
    }

    private void stopLoading() {
        AndroidUtilities.cancelRunOnUIThread(loadTimeout);
        getNotificationCenter().removeObserver(this, NotificationCenter.mediaDidLoad);
        if (requestGuid != 0) ConnectionsManager.getInstance(currentAccount).cancelRequestsForGuid(requestGuid);
        requestGuid = 0;
        if (play != null) redraw();
    }

    @Override public void onFragmentDestroy() {
        stopLoading();
        super.onFragmentDestroy();
    }
}
