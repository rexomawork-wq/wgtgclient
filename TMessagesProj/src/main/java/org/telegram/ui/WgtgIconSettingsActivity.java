package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AppIconsSelectorCell;

public class WgtgIconSettingsActivity extends BaseFragment {
    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgIcons));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        content.addView(new AppIconsSelectorCell(context, this, currentAccount),
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        int padding = AndroidUtilities.dp(20);
        Switch notificationIcon = new Switch(context);
        notificationIcon.setText(LocaleController.getString(R.string.WgtgNotificationIcon));
        notificationIcon.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        notificationIcon.setPadding(padding, padding, padding, padding);
        notificationIcon.setChecked(LauncherIconController.useWgtgNotificationIcon());
        notificationIcon.setOnCheckedChangeListener((button, checked) -> LauncherIconController.setUseWgtgNotificationIcon(checked));
        content.addView(notificationIcon, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView info = new TextView(context);
        info.setText(LocaleController.getString(R.string.WgtgNotificationIconInfo));
        info.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        info.setTextSize(14);
        info.setPadding(padding, 0, padding, padding);
        content.addView(info);
        fragmentView = scroll;
        return fragmentView;
    }
}
