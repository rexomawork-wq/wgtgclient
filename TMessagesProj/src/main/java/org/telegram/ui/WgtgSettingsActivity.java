package org.telegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;

public class WgtgSettingsActivity extends BaseFragment {
    @Override
    public View createView(Context context) {
        actionBar.setTitle(LocaleController.getString(R.string.WgtgSettings));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);
        header(content, R.string.WgtgPrivacy);
        check(content, R.string.WgtgGhostTitle, R.string.WgtgGhostInfo, WgtgConfig.ghostMode, true,
                WgtgConfig::setGhostMode);
        check(content, R.string.WgtgConfirmMedia, 0, WgtgConfig.confirmMedia, false,
                WgtgConfig::setConfirmMedia);
        info(content, R.string.WgtgPrivacyInfo);
        header(content, R.string.WgtgArchive);
        check(content, R.string.WgtgPreserve, 0, WgtgConfig.preserveDeleted(currentAccount), true,
                checked -> WgtgConfig.setPreserveDeleted(currentAccount, checked));
        TextSettingsCell archive = row(content, R.string.WgtgArchive, false);
        archive.setOnClickListener(v -> open(new WgtgDeletedMessagesActivity()));
        info(content, R.string.WgtgArchiveInfo);
        header(content, R.string.WgtgPersonalization);
        TextSettingsCell icons = row(content, R.string.WgtgIcons, true);
        icons.setOnClickListener(v -> open(new WgtgIconSettingsActivity()));
        TextSettingsCell plugins = row(content, R.string.WgtgPlugins, true);
        plugins.setOnClickListener(v -> open(new WgtgPluginsActivity()));
        TextSettingsCell playlist = row(content, R.string.WgtgPlaylist, false);
        playlist.setOnClickListener(v -> open(new WgtgPlaylistActivity()));
        info(content, R.string.WgtgPersonalizationInfo);
        fragmentView = scroll;
        return fragmentView;
    }

    private void open(BaseFragment fragment) {
        fragment.setCurrentAccount(currentAccount);
        presentFragment(fragment);
    }

    static void header(LinearLayout content, int title) {
        HeaderCell cell = new HeaderCell(content.getContext());
        cell.setText(LocaleController.getString(title));
        cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        content.addView(cell);
    }

    static TextInfoPrivacyCell info(LinearLayout content, int text) {
        TextInfoPrivacyCell cell = new TextInfoPrivacyCell(content.getContext());
        cell.setText(LocaleController.getString(text));
        content.addView(cell);
        return cell;
    }

    static TextSettingsCell row(LinearLayout content, int title, boolean divider) {
        TextSettingsCell cell = new TextSettingsCell(content.getContext());
        cell.setText(LocaleController.getString(title), divider);
        cell.setBackground(Theme.getSelectorDrawable(true));
        content.addView(cell);
        return cell;
    }

    static void check(LinearLayout content, int title, int description, boolean checked,
                      boolean divider, java.util.function.Consumer<Boolean> onChange) {
        TextCheckCell cell = new TextCheckCell(content.getContext());
        if (description == 0) {
            cell.setTextAndCheck(LocaleController.getString(title), checked, divider);
        } else {
            cell.setTextAndValueAndCheck(LocaleController.getString(title),
                    LocaleController.getString(description), checked, true, divider);
        }
        cell.setBackground(Theme.getSelectorDrawable(true));
        cell.setOnClickListener(v -> {
            boolean value = !cell.isChecked();
            onChange.accept(value);
            cell.setChecked(value);
        });
        content.addView(cell);
    }

    static TextView text(Context context, CharSequence value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(16);
        view.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        view.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
        return view;
    }
}
