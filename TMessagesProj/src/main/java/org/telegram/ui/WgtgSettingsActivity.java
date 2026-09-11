package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;

public class WgtgSettingsActivity extends BaseFragment {
    @Override
    public View createView(Context context) {
        actionBar.setTitle("wgtg settings");
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        content.setPadding(padding, padding, padding, padding);
        scroll.addView(content);
        content.addView(text(context, LocaleController.getString(R.string.WgtgNicknameInfo)));
        RadioGroup modes = new RadioGroup(context);
        String[] labels = {LocaleController.getString(R.string.WgtgOff), "static", "chroma", "rgb"};
        for (int i = 0; i < labels.length; i++) {
            RadioButton button = new RadioButton(context);
            button.setId(1000 + i);
            button.setText(labels[i]);
            button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            modes.addView(button);
        }
        modes.check(1000 + WgtgConfig.nicknameMode);
        modes.setOnCheckedChangeListener((group, id) -> WgtgConfig.setNickname(id - 1000, WgtgConfig.nicknameColor));
        content.addView(modes);
        EditText color = new EditText(context);
        color.setSingleLine(true);
        color.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        color.setText(String.format(java.util.Locale.US, "#%06X", WgtgConfig.nicknameColor & 0xffffff));
        color.setHint("#RRGGBB");
        content.addView(color);
        TextView apply = text(context, LocaleController.getString(R.string.WgtgApplyColor));
        apply.setOnClickListener(v -> {
            String value = color.getText().toString().trim();
            if (!value.matches("#[0-9a-fA-F]{6}")) { color.setError("#RRGGBB"); return; }
            WgtgConfig.setNickname(1, Color.parseColor(value));
            modes.check(1001);
            apply.setTextColor(WgtgConfig.nicknameColor);
        });
        content.addView(apply);
        Switch preserve = new Switch(context);
        preserve.setText(LocaleController.getString(R.string.WgtgPreserve));
        preserve.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        preserve.setChecked(WgtgConfig.preserveDeleted(currentAccount));
        preserve.setOnCheckedChangeListener((button, checked) -> WgtgConfig.setPreserveDeleted(currentAccount, checked));
        content.addView(preserve);
        content.addView(text(context, LocaleController.getString(R.string.WgtgArchiveInfo)));
        TextView archive = text(context, LocaleController.getString(R.string.WgtgArchive));
        archive.setOnClickListener(v -> {
            WgtgDeletedMessagesActivity fragment = new WgtgDeletedMessagesActivity();
            fragment.setCurrentAccount(currentAccount);
            presentFragment(fragment);
        });
        content.addView(archive);
        fragmentView = scroll;
        return fragmentView;
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
