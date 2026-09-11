import ast, importlib.util, inspect, json, os, re, shutil, sys, traceback, zipfile, tempfile
from base_plugin import BasePlugin, HookStrategy

root = None
loaded = {}
hooks = []
send_hooks = []

def _state_path(): return os.path.join(root, "state.json")
def _read_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f: return json.load(f)
    except Exception: return default
def _write_json(path, value):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as f: json.dump(value, f, ensure_ascii=False, indent=2)
    os.replace(temp, path)
def _state(): return _read_json(_state_path(), {"enabled": {}, "settings": {}})
def _valid_id(value): return isinstance(value, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{1,31}", value) is not None

def _source_metadata(path):
    with open(path, "r", encoding="utf-8") as f: tree = ast.parse(f.read(), path)
    data = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            key = node.targets[0].id
            if key.startswith("__") and key.endswith("__"):
                try: data[key.strip("_")] = ast.literal_eval(node.value)
                except Exception: pass
    return data

def _simple_yaml(path):
    import yaml
    with open(path, "r", encoding="utf-8") as f:
        data = yaml.safe_load(f)
    if not isinstance(data, dict): raise ValueError("Expected a metadata mapping")
    return {str(key).strip("_"): value for key, value in data.items()}

def _project_info(folder):
    ref = {}
    for name in ("refmap.json", "refmap.yml", "refmap.yaml"):
        path = os.path.join(folder, name)
        if os.path.isfile(path): ref = _read_json(path, {}) if name.endswith("json") else _simple_yaml(path); break
    main = os.path.realpath(os.path.join(folder, ref.get("main", "main.py")))
    if not main.startswith(os.path.realpath(folder) + os.sep) or not os.path.isfile(main): raise ValueError("Plugin entry point not found")
    meta_path = ref.get("metainfo")
    if meta_path:
        meta_path = os.path.realpath(os.path.join(folder, meta_path))
        if not meta_path.startswith(os.path.realpath(folder) + os.sep): raise ValueError("Invalid metadata path")
        meta = _read_json(meta_path, {}) if meta_path.endswith("json") else _simple_yaml(meta_path)
    else:
        meta = _source_metadata(main)
        for name in ("metainfo.json", "metainfo.yml", "metainfo.yaml"):
            path = os.path.join(folder, name)
            if os.path.isfile(path): meta = _read_json(path, {}) if name.endswith("json") else _simple_yaml(path); break
    return main, meta

def _descriptors():
    result = []
    for name in os.listdir(root):
        path = os.path.join(root, name)
        try:
            if name.startswith("."): continue
            main, meta = (path, _source_metadata(path)) if os.path.isfile(path) and path.endswith(".py") else _project_info(path) if os.path.isdir(path) else (None, None)
            if not main: continue
            plugin_id = meta.get("id")
            if not _valid_id(plugin_id): continue
            result.append((plugin_id, main, meta))
        except Exception: pass
    return result

def initialize(path):
    global root
    root = path; os.makedirs(root, exist_ok=True)
    state = _state()
    interrupted = state.pop("loading", None)
    if interrupted:
        state["enabled"][interrupted] = False
        _write_json(_state_path(), state)
    for plugin_id, main, meta in _descriptors():
        if state["enabled"].get(plugin_id, False): _load(plugin_id, main, meta)

def _load(plugin_id, main, meta):
    try:
        state = _state(); state["loading"] = plugin_id; _write_json(_state_path(), state)
        if meta.get("requirements") or meta.get("requires"):
            raise NotImplementedError("External plugin dependencies are not supported")
        module_name = "wgtg_plugin_" + plugin_id
        spec = importlib.util.spec_from_file_location(module_name, main)
        module = importlib.util.module_from_spec(spec); sys.modules[module_name] = module
        plugin_dir = os.path.dirname(main); sys.path.insert(0, plugin_dir)
        try: spec.loader.exec_module(module)
        finally:
            if sys.path[0] == plugin_dir: sys.path.pop(0)
        with open(main, encoding="utf-8") as source:
            declared = {node.name for node in ast.parse(source.read()).body if isinstance(node, ast.ClassDef)}
        classes = [value for name, value in vars(module).items() if name in declared and inspect.isclass(value) and value is not BasePlugin and issubclass(value, BasePlugin)]
        if not classes: raise ValueError("BasePlugin subclass not found")
        instance = classes[0].__new__(classes[0]); instance._wgtg_bind(plugin_id, sys.modules[__name__])
        loaded[plugin_id] = {"instance": instance, "meta": meta, "error": ""}
        instance.__init__()
        if hasattr(instance, "on_plugin_load"): instance.on_plugin_load()
    except Exception:
        error = traceback.format_exc()[-4000:]
        _unload(plugin_id)
        state = _state(); state["enabled"][plugin_id] = False; _write_json(_state_path(), state)
        loaded[plugin_id] = {"instance": None, "meta": meta, "error": error}
    finally:
        state = _state(); state.pop("loading", None); _write_json(_state_path(), state)

def _unload(plugin_id):
    item = loaded.pop(plugin_id, None)
    if item and item["instance"] and hasattr(item["instance"], "on_plugin_unload"):
        try: item["instance"].on_plugin_unload()
        except Exception: traceback.print_exc()
    hooks[:] = [x for x in hooks if x[0] != plugin_id]; send_hooks[:] = [x for x in send_hooks if x[0] != plugin_id]
    sys.modules.pop("wgtg_plugin_" + plugin_id, None)

def list_plugins():
    state = _state(); result = []
    for plugin_id, main, meta in _descriptors():
        item = loaded.get(plugin_id, {})
        result.append({"id": plugin_id, "name": meta.get("name", plugin_id), "version": str(meta.get("version", "1.0")), "author": meta.get("author", ""), "description": meta.get("description", ""), "enabled": state["enabled"].get(plugin_id, False), "error": item.get("error", "")})
    return json.dumps(result, ensure_ascii=False)

def install(path):
    temp = tempfile.mkdtemp(prefix=".install-", dir=root)
    try:
        return _install(path, temp)
    finally:
        shutil.rmtree(temp, ignore_errors=True)

def _install(path, temp):
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 2048 or sum(x.file_size for x in archive.infolist()) > 64 * 1024 * 1024: raise ValueError("Plugin archive is too large")
            base = os.path.realpath(temp) + os.sep
            for item in archive.infolist():
                target = os.path.realpath(os.path.join(temp, item.filename))
                if not target.startswith(base): raise ValueError("Unsafe archive path")
                if (item.external_attr >> 16) & 0o170000 == 0o120000: raise ValueError("Symlinks are not supported")
                if item.filename.endswith((".so", ".whl", ".pyc")): raise ValueError("Binary plugin modules are not supported")
            archive.extractall(temp)
        main, meta = _project_info(temp); plugin_id = meta.get("id")
        if not _valid_id(plugin_id): shutil.rmtree(temp); raise ValueError("Invalid plugin id")
        destination = os.path.join(root, plugin_id)
    else:
        meta = _source_metadata(path); plugin_id = meta.get("id")
        if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
        destination = os.path.join(root, plugin_id)
        shutil.copyfile(path, os.path.join(temp, "main.py"))
    _unload(plugin_id)
    backup = destination + ".backup"
    shutil.rmtree(backup, ignore_errors=True)
    if os.path.isdir(destination): os.replace(destination, backup)
    try: os.replace(temp, destination)
    except Exception:
        if os.path.isdir(backup): os.replace(backup, destination)
        raise
    shutil.rmtree(backup, ignore_errors=True)
    legacy = os.path.join(root, plugin_id + ".py")
    if os.path.isfile(legacy): os.remove(legacy)
    state = _state(); state["enabled"][plugin_id] = False; _write_json(_state_path(), state)
    return plugin_id

def set_enabled(plugin_id, enabled):
    if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
    state = _state(); state["enabled"][plugin_id] = bool(enabled); _write_json(_state_path(), state); _unload(plugin_id)
    if enabled:
        for pid, main, meta in _descriptors():
            if pid == plugin_id: _load(pid, main, meta); break

def uninstall(plugin_id):
    if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
    _unload(plugin_id)
    for path in (os.path.join(root, plugin_id), os.path.join(root, plugin_id + ".py")):
        if os.path.isdir(path): shutil.rmtree(path)
        elif os.path.isfile(path): os.remove(path)
    state = _state(); state["enabled"].pop(plugin_id, None); state["settings"].pop(plugin_id, None); _write_json(_state_path(), state)

def add_hook(plugin_id, name, match_substring=False, priority=0): hooks.append((plugin_id, name, bool(match_substring), int(priority))); hooks.sort(key=lambda x: -x[3])
def add_send_hook(plugin_id, priority=0): send_hooks.append((plugin_id, int(priority))); send_hooks.sort(key=lambda x: -x[1])
def _strategy(result): return int(getattr(result, "strategy", HookStrategy.DEFAULT)) if result is not None else 0
def before_request(name, account, request):
    for plugin_id, expected, substring, priority in list(hooks):
        if (substring and expected in name) or (not substring and expected == name):
            try:
                result = loaded[plugin_id]["instance"].pre_request_hook(name, account, request)
                strategy = _strategy(result)
                if strategy == HookStrategy.CANCEL: return None
                if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL) and result.request is not None: request = result.request
                if strategy == HookStrategy.MODIFY_FINAL: break
            except Exception: traceback.print_exc()
    return request
def before_send_message(account, params):
    for plugin_id, priority in list(send_hooks):
        try:
            result = loaded[plugin_id]["instance"].on_send_message_hook(account, params)
            strategy = _strategy(result)
            if strategy == HookStrategy.CANCEL: return None
            if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL) and result.params is not None: params = result.params
            if strategy == HookStrategy.MODIFY_FINAL: break
        except Exception: traceback.print_exc()
    return params

def settings_rows(plugin_id):
    from dataclasses import asdict
    instance = loaded.get(plugin_id, {}).get("instance")
    rows = instance.create_settings() if instance and hasattr(instance, "create_settings") else []
    result = []
    for row in rows:
        kind = type(row).__name__
        if kind not in ("Header", "Input", "Switch", "Text"):
            raise NotImplementedError("Unsupported settings row: " + kind)
        data = asdict(row); data["type"] = kind
        if "key" in data: data["value"] = get_setting(plugin_id, data["key"], data.get("default"))
        result.append(data)
    return json.dumps(result)

def set_setting_json(plugin_id, key, value): set_setting(plugin_id, key, json.loads(value))
def get_settings(plugin_id): return _state()["settings"].get(plugin_id, {})
def get_setting(plugin_id, key, default=None): return get_settings(plugin_id).get(key, default)
def set_setting(plugin_id, key, value):
    state = _state(); state["settings"].setdefault(plugin_id, {})[key] = value; _write_json(_state_path(), state)
def replace_settings(plugin_id, values):
    state = _state(); state["settings"][plugin_id] = dict(values); _write_json(_state_path(), state)

# Chaquopy can call the engine from UI, storage and networking threads.
from functools import wraps
from threading import RLock
_lock = RLock()
def _serialized(function):
    @wraps(function)
    def call(*args, **kwargs):
        with _lock: return function(*args, **kwargs)
    return call
for _name in ("initialize", "install", "set_enabled", "uninstall", "list_plugins",
              "before_request", "before_send_message", "settings_rows", "set_setting_json",
              "get_settings", "get_setting", "set_setting", "replace_settings", "add_hook", "add_send_hook"):
    globals()[_name] = _serialized(globals()[_name])
