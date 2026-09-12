package org.telegram.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.*;

public class WgtgAlternatePasscodeActivity extends BaseFragment {
    @Override public boolean onFragmentCreate() {
        return WgtgPasscode.canAccessArchive() && !SharedConfig.passcodeHash.isEmpty() && super.onFragmentCreate();
    }

    @Override public View createView(Context context) {
        if (!WgtgPasscode.canAccessArchive() || SharedConfig.passcodeHash.isEmpty()) return fragmentView = new View(context);
        actionBar.setTitle(LocaleController.getString(R.string.WgtgAlternatePasscode));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = AndroidUtilities.dp(20);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        scroll.addView(content);
        content.addView(WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgAlternatePasscodeInfo)));
        EditText normal = input(content, R.string.WgtgNormalPasscode);
        EditText alternate = input(content, R.string.WgtgAlternatePasscode);
        EditText confirm = input(content, R.string.WgtgConfirmAlternatePasscode);
        TextView error = WgtgSettingsActivity.text(context, "");
        content.addView(error);
        TextView save = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.Save));
        content.addView(save);
        save.setOnClickListener(v -> {
            if (WgtgPasscode.configure(normal.getText().toString(), alternate.getText().toString(), confirm.getText().toString())) {
                normal.setText(""); alternate.setText(""); confirm.setText("");
                finishFragment();
            } else {
                normal.setText("");
                error.setText(LocaleController.getString(R.string.WgtgAlternatePasscodeError));
            }
        });
        if (WgtgPasscode.hasAlternate()) {
            TextView remove = WgtgSettingsActivity.text(context, LocaleController.getString(R.string.WgtgRemoveAlternatePasscode));
            content.addView(remove);
            remove.setOnClickListener(v -> {
                if (WgtgPasscode.remove(normal.getText().toString())) {
                    normal.setText(""); alternate.setText(""); confirm.setText("");
                    finishFragment();
                } else {
                    normal.setText("");
                    error.setText(LocaleController.getString(R.string.WgtgAlternatePasscodeError));
                }
            });
        }
        return fragmentView = scroll;
    }

    private EditText input(LinearLayout content, int hint) {
        EditText input = new EditText(content.getContext());
        boolean pin = SharedConfig.passcodeType == SharedConfig.PASSCODE_TYPE_PIN;
        input.setInputType(pin ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setSingleLine(true);
        input.setSaveEnabled(false);
        if (android.os.Build.VERSION.SDK_INT >= 26) input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(pin ? 4 : 128)});
        input.setHint(LocaleController.getString(hint));
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        content.addView(input);
        return input;
    }
}
