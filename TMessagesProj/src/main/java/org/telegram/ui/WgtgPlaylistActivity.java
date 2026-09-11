package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import java.util.ArrayList;

public class WgtgPlaylistActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final ArrayList<Long> sources = new ArrayList<>();
    private final ArrayList<MessageObject> music = new ArrayList<>();
    private LinearLayout content;
    private TextView play;
    private int requestGuid;
    private int loadingIndex;

    @Override public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgPlaylist));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(id -> { if (id == -1) finishFragment(); });
        sources.addAll(WgtgPlaylist.getSources(currentAccount));
        ScrollView scroll = new ScrollView(context);
        content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        int p = AndroidUtilities.dp(20); content.setPadding(p, p, p, p); scroll.addView(content);
        content.addView(WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgPlaylistInfo)));
        EditText input = new EditText(context); input.setSingleLine(true); input.setHint(LocaleController.getString(R.string.WgtgChatIdHint)); content.addView(input);
        TextView add = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgAddChat));
        add.setOnClickListener(v -> {
            try { long id = Long.parseLong(input.getText().toString().trim()); if (id != 0 && !sources.contains(id)) { sources.add(id); WgtgPlaylist.setSources(currentAccount, sources); input.setText(""); redraw(); } }
            catch (Exception e) { input.setError(LocaleController.getString(R.string.WgtgChatIdHint)); }
        }); content.addView(add);
        play = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgPlay));
        play.setOnClickListener(v -> loadSources()); content.addView(play);
        redraw(); fragmentView = scroll; return fragmentView;
    }

    private void redraw() {
        if (content == null) return;
        while (content.getChildCount() > 4) content.removeViewAt(4);
        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            TextView row = WgtgSettingsActivity.text(content.getContext(), "" + sources.get(i) + "    " + LocaleController.getString(R.string.Delete));
            row.setOnClickListener(v -> { sources.remove(index); WgtgPlaylist.setSources(currentAccount, sources); redraw(); });
            content.addView(row);
        }
    }

    private void loadSources() {
        if (sources.isEmpty() || requestGuid != 0) return;
        music.clear(); loadingIndex = 0; requestGuid = ConnectionsManager.generateClassGuid();
        getNotificationCenter().addObserver(this, NotificationCenter.mediaDidLoad); loadNext();
    }

    private void loadNext() {
        if (loadingIndex >= sources.size()) {
            getNotificationCenter().removeObserver(this, NotificationCenter.mediaDidLoad);
            requestGuid = 0;
            if (music.isEmpty()) { play.setText(LocaleController.getString(R.string.WgtgNoMusic)); return; }
            MediaController.getInstance().setPlaylist(music, music.get(0), 0, false, null);
            play.setText(LocaleController.getString(R.string.WgtgPlay)); return;
        }
        MediaDataController.getInstance(currentAccount).loadMedia(sources.get(loadingIndex++), 100, Integer.MAX_VALUE, 0, MediaDataController.MEDIA_MUSIC, 0, 1, requestGuid, 0, null, null);
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id != NotificationCenter.mediaDidLoad || requestGuid == 0 || (Integer) args[3] != requestGuid || (Integer) args[4] != MediaDataController.MEDIA_MUSIC) return;
        ArrayList<MessageObject> loaded = (ArrayList<MessageObject>) args[2];
        for (MessageObject object : loaded) { if (object.isMusic() && !music.contains(object)) music.add(object); }
        loadNext();
    }

    @Override public void onFragmentDestroy() {
        if (requestGuid != 0) getNotificationCenter().removeObserver(this, NotificationCenter.mediaDidLoad);
        super.onFragmentDestroy();
    }
}
