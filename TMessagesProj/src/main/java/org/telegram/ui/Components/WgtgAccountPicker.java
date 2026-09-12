package org.telegram.ui.Components;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.telegram.messenger.*;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.AccountSelectCell;
import java.util.ArrayList;
import java.util.Locale;
import java.util.function.IntConsumer;

public class WgtgAccountPicker extends LinearLayout {
    private final SharedPreferences prefs;
    private final LinearLayout rows;
    private final IntConsumer select;
    private String query = "";
    private final int[] colors = {0, 0xff579be5, 0xffbd77d5, 0xff42a884, 0xffde8260};

    public WgtgAccountPicker(Context context, IntConsumer select) {
        super(context);
        this.select = select;
        prefs = context.getSharedPreferences("wgtg_accounts", Context.MODE_PRIVATE);
        setOrientation(VERTICAL);
        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        search.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray));
        search.setHint(LocaleController.getString(R.string.WgtgAccountSearch));
        addView(search, LayoutHelper.createLinear(-1, 48, 16, 0, 16, 0));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString().trim().toLowerCase(Locale.ROOT);
                redraw();
            }
            public void afterTextChanged(Editable s) {}
        });
        TextView hint = text(LocaleController.getString(R.string.WgtgAccountHint));
        addView(hint);
        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(VERTICAL);
        scroll.addView(rows);
        addView(scroll, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(Math.min(320,
                UserConfig.getActivatedAccountsCount() * 80))));
        redraw();
    }

    private TextView text(String value) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
        view.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(6));
        return view;
    }

    private void redraw() {
        if (rows == null) return;
        rows.removeAllViews();
        ArrayList<Integer> accounts = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (UserConfig.getInstance(i).getCurrentUser() != null) accounts.add(i);
        }
        accounts.sort((a, b) -> Boolean.compare(prefs.getBoolean("pin_" + UserConfig.getInstance(b).getClientUserId(), false),
                prefs.getBoolean("pin_" + UserConfig.getInstance(a).getClientUserId(), false)));
        for (int account : accounts) {
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            String label = prefs.getString("label_" + user.id, "");
            String searchable = UserObject.getUserName(user) + " " + user.username + " " + user.phone + " " + label;
            if (!searchable.toLowerCase(Locale.ROOT).contains(query)) continue;
            boolean pinned = prefs.getBoolean("pin_" + user.id, false);
            int color = prefs.getInt("color_" + user.id, 0);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(VERTICAL);
            row.setBackground(Theme.getSelectorDrawable(false));
            AccountSelectCell cell = new AccountSelectCell(getContext(), false);
            cell.setAccount(account, true);
            row.addView(cell, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(56)));
            if (pinned || !label.isEmpty() || color != 0) {
                TextView subtitle = text((pinned ? "* " : "") + (label.isEmpty() ? LocaleController.getString(R.string.WgtgAccountLocal) : label));
                if (color != 0) subtitle.setTextColor(color);
                row.addView(subtitle);
            }
            row.setOnClickListener(v -> {
                if (UserConfig.getInstance(account).getClientUserId() == user.id) select.accept(account);
            });
            row.setOnLongClickListener(v -> { edit(user); return true; });
            rows.addView(row);
        }
        if (rows.getChildCount() == 0) rows.addView(text(LocaleController.getString(R.string.NoResult)));
    }

    private void edit(TLRPC.User user) {
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(VERTICAL);
        content.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        EditText label = new EditText(getContext());
        label.setSingleLine(true);
        label.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        label.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        label.setHintTextColor(Theme.getColor(Theme.key_dialogTextGray));
        label.setHint(LocaleController.getString(R.string.WgtgAccountLocal));
        label.setText(prefs.getString("label_" + user.id, ""));
        content.addView(label);
        CheckBox pin = new CheckBox(getContext());
        pin.setText(LocaleController.getString(R.string.WgtgAccountPin));
        pin.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        pin.setChecked(prefs.getBoolean("pin_" + user.id, false));
        content.addView(pin);
        int[] selected = {prefs.getInt("color_" + user.id, 0)};
        int[] names = {R.string.WgtgFormatDefault, R.string.WgtgAccountBlue, R.string.WgtgAccountPurple,
                R.string.WgtgAccountGreen, R.string.WgtgAccountOrange};
        android.widget.RadioGroup group = new android.widget.RadioGroup(getContext());
        for (int i = 0; i < colors.length; i++) {
            final int index = i;
            android.widget.RadioButton button = new android.widget.RadioButton(getContext());
            button.setId(View.generateViewId());
            button.setText(LocaleController.getString(names[i]));
            button.setTextColor(colors[i] == 0 ? Theme.getColor(Theme.key_dialogTextBlack) : colors[i]);
            group.addView(button);
            button.setChecked(colors[i] == selected[0]);
            button.setOnClickListener(v -> selected[0] = colors[index]);
        }
        content.addView(group);
        new AlertDialog.Builder(getContext()).setTitle(UserObject.getUserName(user)).setView(content)
                .setNegativeButton(LocaleController.getString(R.string.Cancel), null)
                .setPositiveButton(LocaleController.getString(R.string.Save), (dialog, which) -> {
                    prefs.edit().putString("label_" + user.id, label.getText().toString().trim())
                            .putBoolean("pin_" + user.id, pin.isChecked()).putInt("color_" + user.id, selected[0]).apply();
                    redraw();
                }).show();
    }
}
