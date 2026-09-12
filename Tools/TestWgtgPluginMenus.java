package org.telegram.messenger;

import com.chaquo.python.Python;
import java.util.HashMap;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.ItemOptions;

/** Host behavior tests, using the real bridge and MVEL with Android/Chaquopy doubles. */
public class TestWgtgPluginMenus {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        BaseFragment fragment = new BaseFragment();
        HashMap<String, Object> context = WgtgPluginMenus.context(fragment);
        check(context.get("fragment") == fragment, "live fragment");
        check(context.get("context") == fragment.activity, "activity context");
        check(context.get("account").equals(2), "fragment account");
        check(context.get("dialog_id").equals(0L) && context.get("message") == null, "drawer defaults");

        Python.json = "[{\"token\":\"drawer\",\"text\":\"Drawer action\",\"icon\":\"msg_info\",\"subtext\":\"Details\"}]";
        ItemOptions options = new ItemOptions();
        WgtgPluginMenus.append(options, WgtgPluginMenus.DRAWER_MENU, fragment, context);
        check(Python.type == 1 && options.layout.children.size() == 1, "drawer query and native row");
        ActionBarMenuSubItem row = (ActionBarMenuSubItem) options.layout.children.get(0);
        check(row.text.equals("Drawer action") && row.icon == 42 && row.subtext.equals("Details"), "presentation");
        row.performClick();
        check(options.dismissed, "native dismissal");
        check(Python.clickArgs[0].equals("drawer") && Python.clickArgs[1] == context, "click token and live map");
        Python.clickArgs = null;
        fragment.account = 3;
        row.performClick();
        check(Python.clickArgs == null, "account switch rejects old click");
        fragment.account = 2;
        fragment.isFinished = true;
        row.performClick();
        check(Python.clickArgs == null, "destroyed fragment rejects click");
        fragment.isFinished = false;

        WgtgPluginMenus.invalidate("[\"drawer\"]");
        check(options.layout.children.size() == 1, "cleanup is posted to UI thread");
        for (Runnable task : AndroidUtilities.pending) task.run();
        AndroidUtilities.pending.clear();
        check(options.layout.children.isEmpty() && row.listener == null, "unload removes row and listener");
        row.performClick();
        check(Python.clickArgs == null, "detached row cannot dispatch");

        Python.json = "[{\"token\":\"plain\",\"text\":\"Plain\",\"icon\":null,\"subtext\":null}]";
        ItemOptions plainOptions = new ItemOptions();
        WgtgPluginMenus.append(plainOptions, WgtgPluginMenus.DRAWER_MENU, fragment, context);
        ActionBarMenuSubItem plainRow = (ActionBarMenuSubItem) plainOptions.layout.children.get(0);
        check(plainRow.subtext == null && plainRow.icon == 0, "JSON null does not render a literal null subtitle");

        ActionBarMenuItem menu = new ActionBarMenuItem();
        android.view.View nativeRow = new android.view.View();
        menu.getPopupLayout().addView(nativeRow);
        WgtgPluginMenus.populate(menu, WgtgPluginMenus.CHAT_ACTION_MENU, fragment, context);
        WgtgPluginMenus.populate(menu, WgtgPluginMenus.CHAT_ACTION_MENU, fragment, context);
        check(menu.getPopupLayout().getItemsCount() == 2, "reopening does not duplicate plugin rows");
        check(menu.getPopupLayout().getItemAt(0) == nativeRow, "native actions survive refresh");
        WgtgPluginsController.ready = false;
        WgtgPluginMenus.populate(menu, WgtgPluginMenus.CHAT_ACTION_MENU, fragment, context);
        check(menu.getPopupLayout().getItemsCount() == 1, "not-ready engine adds no rows");

        MessageObject message = new MessageObject();
        TLRPC.User user = new TLRPC.User();
        user.id = 5000000000L;
        context.put("message", message);
        context.put("user", user);
        context.put("userId", user.id);
        for (int i = 0; i < 100; i++) {
            check(WgtgPluginMenus.evaluateCondition("account == 2 && message.getId() == 123", context), "MVEL Java method");
            check(WgtgPluginMenus.evaluateCondition("user != null && !user.bot && userId == 5000000000L", context), "MVEL Java fields/long");
            check(!WgtgPluginMenus.evaluateCondition("chat != null && chat.id > 0", context), "MVEL null short circuit");
        }
        check(WgtgPluginMenus.evaluateCondition("account = 9; account == 9", context), "MVEL local assignment");
        check(context.get("account").equals(2), "MVEL cannot replace snapshot entries");
        try {
            WgtgPluginMenus.evaluateCondition("message.(", context);
            throw new AssertionError("invalid MVEL accepted");
        } catch (RuntimeException expected) {
            // Python catches expression errors and hides the item.
        }
    }
}
