from java import jclass
from android_utils import R, run_on_ui_thread
from client_utils import get_last_fragment


class BulletinHelper:
    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @staticmethod
    def _show(create, fragment):
        def show():
            factory = jclass("org.telegram.ui.Components.BulletinFactory")
            current = fragment if fragment is not None else get_last_fragment()
            factory = factory.of(current) if current is not None else getattr(factory, "global")()
            create(factory).show()
        run_on_ui_thread(show)

    @staticmethod
    def show_simple(text, icon_res_id, fragment=None):
        BulletinHelper._show(lambda f: f.createSimpleBulletin(int(icon_res_id), text), fragment)

    @staticmethod
    def show_info(message, fragment=None):
        BulletinHelper.show_simple(message, jclass("org.telegram.messenger.R$raw").info, fragment)

    @staticmethod
    def show_success(message, fragment=None):
        BulletinHelper.show_simple(message, jclass("org.telegram.messenger.R$raw").done, fragment)

    @staticmethod
    def show_error(message, fragment=None):
        BulletinHelper._show(lambda f: f.createErrorBulletin(message), fragment)

    @staticmethod
    def show_two_line(title, subtitle, icon_res_id, fragment=None):
        BulletinHelper._show(lambda f: f.createSimpleBulletin(int(icon_res_id), title, subtitle), fragment)

    @staticmethod
    def show_with_button(text, icon_res_id, button_text, on_click, fragment=None, duration=DURATION_PROLONG):
        BulletinHelper._show(lambda f: f.createSimpleBulletin(int(icon_res_id), text, button_text,
                             int(duration), R(on_click) if on_click else None), fragment)

    @staticmethod
    def show_copied_to_clipboard(message=None, fragment=None):
        if message is None:
            message = jclass("org.telegram.messenger.LocaleController").getString(
                jclass("org.telegram.messenger.R$string").TextCopied)
        BulletinHelper._show(lambda f: f.createCopyBulletin(message), fragment)
