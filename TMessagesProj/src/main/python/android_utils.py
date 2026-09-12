from java import jclass
from elyx._proxies import gen

AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
R = gen(jclass("java.lang.Runnable"), "run")
OnClickListener = gen(jclass("android.view.View$OnClickListener"), "onClick")
OnLongClickListener = gen(jclass("android.view.View$OnLongClickListener"), "onLongClick", True, False)


def run_on_ui_thread(callback, delay=0):
    AndroidUtilities.runOnUIThread(R(callback), int(delay))


def log(value):
    jclass("org.telegram.messenger.FileLog").d(str(value))


def copy_to_clipboard(text):
    def copy():
        context = jclass("org.telegram.messenger.ApplicationLoader").applicationContext
        clipboard = context.getSystemService("clipboard")
        clipboard.setPrimaryClip(jclass("android.content.ClipData").newPlainText("", str(text)))
        from ui.bulletin import BulletinHelper
        BulletinHelper.show_copied_to_clipboard()
    run_on_ui_thread(copy)
