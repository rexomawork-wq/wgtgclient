package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;

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
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        WgtgSettingsActivity.header(content, R.string.WgtgIcons);
        AppIconsSelectorCell selector = new AppIconsSelectorCell(context, this, currentAccount);
        selector.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(selector,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        WgtgSettingsActivity.info(content, R.string.WgtgIconsInfo);
        WgtgSettingsActivity.check(content, R.string.WgtgNotificationIcon, 0,
                LauncherIconController.useWgtgNotificationIcon(), false,
                LauncherIconController::setUseWgtgNotificationIcon);
        WgtgSettingsActivity.info(content, R.string.WgtgNotificationIconInfo);
        fragmentView = scroll;
        return fragmentView;
    }
}
