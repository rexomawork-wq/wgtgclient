from java import jclass, dynamic_proxy
AndroidUtilities = jclass("org.telegram.messenger.AndroidUtilities")
class _Runnable(dynamic_proxy(jclass("java.lang.Runnable"))):
    def __init__(self, callback): super().__init__(); self.callback = callback
    def run(self): self.callback()
def run_on_ui_thread(callback, delay=0): AndroidUtilities.runOnUIThread(_Runnable(callback), int(delay))
def log(value): print(value)
