from java import jclass
from elyx._proxies import gen


class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(self, context, progress_style=0, resources_provider=None):
        self._builder = jclass("org.telegram.ui.ActionBar.AlertDialog$Builder")(
            context, int(progress_style), resources_provider)
        self._dialog = None
        self._listeners = []

    def _listener(self, callback, method="onClick", interface="OnClickListener"):
        if callback is None:
            return None
        listener = gen(jclass("android.content.DialogInterface$" + interface), method)(
            lambda dialog, *args: callback(self, *args))
        self._listeners.append(listener)
        return listener

    def set_title(self, title):
        self._builder.setTitle(title)
        return self

    def set_message(self, message):
        self._builder.setMessage(message)
        return self

    def set_view(self, view, height=-2):
        self._builder.setView(view, int(height))
        return self

    def set_items(self, items, listener=None, icons=None):
        listener = self._listener(listener)
        if icons is None:
            self._builder.setItems(items, listener)
        else:
            self._builder.setItems(items, icons, listener)
        return self

    def set_positive_button(self, text, listener=None):
        self._builder.setPositiveButton(text, self._listener(listener))
        return self

    def set_negative_button(self, text, listener=None):
        self._builder.setNegativeButton(text, self._listener(listener))
        return self

    def set_neutral_button(self, text, listener=None):
        self._builder.setNeutralButton(text, self._listener(listener))
        return self

    def create(self):
        if self._dialog is None:
            self._dialog = self._builder.create()
        return self

    def show(self):
        self.create()._dialog.show()
        return self

    def dismiss(self):
        if self._dialog is not None:
            self._dialog.dismiss()

    def get_dialog(self):
        return self._dialog

    def get_button(self, button_type):
        return self._dialog.getButton(button_type) if self._dialog is not None else None

    def set_cancelable(self, cancelable):
        self.create()._dialog.setCancelable(bool(cancelable))
        return self

    def set_canceled_on_touch_outside(self, cancel):
        self.create()._dialog.setCanceledOnTouchOutside(bool(cancel))
        return self

    def set_progress(self, progress):
        self.create()._dialog.setProgress(int(progress))
        return self

    def set_on_dismiss_listener(self, listener=None):
        self.create()._dialog.setOnDismissListener(self._listener(listener, "onDismiss", "OnDismissListener"))
        return self

    def set_on_cancel_listener(self, listener=None):
        self.create()._dialog.setOnCancelListener(self._listener(listener, "onCancel", "OnCancelListener"))
        return self
