"""Resource loading for an already registered plugin, after dependencies activate."""
import importlib.util
import json
from pathlib import Path
import re
import sys


def _mapping(path):
    if path.suffix == ".json":
        value = json.loads(path.read_text(encoding="utf-8"))
    else:
        import yaml
        value = yaml.safe_load(path.read_text(encoding="utf-8"))
    if value is None:
        return {}
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise ValueError(f"Expected a string-keyed mapping: {path}")
    return value


def _inside(folder, value):
    if not isinstance(value, str) or not value or "\\" in value:
        raise ValueError("Resource paths must be non-empty relative strings")
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts:
        raise ValueError("Resource path must stay inside the plugin")
    path = (folder / relative).resolve()
    if not path.is_relative_to(folder) or not path.exists():
        raise ValueError(f"Resource path must exist inside the plugin: {value}")
    return path


def read_refmap(folder):
    folder = Path(folder).resolve()
    for name in ("refmap.yaml", "refmap.yml", "refmap.json"):
        if (folder / name).exists():
            return _mapping(_inside(folder, name))
    return {}


def load_resources(folder, environment):
    from . import Assets, Strings
    folder = Path(folder).resolve()
    refmap = read_refmap(folder)
    environment["refmap"] = refmap
    asset_path = refmap.get("assets", "assets" if (folder / "assets").is_dir() else None)
    if asset_path:
        environment["assets"] = Assets(_inside(folder, asset_path))
    strings_path = refmap.get("strings")
    if not strings_path:
        return
    path = _inside(folder, strings_path)
    catalogs = {}
    files = sorted(path.iterdir()) if path.is_dir() else [path]
    for index, file in enumerate(files):
        if file.suffix not in (".json", ".yaml", ".yml", ".py") or not file.is_file():
            continue
        file = _inside(folder, str(file.relative_to(folder)))
        locale = file.stem.rsplit("_", 1)[1] if "_" in file.stem else "en"
        if file.suffix == ".py":
            name = environment["package"] + f"._elyx_locale_{index}"
            spec = importlib.util.spec_from_file_location(name, file)
            module = importlib.util.module_from_spec(spec)
            sys.modules[name] = module
            spec.loader.exec_module(module)
            values = {key: value for key, value in vars(module).items()
                      if not key.startswith("_") and isinstance(value, (str, int, float, bool, list, tuple, dict, type(None)))}
        else:
            values = _mapping(file)
        catalogs.setdefault(locale, {}).update(values)
    if any(catalogs.values()):
        strings = environment["strings"] = Strings(catalogs)
        description = environment["metainfo"].get("description")
        if isinstance(description, str):
            environment["metainfo"]["description"] = re.sub(
                r"\{([^{}]+)\}", lambda match: str(strings.get(match[1])), description)
