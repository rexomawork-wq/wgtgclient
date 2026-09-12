package org.telegram.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import androidx.core.graphics.ColorUtils;
import java.util.ArrayList;

public class WgtgSettingsActivity extends BaseFragment {
    private final ArrayList<LinearLayout> cards = new ArrayList<>();
    private TextView brand;
    private TextView subtitle;

    @Override
    public View createView(Context context) {
        cards.clear();
        actionBar.setTitle(LocaleController.getString(R.string.WgtgSettings));
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(28));
        scroll.addView(root);
        brand = new TextView(context);
        brand.setText("WGTG");
        brand.setTextSize(36);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setLetterSpacing(0.08f);
        brand.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8), 0);
        root.addView(brand);
        subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.WgtgSettingsSubtitle));
        subtitle.setTextSize(15);
        subtitle.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(20));
        root.addView(subtitle);
        LinearLayout content = section(root, R.string.WgtgPrivacy);
        check(content, R.string.WgtgGhostTitle, R.string.WgtgGhostInfo, WgtgConfig.ghostMode, true,
                WgtgConfig::setGhostMode);
        check(content, R.string.WgtgConfirmMedia, 0, WgtgConfig.confirmMedia, false,
                WgtgConfig::setConfirmMedia);
        info(root, R.string.WgtgPrivacyInfo);
        content = section(root, R.string.WgtgArchive);
        check(content, R.string.WgtgPreserve, 0, WgtgConfig.preserveDeleted(currentAccount), true,
                checked -> WgtgConfig.setPreserveDeleted(currentAccount, checked));
        TextSettingsCell archive = row(content, R.string.WgtgArchive, false);
        archive.setOnClickListener(v -> open(new WgtgDeletedMessagesActivity()));
        info(root, R.string.WgtgArchiveInfo);
        content = section(root, R.string.WgtgPersonalization);
        TextSettingsCell theme = row(content, R.string.WgtgBlackTheme, true);
        String themeKey = Theme.getActiveTheme().getKey();
        int[] paletteNames = {R.string.WgtgBlackOrange, R.string.WgtgBlackPink, R.string.WgtgBlackMint,
                R.string.WgtgBlackViolet, R.string.WgtgMidnightBlue, R.string.WgtgWarmLinen};
        CharSequence[] palettes = new CharSequence[paletteNames.length];
        String selectedTheme = LocaleController.getString(R.string.WgtgThemeOther);
        for (int i = 0; i < palettes.length; i++) {
            palettes[i] = LocaleController.getString(paletteNames[i]);
            if (Theme.getWgtgThemeKey(i).equals(themeKey)) {
                selectedTheme = palettes[i].toString();
            }
        }
        theme.setTextAndValue(LocaleController.getString(R.string.WgtgBlackTheme),
                selectedTheme, true);
        theme.setOnClickListener(v -> showDialog(new AlertDialog.Builder(context)
                .setTitle(LocaleController.getString(R.string.WgtgBlackTheme))
                .setItems(palettes, (dialog, which) -> {
                    if (Theme.applyWgtgTheme(which) != null) {
                        if (parentLayout != null) {
                            parentLayout.rebuildAllFragmentViews(true, true);
                        }
                    } else {
                        showDialog(new AlertDialog.Builder(context)
                                .setMessage(LocaleController.getString(R.string.WgtgThemeError))
                                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
                    }
                }).create()));
        TextSettingsCell icons = row(content, R.string.WgtgIcons, false);
        icons.setOnClickListener(v -> open(new WgtgIconSettingsActivity()));
        info(root, R.string.WgtgBlackThemeInfo);
        content = section(root, R.string.WgtgAnimationsSection);
        check(content, R.string.WgtgSmoothChats, R.string.WgtgSmoothChatsInfo,
                SharedConfig.animationsEnabled(), true, enabled -> {
                    MessagesController.getGlobalMainSettings().edit().putBoolean("view_animations", enabled).apply();
                    SharedConfig.setAnimationsEnabled(enabled);
                });
        transitionRow(content, R.string.WgtgChatTransitionStyle, WgtgConfig.chatTransition, WgtgConfig::setChatTransition);
        check(content, R.string.WgtgSmoothMessages, R.string.WgtgSmoothMessagesInfo,
                WgtgConfig.smoothMessages, true, WgtgConfig::setSmoothMessages);
        transitionRow(content, R.string.WgtgMessageTransitionStyle, WgtgConfig.messageTransition, WgtgConfig::setMessageTransition);
        content = section(root, R.string.WgtgMessagesSection);
        check(content, R.string.WgtgAutoBold, R.string.WgtgAutoBoldInfo,
                WgtgConfig.autoBold, false, WgtgConfig::setAutoBold);
        content = section(root, R.string.WgtgExtrasSection);
        TextSettingsCell plugins = row(content, R.string.WgtgPlugins, true);
        plugins.setOnClickListener(v -> open(new WgtgPluginsActivity()));
        TextSettingsCell playlist = row(content, R.string.WgtgPlaylist, false);
        playlist.setOnClickListener(v -> open(new WgtgPlaylistActivity()));
        info(root, R.string.WgtgPersonalizationInfo);
        fragmentView = scroll;
        for (LinearLayout card : cards) {
            for (int i = 0; i < card.getChildCount(); i++) {
                View child = card.getChildAt(i);
                child.setBackground(child instanceof HeaderCell ? null : Theme.getSelectorDrawable(false));
                if (child instanceof TextSettingsCell) {
                    ((TextSettingsCell) child).setBetterLayout(true);
                }
            }
        }
        updateCardColors();
        return fragmentView;
    }

    private LinearLayout section(LinearLayout root, int title) {
        LinearLayout card = new LinearLayout(root.getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(6));
        card.setClipToOutline(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = AndroidUtilities.dp(12);
        root.addView(card, params);
        cards.add(card);
        header(card, title);
        return card;
    }

    private void updateCardColors() {
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        int surface = Theme.getColor(Theme.key_windowBackgroundWhite);
        for (LinearLayout card : cards) {
            GradientDrawable background = new GradientDrawable();
            background.setColor(surface);
            background.setCornerRadius(AndroidUtilities.dp(18));
            background.setStroke(AndroidUtilities.dp(1), ColorUtils.blendARGB(surface, accent, 0.22f));
            card.setBackground(background);
        }
        if (brand != null) brand.setTextColor(accent);
        if (subtitle != null) subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = new ArrayList<>();
        descriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        descriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        ThemeDescription.ThemeDescriptionDelegate delegate = this::updateCardColors;
        for (int key : new int[]{Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhiteBlueText4, Theme.key_windowBackgroundWhiteGrayText2}) {
            descriptions.add(new ThemeDescription(null, 0, null, null, null, delegate, key));
        }
        for (LinearLayout card : cards) {
            for (int i = 0; i < card.getChildCount(); i++) {
                View cell = card.getChildAt(i);
                if (cell instanceof HeaderCell) {
                    descriptions.add(new ThemeDescription(((HeaderCell) cell).getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
                } else {
                    descriptions.add(new ThemeDescription(cell, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));
                    Class[] type = {cell.getClass()};
                    descriptions.add(new ThemeDescription(cell, 0, type, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
                    descriptions.add(new ThemeDescription(cell, 0, type, new String[]{"valueTextView"}, null, null, null,
                            cell instanceof TextCheckCell ? Theme.key_windowBackgroundWhiteGrayText2 : Theme.key_windowBackgroundWhiteValueText));
                    if (cell instanceof TextCheckCell) {
                        for (int key : new int[]{Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite}) {
                            descriptions.add(new ThemeDescription(cell, 0, type, new String[]{"checkBox"}, null, null, null, key));
                        }
                    }
                }
            }
        }
        if (fragmentView instanceof ScrollView) {
            LinearLayout root = (LinearLayout) ((ScrollView) fragmentView).getChildAt(0);
            for (int i = 0; i < root.getChildCount(); i++) {
                View child = root.getChildAt(i);
                if (child instanceof TextInfoPrivacyCell) {
                    descriptions.add(new ThemeDescription(((TextInfoPrivacyCell) child).getTextView(), ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
                }
            }
        }
        descriptions.add(new ThemeDescription(null, 0, null, Theme.dividerPaint, null, null, Theme.key_divider));
        return descriptions;
    }

    private void open(BaseFragment fragment) {
        fragment.setCurrentAccount(currentAccount);
        presentFragment(fragment);
    }

    private void transitionRow(LinearLayout content, int title, int selected, java.util.function.IntConsumer onChange) {
        CharSequence[] styles = {LocaleController.getString(R.string.WgtgTransitionDefault),
                LocaleController.getString(R.string.WgtgTransitionFade), LocaleController.getString(R.string.WgtgTransitionSlide),
                LocaleController.getString(R.string.WgtgTransitionScale)};
        TextSettingsCell cell = row(content, title, true);
        cell.setTextAndValue(LocaleController.getString(title), styles[selected].toString(), true);
        cell.setOnClickListener(v -> showDialog(new AlertDialog.Builder(content.getContext())
                .setTitle(LocaleController.getString(title)).setItems(styles, (dialog, which) -> {
                    onChange.accept(which);
                    cell.setTextAndValue(LocaleController.getString(title), styles[which].toString(), true);
                }).create()));
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
