package org.telegram.ui;

import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.WeakHashMap;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.WgtgPasscode;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;

/** Synchronous invalidation, including fragments underneath the visible screen and on tablets. */
public final class WgtgPrivacyUi {
    private static final WeakHashMap<BaseFragment, Boolean> fragments = new WeakHashMap<>();
    private static boolean lastRestricted = WgtgPasscode.isRestricted();
    static { WgtgPasscode.addListener(WgtgPrivacyUi::refresh); }

    public static void track(BaseFragment fragment) { fragments.put(fragment, true); }
    public static void forget(BaseFragment fragment) { fragments.remove(fragment); }

    private static void refresh() {
        boolean modeChanged = lastRestricted != WgtgPasscode.isRestricted();
        HashSet<INavigationLayout> layouts = new HashSet<>();
        for (BaseFragment fragment : new ArrayList<>(fragments.keySet())) {
            if (fragment == null || fragment.isFinished) continue;
            INavigationLayout layout = fragment.getParentLayout();
            boolean root = fragment instanceof DialogsActivity && layout != null
                    && !layout.getFragmentStack().isEmpty() && layout.getFragmentStack().get(0) == fragment;
            boolean close = modeChanged && !root && !(fragment instanceof PasscodeActivity && !WgtgPasscode.isRestricted());
            close |= !WgtgPasscode.canAccessArchive() && (fragment instanceof WgtgDeletedMessagesActivity || fragment instanceof WgtgAlternatePasscodeActivity);
            if (modeChanged || close) { fragment.dismissCurrentDialog(); fragment.clearSheets(); }
            if (close) {
                if (fragment.getFragmentView() != null) fragment.getFragmentView().setVisibility(View.INVISIBLE);
                if (layout != null) layouts.add(layout);
                fragment.removeSelfFromStack(true);
                fragment.clearViews();
            } else if (fragment instanceof WgtgSettingsActivity) {
                ((WgtgSettingsActivity) fragment).updatePrivacyVisibility();
            }
            if (modeChanged && layout != null) layouts.add(layout);
        }
        if (modeChanged) {
            if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) PhotoViewer.getInstance().closePhoto(false, true);
            MediaController.getInstance().cleanupPlayer(true, true);
            for (INavigationLayout layout : layouts) layout.rebuildAllFragmentViews(true, true);
        }
        lastRestricted = WgtgPasscode.isRestricted();
    }
}
