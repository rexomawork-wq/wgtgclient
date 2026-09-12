import ast, importlib.util, inspect, json, os, re, shutil, sys, traceback, zipfile, tempfile
from contextlib import contextmanager
from contextvars import ContextVar
from base_plugin import AppEvent, BasePlugin, HookStrategy
import elyx
from elyx._environment import read_refmap, load_resources

root = None
loaded = {}
hooks = []
send_hooks = []
app_event = None
dependency_paths = {}
source_paths = {}
_settings_token = 0
_account = ContextVar("plugin_account", default=None)

@contextmanager
def account_scope(account):
    token = _account.set(int(account))
    try:
        yield
    finally:
        _account.reset(token)

def _required_plugins():
    return {pid: {"version": loaded.get(pid, {}).get("meta", meta).get("version", "1.0"),
                  "enabled": bool(loaded.get(pid, {}).get("instance"))}
            for pid, _, meta in _descriptors()}

def _validate_requires(meta):
    from plugin_dependencies import validate_required_plugins
    validate_required_plugins(meta.get("requires"), _required_plugins())

def _wheel_paths(folder):
    ref = read_refmap(folder)
    value = ref.get("wheels")
    if not value: return []
    if not isinstance(value, str): raise ValueError("refmap wheels must be a directory path")
    directory = os.path.realpath(os.path.join(folder, value))
    project = os.path.realpath(folder)
    if (directory != project and not directory.startswith(project + os.sep)) or not os.path.isdir(directory):
        raise ValueError("Wheel directory must exist inside the plugin")
    return sorted(os.path.join(directory, name) for name in os.listdir(directory) if name.endswith(".whl"))

def _plugin_folder(plugin_id, main):
    installed = os.path.join(root, plugin_id)
    return installed if os.path.isdir(installed) else os.path.dirname(main)

def _dependency_path(record):
    if not record: return None
    relative = record["path"]
    path = os.path.realpath(os.path.join(root, relative))
    base = os.path.realpath(os.path.join(root, ".dependencies")) + os.sep
    if not path.startswith(base) or not os.path.isdir(path):
        raise ValueError("Installed dependency generation is missing; reinstall the plugin")
    return path

def _dependency_conflicts(plugin_id, path):
    # sys.modules and sys.path are process-global: separate directories are not
    # isolated environments. Never silently shadow host or another plugin's code.
    own = dependency_paths.get(plugin_id)
    for name in os.listdir(path):
        if name.endswith((".dist-info", ".egg-info")) or name == "__pycache__": continue
        module = name[:-3] if name.endswith(".py") else name
        if not module.isidentifier(): continue
        for owner, active in list(dependency_paths.items()) + list(source_paths.items()):
            if owner != plugin_id and (os.path.exists(os.path.join(active, module)) or os.path.exists(os.path.join(active, module + ".py"))):
                raise ValueError(f"Dependency {module} conflicts with active plugin {owner}; disable it first")
        spec = importlib.util.find_spec(module)
        if spec is not None:
            locations = list(spec.submodule_search_locations or ()) + ([spec.origin] if spec.origin else [])
            if own and locations and all(os.path.realpath(p).startswith(own + os.sep) for p in locations): continue
            raise ValueError(f"Dependency {module} conflicts with an existing Python/host library; shared-interpreter overrides are unsupported")

def _prepare_dependencies(plugin_id, folder, meta):
    from plugin_dependencies import prepare_requirements, install_requirements
    wheels = _wheel_paths(folder)
    if not meta.get("requirements") and not wheels: return None
    plan = prepare_requirements(meta.get("requirements"), wheels=wheels)
    directory = os.path.join(root, ".dependencies")
    os.makedirs(directory, exist_ok=True)
    generation = tempfile.mkdtemp(prefix="generation-", dir=directory)
    os.rmdir(generation)  # Installer requires a new target and publishes atomically.
    try:
        install_requirements(plan, generation)
        with _lock: _dependency_conflicts(plugin_id, generation)
        return {"path": os.path.relpath(generation, root)}
    except BaseException:
        shutil.rmtree(generation, ignore_errors=True)
        raise

def _remove_dependencies(record):
    if record:
        try: path = _dependency_path(record)
        except (KeyError, ValueError): return
        shutil.rmtree(path, ignore_errors=True)

def _activate_dependencies(plugin_id):
    path = _dependency_path(_state().get("dependencies", {}).get(plugin_id))
    if path:
        _dependency_conflicts(plugin_id, path)
        dependency_paths[plugin_id] = path
        sys.path.insert(0, path)
        importlib.invalidate_caches()

def _check_dependents(plugin_id):
    from plugin_dependencies import validate_required_plugins
    installed = _required_plugins()
    installed.pop(plugin_id, None)
    for pid, item in loaded.items():
        if pid != plugin_id and item.get("instance"):
            try: validate_required_plugins(item["meta"].get("requires"), installed)
            except ValueError as error:
                raise ValueError(f"Disable dependent plugin {pid} first: {error}") from error

def _state_path(): return os.path.join(root, "state.json")
def _read_json(path, default):
    try:
        with open(path, "r", encoding="utf-8") as f: return json.load(f)
    except Exception: return default
def _write_json(path, value):
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as f: json.dump(value, f, ensure_ascii=False, indent=2)
    os.replace(temp, path)
def _state():
    if not os.path.exists(_state_path()): return {"enabled": {}, "settings": {}}
    state = _read_json(_state_path(), None)
    if not isinstance(state, dict) or any(not isinstance(state.get(key, {}), dict) for key in ("enabled", "settings", "dependencies", "installations")):
        raise ValueError("Plugin state is corrupt; refusing to overwrite installed plugin records")
    state.setdefault("enabled", {})
    state.setdefault("settings", {})
    return state
def _valid_id(value): return isinstance(value, str) and re.fullmatch(r"[A-Za-z][A-Za-z0-9_-]{1,31}", value) is not None

def _source_metadata(path, public=False):
    with open(path, "r", encoding="utf-8") as f: tree = ast.parse(f.read(), path)
    data = {}
    for node in tree.body:
        if isinstance(node, ast.Assign) and len(node.targets) == 1 and isinstance(node.targets[0], ast.Name):
            key = node.targets[0].id
            if (key.startswith("__") and key.endswith("__")) or (public and not key.startswith("_")):
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
    ref = read_refmap(folder)
    main = os.path.realpath(os.path.join(folder, ref.get("main", "main.py")))
    if not main.startswith(os.path.realpath(folder) + os.sep) or not os.path.isfile(main): raise ValueError("Plugin entry point not found")
    meta_path = ref.get("metainfo")
    if meta_path:
        meta_path = os.path.realpath(os.path.join(folder, meta_path))
        if not meta_path.startswith(os.path.realpath(folder) + os.sep): raise ValueError("Invalid metadata path")
        meta = _source_metadata(meta_path, public=True) if meta_path.endswith(".py") else _read_json(meta_path, {}) if meta_path.endswith("json") else _simple_yaml(meta_path)
    else:
        meta = _source_metadata(main)
        for name in ("metainfo.yaml", "metainfo.yml", "metainfo.json"):
            path = os.path.join(folder, name)
            if os.path.isfile(path): meta = _read_json(path, {}) if name.endswith("json") else _simple_yaml(path); break
    return main, {str(key).strip("_"): value for key, value in meta.items()}

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
    global root, app_event
    for plugin_id in list(loaded): _unload(plugin_id)
    root = path; os.makedirs(root, exist_ok=True)
    app_event = None
    state = _state()
    # A directory rename and state.json cannot be committed in one filesystem
    # operation. The marker identifies whether an interrupted update committed.
    for name in os.listdir(root):
        if name.startswith(".backup-"):
            plugin_id = name[len(".backup-"):]
            if not _valid_id(plugin_id): continue
            destination = os.path.join(root, plugin_id)
            backup = os.path.join(root, name)
            marker = _read_json(os.path.join(destination, ".wgtg-install.json"), None)
            committed = marker is not None and marker == state.get("installations", {}).get(plugin_id)
            if not committed:
                shutil.rmtree(destination, ignore_errors=True)
                os.replace(backup, destination)
            else:
                shutil.rmtree(backup, ignore_errors=True)
    for name in os.listdir(root):
        if not _valid_id(name): continue
        destination = os.path.join(root, name)
        marker = _read_json(os.path.join(destination, ".wgtg-install.json"), None)
        if marker is not None and marker != state.get("installations", {}).get(name):
            shutil.rmtree(destination, ignore_errors=True)
        elif marker is not None:
            legacy = os.path.join(root, name + ".py")
            if os.path.isfile(legacy): os.remove(legacy)
    interrupted = state.pop("loading", None)
    if interrupted:
        state["enabled"][interrupted] = False
        _write_json(_state_path(), state)
    generations = os.path.join(root, ".dependencies")
    referenced = {record.get("path") for record in state.get("dependencies", {}).values()}
    if os.path.isdir(generations):
        for name in os.listdir(generations):
            relative = os.path.join(".dependencies", name)
            if relative not in referenced: shutil.rmtree(os.path.join(root, relative), ignore_errors=True)
    pending = [item for item in _descriptors() if state["enabled"].get(item[0], False)]
    while pending:
        ready = []
        for item in pending:
            try: _validate_requires(item[2])
            except ValueError: continue
            ready.append(item)
        if not ready: ready = list(pending)  # Surface missing/cyclic dependency errors.
        for plugin_id, main, meta in ready:
            pending.remove((plugin_id, main, meta))
            _load(plugin_id, main, meta)
    on_app_event(AppEvent.START)

def _load(plugin_id, main, meta):
    try:
        state = _state(); state["loading"] = plugin_id; _write_json(_state_path(), state)
        _validate_requires(meta)
        if (meta.get("requirements") or _wheel_paths(_plugin_folder(plugin_id, main))) and plugin_id not in state.get("dependencies", {}):
            raise ValueError("Dependencies have not been prepared; enable or reinstall the plugin")
        _activate_dependencies(plugin_id)
        module_name = "wgtg_plugin_" + plugin_id
        environment = {
            "plugin_id": plugin_id, "package": module_name,
            "settings_backend": sys.modules[__name__],
            "settings": elyx.SettingsController(plugin_id), "metainfo": dict(meta),
        }
        loaded[plugin_id] = {"instance": None, "meta": environment["metainfo"], "error": "", "environment": environment}
        elyx.register_environment(module_name, environment)
        spec = importlib.util.spec_from_file_location(module_name, main, submodule_search_locations=[os.path.dirname(main)])
        module = importlib.util.module_from_spec(spec); sys.modules[module_name] = module
        plugin_dir = os.path.realpath(os.path.dirname(main))
        source_paths[plugin_id] = plugin_dir
        sys.path.insert(0, plugin_dir)
        importlib.invalidate_caches()
        with elyx.environment_scope(environment):
            load_resources(_plugin_folder(plugin_id, main), environment)
            spec.loader.exec_module(module)
        with open(main, encoding="utf-8") as source:
            declared = {node.name for node in ast.parse(source.read()).body if isinstance(node, ast.ClassDef)}
        classes = [value for name, value in vars(module).items() if name in declared and inspect.isclass(value) and value is not BasePlugin and issubclass(value, BasePlugin)]
        if not classes: raise ValueError("BasePlugin subclass not found")
        instance = classes[0].__new__(classes[0]); instance._wgtg_bind(plugin_id, sys.modules[__name__])
        loaded[plugin_id]["instance"] = instance
        with elyx.environment_scope(environment):
            instance.__init__()
            if hasattr(instance, "on_plugin_load"): instance.on_plugin_load()
    except (Exception, SystemExit):
        error = traceback.format_exc()[-4000:]
        _unload(plugin_id)
        state = _state(); state["enabled"][plugin_id] = False; _write_json(_state_path(), state)
        loaded[plugin_id] = {"instance": None, "meta": meta, "error": error}
    finally:
        state = _state(); state.pop("loading", None); _write_json(_state_path(), state)

def _unload(plugin_id):
    item = loaded.pop(plugin_id, None)
    if item and item["instance"] and hasattr(item["instance"], "on_plugin_unload"):
        try:
            with elyx.environment_scope(item.get("environment")):
                item["instance"].on_plugin_unload()
        except (Exception, SystemExit): traceback.print_exc()
    if item and item["instance"]:
        item["instance"]._wgtg_unloaded = True
        try:
            import hook_utils
            hook_utils.unhook_all(item["instance"])
        except (Exception, SystemExit): traceback.print_exc()
    menus = sys.modules.get("plugin_menus")
    if menus is not None:
        try: menus.clear_plugin(plugin_id)
        except (Exception, SystemExit): traceback.print_exc()
    hooks[:] = [x for x in hooks if x[0] != plugin_id]; send_hooks[:] = [x for x in send_hooks if x[0] != plugin_id]
    module_name = "wgtg_plugin_" + plugin_id
    elyx.unregister_environment(module_name)
    for name in list(sys.modules):
        if name == module_name or name.startswith(module_name + "."): sys.modules.pop(name, None)
    for path in (source_paths.pop(plugin_id, None), dependency_paths.pop(plugin_id, None)):
        if not path: continue
        if path not in source_paths.values():
            sys.path[:] = [entry for entry in sys.path if entry != path]
        if path == os.path.realpath(root): continue  # Legacy loose plugins share this directory.
        for name, module in list(sys.modules.items()):
            namespace = getattr(module, "__dict__", {})
            locations = list(namespace.get("__path__", ()) or ())
            filename = namespace.get("__file__")
            if filename: locations.append(filename)
            if locations and all(os.path.realpath(p).startswith(path + os.sep) for p in locations):
                sys.modules.pop(name, None)
        importlib.invalidate_caches()

def list_plugins():
    state = _state(); result = []
    for plugin_id, main, meta in _descriptors():
        item = loaded.get(plugin_id, {})
        meta = item.get("meta", meta)
        result.append({"id": plugin_id, "name": meta.get("name", plugin_id), "version": str(meta.get("version", "1.0")), "author": meta.get("author", ""), "description": meta.get("description", ""), "enabled": state["enabled"].get(plugin_id, False), "error": item.get("error", "")})
    return json.dumps(result, ensure_ascii=False)

def install(path):
    temp = tempfile.mkdtemp(prefix=".install-", dir=root)
    try:
        return _install(path, temp)
    finally:
        shutil.rmtree(temp, ignore_errors=True)

def _prepare(path, temp):
    if os.path.getsize(path) > 32 * 1024 * 1024: raise ValueError("Plugin is larger than 32 MB")
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 2048 or sum(x.file_size for x in archive.infolist()) > 64 * 1024 * 1024: raise ValueError("Plugin archive is too large")
            base = os.path.realpath(temp) + os.sep
            for item in archive.infolist():
                target = os.path.realpath(os.path.join(temp, item.filename))
                if not target.startswith(base): raise ValueError("Unsafe archive path")
                if (item.external_attr >> 16) & 0o170000 == 0o120000: raise ValueError("Symlinks are not supported")
                if item.filename.endswith((".so", ".pyc")): raise ValueError("Binary plugin modules are not supported")
                if any(part.startswith(".wgtg") for part in item.filename.split("/")): raise ValueError("Reserved plugin path")
            archive.extractall(temp)
        main, meta = _project_info(temp); plugin_id = meta.get("id")
        from plugin_dependencies import Wheel, _wheel
        declared_wheels = set(_wheel_paths(temp))
        for directory, _, names in os.walk(temp):
            for name in names:
                if name.endswith(".whl"):
                    wheel_path = os.path.join(directory, name)
                    if wheel_path not in declared_wheels: raise ValueError("Bundled wheels must be direct children of the refmap wheels directory")
                    with open(wheel_path, "rb") as wheel: _wheel(Wheel(name, wheel.read()))
    else:
        meta = _source_metadata(path); plugin_id = meta.get("id")
        shutil.copyfile(path, os.path.join(temp, "main.py"))
    if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
    if not isinstance(meta.get("name"), str) or not meta["name"].strip(): raise ValueError("Plugin name is required")
    from plugin_dependencies import _requirements
    _requirements(meta.get("requirements"))
    requires = meta.get("requires")
    if requires and not isinstance(requires, dict): raise ValueError("requires must be a plugin-id/link mapping")
    return plugin_id, meta

def inspect_plugin(path):
    temp = tempfile.mkdtemp(prefix=".inspect-", dir=root)
    try:
        plugin_id, meta = _prepare(path, temp)
        result = {key: meta.get(key, "") for key in ("id", "name", "author", "description", "app_version", "sdk_version", "min_version", "requirements", "requires")}
        result["version"] = str(meta.get("version", "1.0"))
        result["installed_version"] = next((str(m.get("version", "1.0")) for pid, _, m in _descriptors() if pid == plugin_id), None)
        result["wheels"] = [os.path.basename(path) for path in _wheel_paths(temp)]
        return json.dumps(result, ensure_ascii=False)
    finally:
        shutil.rmtree(temp, ignore_errors=True)

def _install(path, temp):
    plugin_id, meta = _prepare(path, temp)
    with _lock:
        _validate_requires(meta)
        _check_dependents(plugin_id)
    record = _prepare_dependencies(plugin_id, temp, meta)
    try:
        with _lock: return _publish_install(plugin_id, temp, meta, record)
    except BaseException:
        if _state().get("dependencies", {}).get(plugin_id) != record:
            _remove_dependencies(record)
        raise

def _publish_install(plugin_id, temp, meta, record):
    _validate_requires(meta)
    _check_dependents(plugin_id)
    if record: _dependency_conflicts(plugin_id, _dependency_path(record))
    state = _state()
    old_record = state.get("dependencies", {}).get(plugin_id)
    marker = os.path.basename(temp)
    _write_json(os.path.join(temp, ".wgtg-install.json"), marker)
    destination = os.path.join(root, plugin_id)
    backup = os.path.join(root, ".backup-" + plugin_id)
    shutil.rmtree(backup, ignore_errors=True)
    if os.path.isdir(destination): os.replace(destination, backup)
    replaced = False
    try:
        os.replace(temp, destination)
        replaced = True
        state["enabled"][plugin_id] = False
        state.setdefault("installations", {})[plugin_id] = marker
        dependencies = state.setdefault("dependencies", {})
        if record: dependencies[plugin_id] = record
        else: dependencies.pop(plugin_id, None)
        _write_json(_state_path(), state)
    except Exception:
        if replaced: shutil.rmtree(destination)
        if os.path.isdir(backup): os.replace(backup, destination)
        raise
    _unload(plugin_id)
    _remove_dependencies(old_record)
    shutil.rmtree(backup, ignore_errors=True)
    legacy = os.path.join(root, plugin_id + ".py")
    if os.path.isfile(legacy): os.remove(legacy)
    return plugin_id

def set_enabled(plugin_id, enabled):
    if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
    with _lock:
        descriptor = next((item for item in _descriptors() if item[0] == plugin_id), None)
        if descriptor is None: raise ValueError("Plugin is not installed")
        _, main, meta = descriptor
        revision = os.stat(main)
        if not enabled: _check_dependents(plugin_id)
        record = _state().get("dependencies", {}).get(plugin_id)
        if enabled: _validate_requires(meta)
    prepared = None
    if enabled and not record:
        prepared = _prepare_dependencies(plugin_id, _plugin_folder(plugin_id, main), meta)
    try:
        with _lock:
            current = os.stat(main)
            if (current.st_ino, current.st_mtime_ns, current.st_size) != (revision.st_ino, revision.st_mtime_ns, revision.st_size):
                raise ValueError("Plugin changed while preparing dependencies; try enabling again")
            if enabled: _validate_requires(meta)
            else: _check_dependents(plugin_id)
            state = _state()
            if prepared: state.setdefault("dependencies", {})[plugin_id] = prepared
            state["enabled"][plugin_id] = bool(enabled)
            _write_json(_state_path(), state)
            prepared = None  # Persisted generations survive a plugin load error.
            _unload(plugin_id)
            if enabled: _load(plugin_id, main, meta)
    finally:
        _remove_dependencies(prepared)

def uninstall(plugin_id):
    if not _valid_id(plugin_id): raise ValueError("Invalid plugin id")
    _check_dependents(plugin_id)
    record = _state().get("dependencies", {}).get(plugin_id)
    _unload(plugin_id)
    for path in (os.path.join(root, plugin_id), os.path.join(root, plugin_id + ".py")):
        if os.path.isdir(path): shutil.rmtree(path)
        elif os.path.isfile(path): os.remove(path)
    state = _state(); state["enabled"].pop(plugin_id, None); state["settings"].pop(plugin_id, None)
    state.get("dependencies", {}).pop(plugin_id, None)
    state.get("installations", {}).pop(plugin_id, None)
    _write_json(_state_path(), state)
    _remove_dependencies(record)

def add_hook(plugin_id, name, match_substring=False, priority=0):
    if not isinstance(name, str) or not name:
        raise ValueError("Hook name must be a non-empty string")
    hooks.append((plugin_id, name, bool(match_substring), int(priority)))
    hooks.sort(key=lambda x: -x[3])
def add_send_hook(plugin_id, priority=0): send_hooks.append((plugin_id, int(priority))); send_hooks.sort(key=lambda x: -x[1])
def _dispatch(method, field, name, account, value, *extra):
    registry = send_hooks if name is None else hooks
    # Capture instances as well as registrations: unload/reload during a callback
    # must not dispatch an old registration into a newly loaded plugin.
    snapshot = [(entry, loaded.get(entry[0], {}).get("instance")) for entry in registry]
    for entry, instance in snapshot:
        if instance is None or loaded.get(entry[0], {}).get("instance") is not instance:
            continue
        if name is not None and not (entry[1] in name if entry[2] else entry[1] == name):
            continue
        try:
            args = (account, value) if name is None else (name, account, value, *extra)
            with account_scope(account), elyx.environment_scope(loaded[entry[0]].get("environment")):
                result = getattr(instance, method)(*args)
            strategy = HookStrategy(getattr(result, "strategy", HookStrategy.DEFAULT))
            if strategy == HookStrategy.CANCEL: return True, value
            if strategy in (HookStrategy.MODIFY, HookStrategy.MODIFY_FINAL):
                replacement = getattr(result, field, None)
                if replacement is not None: value = replacement
            if strategy == HookStrategy.MODIFY_FINAL: break
        except (Exception, SystemExit): traceback.print_exc()
    return False, value

def before_request(name, account, request):
    cancelled, value = _dispatch("pre_request_hook", "request", name, account, request)
    return None if cancelled else value

def after_request(name, account, response, error):
    # A null response is valid for RPC errors; cancellation needs a separate flag.
    cancelled, value = _dispatch("post_request_hook", "response", name, account, response, error)
    return [cancelled, value]

def before_update(name, account, update):
    cancelled, value = _dispatch("on_update_hook", "update", name, account, update)
    return None if cancelled else value

def before_updates(name, account, updates):
    cancelled, value = _dispatch("on_updates_hook", "updates", name, account, updates)
    return None if cancelled else value

def before_send_message(account, params):
    cancelled, value = _dispatch("on_send_message_hook", "params", None, account, params)
    return None if cancelled else value

def on_app_event(event):
    global app_event
    event = AppEvent(event)
    if event == app_event: return
    app_event = event
    for plugin_id, item in list(loaded.items()):
        instance = item.get("instance")
        if instance is None or loaded.get(plugin_id) is not item: continue
        try:
            with elyx.environment_scope(item.get("environment")):
                instance.on_app_event(event)
        except (Exception, SystemExit): traceback.print_exc()

def shutdown():
    on_app_event(AppEvent.STOP)
    for plugin_id in list(loaded): _unload(plugin_id)

def settings_rows(plugin_id, parent=None):
    from dataclasses import fields
    global _settings_token
    item = loaded.get(plugin_id, {})
    instance = item.get("instance")
    with elyx.environment_scope(item.get("environment")):
        if parent is None:
            rows = instance.create_settings() if instance else []
        else:
            rows = item.get("settings_rows", {})[parent].create_sub_fragment()
    result = []
    registered = {}
    for row in rows:
        kind = type(row).__name__
        if kind not in ("Header", "Input", "Switch", "Text", "Selector", "Divider", "EditText"):
            raise NotImplementedError("Unsupported settings row: " + kind)
        data = {f.name: getattr(row, f.name) for f in fields(row)
                if f.name not in ("on_change", "on_click", "on_long_click", "create_sub_fragment")}
        if kind == "EditText" and row.mask:
            raise NotImplementedError("EditText mask is not supported by this host")
        _settings_token += 1
        data.update(type=kind, token=str(_settings_token))
        for callback in ("on_click", "on_long_click", "create_sub_fragment"):
            data[callback] = callable(getattr(row, callback, None))
        if "key" in data: data["value"] = get_setting(plugin_id, data["key"], data.get("default"))
        result.append(data)
        registered[data["token"]] = row
    encoded = json.dumps(result)
    item["settings_rows"] = registered
    return encoded

def settings_action(plugin_id, token, action, value="null", view=None):
    item = loaded.get(plugin_id, {})
    if not item.get("instance") or token not in item.get("settings_rows", {}):
        raise ValueError("Settings page expired; reopen the plugin settings")
    row = item["settings_rows"][token]
    with elyx.environment_scope(item.get("environment")):
        if action == "change":
            value = json.loads(value)
            kind = type(row).__name__
            if kind == "Switch": valid = type(value) is bool
            elif kind == "Selector": valid = type(value) is int and 0 <= value < len(row.items)
            elif kind in ("Input", "EditText"):
                valid = isinstance(value, str) and (not getattr(row, "max_length", 0) or len(value) <= row.max_length)
            else: valid = False
            if not valid: raise ValueError("Invalid settings value")
            set_setting(plugin_id, row.key, value)
            if row.on_change: row.on_change(value)
        elif action in ("on_click", "on_long_click"):
            callback = getattr(row, action, None)
            if callback: return bool(callback(view))
        else:
            raise ValueError("Unknown settings action")
    return False

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
for _name in ("initialize", "inspect_plugin", "uninstall", "list_plugins",
              "before_request", "after_request", "before_update", "before_updates", "on_app_event", "shutdown",
              "before_send_message", "settings_rows", "settings_action", "set_setting_json",
              "get_settings", "get_setting", "set_setting", "replace_settings", "add_hook", "add_send_hook"):
    globals()[_name] = _serialized(globals()[_name])
