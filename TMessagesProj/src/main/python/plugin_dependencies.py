"""Wheel-only plugin dependencies. No pip, subprocess, build hooks or sys.path edits."""
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tempfile
from dataclasses import dataclass
from email.parser import BytesParser
from urllib.parse import quote, urlparse
from urllib.request import urlopen
from zipfile import ZipFile


class DependencyError(ValueError):
    """Invalid, incompatible, or unsatisfiable plugin dependencies."""


MAX_DOWNLOAD = 32 * 1024 * 1024
MAX_EXPANDED = 128 * 1024 * 1024


def _packaging():
    try:
        from packaging.requirements import Requirement
        from packaging.specifiers import SpecifierSet
        from packaging.version import Version
        from packaging.utils import canonicalize_name, parse_wheel_filename
        from packaging.markers import default_environment
    except ImportError as exc:
        raise DependencyError("Host must bundle packaging==24.2 in Chaquopy") from exc
    return Requirement, SpecifierSet, Version, canonicalize_name, parse_wheel_filename, default_environment


def _fetch(url):
    if urlparse(url).scheme != "https":
        raise DependencyError("Dependency downloads require HTTPS")
    with urlopen(url, timeout=30) as response:
        if urlparse(response.geturl()).scheme != "https":
            raise DependencyError("Dependency redirect must use HTTPS")
        data = response.read(MAX_DOWNLOAD + 1)
    if len(data) > MAX_DOWNLOAD:
        raise DependencyError("Dependency download exceeds 32 MiB")
    return data


def _requirements(value):
    Requirement = _packaging()[0]
    if value is None or value == "":
        return []
    if isinstance(value, str):
        # Elyx separates packages with commas, also used by version ranges/extras.
        parts, start, depth, quote_char = [], 0, 0, None
        for index, char in enumerate(value):
            if quote_char:
                if char == quote_char: quote_char = None
            elif char in "\"'":
                quote_char = char
            elif char in "[(":
                depth += 1
            elif char in "])":
                depth -= 1
            elif char == "," and depth == 0 and re.match(r"\s*[A-Za-z0-9]", value[index + 1:]):
                parts.append(value[start:index])
                start = index + 1
        value = parts + [value[start:]]
    if not isinstance(value, (list, tuple)) or not all(isinstance(v, str) for v in value):
        raise DependencyError("requirements must be a string or list of PEP 508 strings")
    result = []
    for text in value:
        try:
            req = Requirement(text)
        except ValueError as exc:
            raise DependencyError(f"Invalid requirement: {text!r}") from exc
        if req.url:
            raise DependencyError("Direct URL, VCS and local-path requirements are unsupported; bundle a wheel")
        if any(spec.operator == "===" for spec in req.specifier):
            raise DependencyError("Arbitrary === versions are unsupported; use PEP 440 versions")
        result.append(req)
    return result


@dataclass(frozen=True)
class Wheel:
    filename: str
    data: bytes


@dataclass(frozen=True)
class DependencyPlan:
    wheels: tuple


def _wheel(wheel):
    _, SpecifierSet, Version, canonical, parse_filename, _ = _packaging()
    if Path(wheel.filename).name != wheel.filename or "\\" in wheel.filename:
        raise DependencyError("Wheel filename must be a basename")
    try:
        name, version, _, tags = parse_filename(wheel.filename)
    except ValueError as exc:
        raise DependencyError(f"Invalid wheel filename: {wheel.filename}") from exc
    python_tags = {"py3", f"py{sys.version_info.major}{sys.version_info.minor}"}
    if not tags or any(t.abi != "none" or t.platform != "any" for t in tags) or not any(t.interpreter in python_tags for t in tags):
        raise DependencyError(f"Unsupported Android/Python wheel: {wheel.filename}; require py3-none-any")
    if len(wheel.data) > MAX_DOWNLOAD:
        raise DependencyError("Wheel exceeds download limit")
    files = {}
    with ZipFile(io.BytesIO(wheel.data)) as archive:
        entries = archive.infolist()
        if len(entries) > 10000 or sum(i.file_size for i in entries) > MAX_EXPANDED:
            raise DependencyError("Wheel exceeds extraction limits")
        for entry in entries:
            path = PurePosixPath(entry.filename)
            mode = entry.external_attr >> 16
            if (not entry.filename or "\\" in entry.filename or ":" in entry.filename
                    or path.is_absolute() or ".." in path.parts
                    or any(p in ("", ".") for p in entry.filename.rstrip("/").split("/"))
                    or stat.S_ISLNK(mode) or stat.S_IFMT(mode) not in (0, stat.S_IFREG, stat.S_IFDIR)):
                raise DependencyError(f"Unsafe wheel path: {entry.filename}")
            if entry.is_dir():
                continue
            if path.suffix.lower() in (".so", ".pyd", ".dll", ".dylib", ".a", ".exe", ".pyc", ".pth"):
                raise DependencyError(f"Unsupported binary/startup file: {entry.filename}")
            parts = path.parts
            if parts[0].endswith(".data"):
                if len(parts) < 3 or parts[1] not in ("purelib", "platlib"):
                    raise DependencyError(f"Unsupported wheel data scheme: {entry.filename}")
                path = PurePosixPath(*parts[2:])
            key = str(path)
            if key in files:
                raise DependencyError(f"Duplicate wheel destination: {key}")
            data = archive.read(entry)
            if data.startswith((b"\x7fELF", b"MZ", b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf")):
                raise DependencyError(f"Native binary in pure wheel: {entry.filename}")
            files[key] = data
    metadata_paths = [p for p in files if p.endswith(".dist-info/METADATA") and p.count("/") == 1]
    if len(metadata_paths) != 1:
        raise DependencyError("Wheel must contain exactly one METADATA")
    prefix = metadata_paths[0].rsplit("/", 1)[0]
    metadata = BytesParser().parsebytes(files[metadata_paths[0]])
    wheel_meta = BytesParser().parsebytes(files.get(prefix + "/WHEEL", b""))
    if wheel_meta.get("Wheel-Version") != "1.0" or wheel_meta.get("Root-Is-Purelib", "").lower() != "true":
        raise DependencyError("Only Wheel-Version 1.0 purelib wheels are supported")
    from packaging.tags import parse_tag
    try:
        declared_tags = set().union(*(parse_tag(t) for t in wheel_meta.get_all("Tag", [])))
    except ValueError as exc:
        raise DependencyError("Invalid WHEEL compatibility tags") from exc
    if declared_tags != tags:
        raise DependencyError("Wheel filename and WHEEL compatibility tags disagree")
    if canonical(metadata.get("Name", "")) != name or Version(metadata.get("Version", "0")) != version:
        raise DependencyError("Wheel filename and METADATA identity disagree")
    if not SpecifierSet(metadata.get("Requires-Python", "")).contains(Version(".".join(map(str, sys.version_info[:3]))), prereleases=True):
        raise DependencyError(f"{wheel.filename} requires Python {metadata['Requires-Python']}")
    return name, version, _requirements(metadata.get_all("Requires-Dist", [])), files


def prepare_requirements(requirements=None, *, wheels=(), fetch=None, environment=None):
    """Resolve/download without executing code. wheels is an iterable of local paths.

    fetch(url)->bytes is injectable for offline indexes/tests. environment overrides
    PEP 508 marker values; default target is this Python on Android/Linux.
    """
    _, SpecifierSet, Version, canonical, parse_filename, default_environment = _packaging()
    env = default_environment()
    env.update({"sys_platform": "linux", "platform_system": "Linux", "platform_release": "", "platform_version": ""})
    if environment:
        env.update(environment)
    fetch = fetch or _fetch
    bundled = {}
    roots = _requirements(requirements)
    for path in wheels:
        path = Path(path)
        if path.stat().st_size > MAX_DOWNLOAD:
            raise DependencyError("Bundled wheel exceeds 32 MiB")
        wheel = Wheel(path.name, path.read_bytes())
        name, version, _, _ = _wheel(wheel)
        if name in bundled:
            raise DependencyError(f"Multiple bundled wheels for {name}")
        bundled[name] = wheel
        roots.extend(_requirements([f"{name}=={version}"]))
    cache = {}
    attempts = 0

    def candidates(name, constraints):
        if name in bundled:
            yield bundled[name]
            return
        url = f"https://pypi.org/pypi/{quote(name)}/json"
        if url not in cache:
            cache[url] = json.loads(fetch(url))
        releases = cache[url]["releases"]
        versions = []
        for raw in releases:
            try:
                v = Version(raw)
            except ValueError:
                continue
            if all(r.specifier.contains(v) for r in constraints):
                versions.append((v, raw))
        for _, raw in sorted(versions, reverse=True):
            for artifact in sorted(releases[raw], key=lambda f: f["filename"]):
                if artifact.get("yanked") or artifact.get("packagetype") != "bdist_wheel":
                    continue
                try:
                    _, _, _, tags = parse_filename(artifact["filename"])
                except ValueError:
                    continue
                if not any(t.platform == "any" and t.abi == "none" and t.interpreter in ("py3", f"py{sys.version_info.major}{sys.version_info.minor}") for t in tags):
                    continue
                if not SpecifierSet(artifact.get("requires_python") or "").contains(env["python_full_version"], prereleases=True):
                    continue
                link = artifact["url"]
                if urlparse(link).scheme != "https":
                    raise DependencyError("Wheel URL must use HTTPS")
                if link not in cache:
                    data = fetch(link)
                    if hashlib.sha256(data).hexdigest() != artifact.get("digests", {}).get("sha256"):
                        raise DependencyError(f"SHA256 mismatch: {artifact['filename']}")
                    cache[link] = Wheel(artifact["filename"], data)
                yield cache[link]

    def solve(selected):
        nonlocal attempts
        attempts += 1
        if attempts > 500 or len(selected) > 100:
            raise DependencyError("Dependency resolution limit exceeded")
        constraints = {}
        pending = [(r, {""}) for r in roots]
        expanded = {}
        while pending:
            req, extras = pending.pop()
            if req.marker and not any(req.marker.evaluate(dict(env, extra=e)) for e in extras):
                continue
            name = canonical(req.name)
            constraints.setdefault(name, []).append(req)
            if name in selected:
                _, version, dependencies, _ = _wheel(selected[name])
                if not req.specifier.contains(version):
                    return None
                wanted = {""} | set(req.extras) | expanded.get(name, set())
                if expanded.get(name) != wanted:
                    expanded[name] = wanted
                    pending.extend((d, wanted) for d in dependencies)
        missing = next((n for n in constraints if n not in selected), None)
        if missing is None:
            return selected
        for wheel in candidates(missing, constraints[missing]):
            try:
                name, version, _, _ = _wheel(wheel)
            except DependencyError:
                continue
            if name != missing or not all(r.specifier.contains(version) for r in constraints[missing]):
                continue
            result = solve(dict(selected, **{missing: wheel}))
            if result is not None:
                return result
        return None

    selected = solve({})
    if selected is None:
        raise DependencyError("No compatible pure-Python wheel resolution for " + ", ".join(map(str, roots)) + "; binary wheels, source builds, incompatible Python versions and conflicting constraints are unsupported")
    return DependencyPlan(tuple(selected[n] for n in sorted(selected)))


def install_requirements(plan, target_dir):
    """Atomically publish a NEW site directory; return tuple of absolute import paths.

    Caller owns plugin-specific target, activation, old-generation cleanup and locks.
    Existing targets are rejected; failures leave no partially installed target.
    """
    target = Path(target_dir).absolute()
    if target.exists() or target.is_symlink():
        raise DependencyError("Dependency target must be a new directory")
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".dependencies-", dir=target.parent))
    try:
        written = set()
        for wheel in plan.wheels:
            _, _, _, files = _wheel(wheel)
            for relative, data in files.items():
                if relative in written:
                    raise DependencyError(f"Dependency file collision: {relative}")
                written.add(relative)
                path = stage / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
        os.rename(stage, target)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
    return (str(target),) if plan.wheels else ()


def validate_required_plugins(requires, installed):
    """Check Elyx requires mapping against {id: {version: str, enabled: bool}}.

    Links are descriptive metadata, never downloaded or installed automatically.
    Parenthesized versions are minimum versions, per the Elyx dependency docs.
    """
    if not requires:
        return
    if not isinstance(requires, dict):
        raise DependencyError("requires must be a plugin-id/link mapping")
    Version = _packaging()[2]
    for key, link in requires.items():
        match = re.fullmatch(r"([A-Za-z0-9_.-]+)(?:\s*\(([^()]+)\))?", key)
        if not match or not isinstance(link, str):
            raise DependencyError(f"Invalid required-plugin declaration: {key!r}")
        plugin_id, minimum = match.groups()
        current = installed.get(plugin_id)
        if not current or not current.get("enabled"):
            raise DependencyError(f"Required plugin {plugin_id} is missing or disabled ({link})")
        try:
            outdated = minimum and Version(str(current.get("version", "0"))) < Version(minimum)
        except ValueError as exc:
            raise DependencyError(f"Unsupported required-plugin version: {key}") from exc
        if outdated:
            raise DependencyError(f"Required plugin {plugin_id} needs version >= {minimum} ({link})")
