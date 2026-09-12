from java import jclass, dynamic_proxy
from android_utils import R, run_on_ui_thread
from elyx._proxies import gen
from wgtg_plugin_engine import _account, account_scope

STAGE_QUEUE = "stageQueue"
GLOBAL_QUEUE = "globalQueue"
CACHE_CLEAR_QUEUE = "cacheClearQueue"
SEARCH_QUEUE = "searchQueue"
PHONE_BOOK_QUEUE = "phoneBookQueue"
THEME_QUEUE = "themeQueue"
EXTERNAL_NETWORK_QUEUE = "externalNetworkQueue"
PLUGINS_QUEUE = "pluginsQueue"
_plugins_queue = jclass("org.telegram.messenger.DispatchQueue")(PLUGINS_QUEUE)


def _selected(account=None):
    if account is None:
        account = _account.get()
    return int(jclass("org.telegram.messenger.UserConfig").selectedAccount if account is None else account)


def _getter(class_name):
    def get(account=None):
        return jclass(class_name).getInstance(_selected(account))
    return get


for _name, _class in {
    "account_instance": "AccountInstance", "messages_controller": "MessagesController",
    "contacts_controller": "ContactsController", "media_data_controller": "MediaDataController",
    "location_controller": "LocationController", "notifications_controller": "NotificationsController",
    "messages_storage": "MessagesStorage", "send_messages_helper": "SendMessagesHelper",
    "file_loader": "FileLoader", "secret_chat_helper": "SecretChatHelper",
    "download_controller": "DownloadController", "notification_center": "NotificationCenter",
    "user_config": "UserConfig",
}.items():
    globals()["get_" + _name] = _getter("org.telegram.messenger." + _class)
get_connections_manager = _getter("org.telegram.tgnet.ConnectionsManager")
messages_controller = get_messages_controller
send_messages_helper = get_send_messages_helper


def get_media_controller():
    return jclass("org.telegram.messenger.MediaController").getInstance()


def get_notifications_settings(account=None):
    return jclass("org.telegram.messenger.MessagesController").getNotificationsSettings(_selected(account))


def get_last_fragment():
    return jclass("org.telegram.ui.LaunchActivity").getSafeLastFragment()


def get_queue_by_name(name):
    if name == PLUGINS_QUEUE:
        return _plugins_queue
    if name not in (STAGE_QUEUE, GLOBAL_QUEUE, CACHE_CLEAR_QUEUE, SEARCH_QUEUE,
                    PHONE_BOOK_QUEUE, THEME_QUEUE, EXTERNAL_NETWORK_QUEUE):
        raise ValueError(f"Unknown queue: {name}")
    return getattr(jclass("org.telegram.messenger.Utilities"), name)


def run_on_queue(callback, queue=PLUGINS_QUEUE, delay=0):
    account = _selected()
    def run():
        with account_scope(account):
            callback()
    get_queue_by_name(queue).postRunnable(R(run), int(delay))


RequestCallback = gen(jclass("org.telegram.tgnet.RequestDelegate"), "run")
NotificationCenterDelegate = dynamic_proxy(jclass("org.telegram.messenger.NotificationCenter$NotificationCenterDelegate"))


def send_request(request, fn, account=None):
    account = _selected(account)
    def callback(response, error):
        with account_scope(account):
            fn(response, error)
    return get_connections_manager(account).sendRequest(request, RequestCallback(callback))


def send_message(params, parse_mode=None, account=None):
    if parse_mode is not None:
        raise NotImplementedError("Message parse_mode is not supported by this host")
    if not isinstance(params, dict) or "peer" not in params:
        raise ValueError("send_message requires a field dictionary containing peer")
    if isinstance(params.get("replyToMsg"), int):
        raise NotImplementedError("replyToMsg requires a MessageObject, not an integer ID")
    account = _selected(account)
    java_params = jclass("org.telegram.messenger.SendMessagesHelper$SendMessageParams").of(
        params.get("message", ""), int(params["peer"]))
    for key, value in params.items():
        if not hasattr(java_params, key):
            raise ValueError(f"Unknown SendMessageParams field: {key}")
        setattr(java_params, key, value)
    run_on_ui_thread(lambda: get_send_messages_helper(account).sendMessage(java_params))


def send_text(peer_id, text, account=None, parse_mode=None, **params):
    send_message(dict(params, peer=peer_id, message=text), parse_mode, account)
