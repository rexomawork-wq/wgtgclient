# Dependency runtime integration

Sources checked: https://plugins.exteragram.app/docs/pip and
https://plugins.exteragram.app/docs/elyx/dependencies.

## Metadata and preview

Single-file plugins declare literal `__requirements__` (a PEP 508 string list)
and `__requires__` (a plugin-id/link mapping). Elyx JSON/YAML metadata accepts
`requirements` as a list or comma-separated string, and `requires` as a mapping.
Required-plugin keys may include a parenthesized minimum version. Links are
descriptive and are never fetched or executed.

`refmap.json`, `refmap.yml`, or `refmap.yaml` may set `wheels` to a directory inside
the project. Only its direct `.whl` children are accepted. Nested entrypoints are
supported; the wheels path is relative to the project root, not the entrypoint.
Each local wheel is validated through the sibling dependency module, including
its tags, metadata, archive paths, and prohibited native/startup files.

`inspect_plugin` only extracts into temporary storage, parses metadata, checks
requirement syntax, and validates bundled wheels. It never calls the resolver,
downloads anything, installs dependencies, or imports plugin/dependency modules.
Its JSON includes `requirements`, `requires`, and `wheels` for the confirmation UI.
Required-plugin availability is enforced on install/enable, not metadata preview.

## Install and enable

The existing Java `Utilities.globalQueue` installation flow calls `install`.
Enable now uses the same background queue and shows a non-cancellable progress
dialog. Controller errors are returned to the plugin screen; load errors remain
visible on the affected plugin row. Uninstall also runs on the background queue.

The engine calls the sibling contracts:

```python
validate_required_plugins(metadata.get("requires"), installed_plugins)
plan = prepare_requirements(metadata.get("requirements"), wheels=wheel_paths)
install_requirements(plan, new_generation_directory)
```

Availability reflects successfully loaded instances, not merely saved enable
flags. Active dependents must be disabled before replacing, disabling, or removing
their required provider. Startup loads providers before their consumers and
reports missing, failed, or cyclic providers as load errors.

Resolution and generation extraction run outside the engine's hook-dispatch lock.
The publication transaction holds the lock and revalidates required plugins and
import conflicts. Enable checks that the entrypoint has not changed while it was
preparing dependencies. The UI queue serializes ordinary user mutations.

Each generation has an independent path under `plugins/.dependencies/generation-*`.
`state.json.dependencies[plugin_id].path` stores its relative path. Downloads and
extraction must succeed before replacing existing plugin files or state. A failed
update preserves the old running instance and its dependency generation. Failed
publication restores the previous plugin directory and removes the new generation.

Each published plugin directory contains an engine-owned `.wgtg-install.json`
marker, matched by `state.json.installations[plugin_id]`. At restart, this identifies
whether a leftover update backup should be restored or discarded. Unreferenced
dependency generations are collected. Corrupt state is reported instead of being
overwritten with empty records.

Installation leaves the plugin disabled. On enable, the persisted generation path
is validated and activated before executing the entrypoint. It stays on `sys.path`
for lazy imports. Legacy installed plugins without dependency records are prepared
when explicitly enabled. Startup never downloads: missing generations cause an
actionable reinstall error. Even an empty marker-filtered plan gets a persisted
generation, preventing repeated resolution.

Unload removes the generation's import path and modules loaded from that path,
after plugin callbacks and owned-hook cleanup. A failed plugin load follows the
same deactivation path but retains the generation for retry. A committed update
removes the old generation; uninstall removes the current generation and record.

## Compatibility limits

`packaging==24.2` is bundled in the Chaquopy Gradle configuration. The sibling
resolver installs compatible pure-Python wheels only, with HTTPS and PyPI SHA-256
verification. It does not invoke pip, source builds, setup hooks, or subprocesses.
Its complete artifact/resolution restrictions are documented in
`TMessagesProj/src/main/python/PLUGIN_COMPATIBILITY.md`.

All plugins share `sys.path` and `sys.modules`. Separate generations are storage
ownership, not interpreter isolation. The runtime conservatively rejects any
overlapping top-level import with another active plugin or an existing host/stdlib
library. This includes matching-version overlaps: shared dependency deduplication
and reuse of bundled requirements are not implemented. Disable the conflicting
plugin or omit an already bundled library from requirements when appropriate.
Dependencies cannot override host libraries. Plugin background tasks must be
stopped on unload; retained Python references cannot be revoked or sandboxed.

## Verification

`Tools/test_wgtg_dependency_runtime.py` uses real small wheel archives and an
offline PyPI JSON/artifact fixture. It exercises preview purity, remote and bundled
installation, nested Elyx metadata, import activation, lazy imports, restart,
failure rollback, interrupted-update recovery, cleanup, dependency ordering,
conflicts, marker-only plans, and nonblocking hook dispatch during resolution.

Run with:

```sh
python3 -m unittest Tools.test_wgtg_dependency_runtime Tools.test_wgtg_plugins Tools.test_wgtg_plugin_hooks_events Tools.test_wgtg_plugin_menus
```
