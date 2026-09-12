"""Offline XML, locale, launcher and Java resource-reference checks. No Gradle/SDK."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main"
res = root / "res"
android = "{http://schemas.android.com/apk/res/android}"
resources = {}
for directory in res.iterdir():
    if not directory.is_dir():
        continue
    kind = directory.name.split("-")[0]
    for file in directory.iterdir():
        if kind == "values" and file.suffix == ".xml":
            for item in ET.parse(file).getroot():
                resources.setdefault(item.tag, set()).add(item.get("name"))
        else:
            resources.setdefault(kind, set()).add(file.stem)

for file in res.rglob("*wgtg*.xml"):
    tree = ET.parse(file)
    for element in tree.iter():
        for value in element.attrib.values():
            match = re.fullmatch(r"@(\w+)/(\w+)", value)
            if match:
                kind, name = match.groups()
                assert name in resources.get(kind, set()), (file, value)

for name in ("wgtg_settings.xml", "wgtg_extra_icons.xml"):
    en = {e.get("name") for e in ET.parse(res / "values" / name).getroot() if e.tag == "string"}
    ru = {e.get("name") for e in ET.parse(res / "values-ru" / name).getroot() if e.tag == "string"}
    assert en == ru, (name, en ^ ru)

java = root / "java/org/telegram"
for file in list(java.rglob("Wgtg*.java")) + [java / "ui/LauncherIconController.java"]:
    for kind, name in re.findall(r"R\.(string|drawable|mipmap|color)\.(\w+)", file.read_text()):
        assert name in resources.get(kind, set()), (file, kind, name)

manifest = ET.parse(root / "AndroidManifest.xml")
aliases = {e.get(android + "name"): e for e in manifest.findall("application/activity-alias")}
icons = re.findall(r'^\s*[A-Z_]+\("(\w+Icon)"', (java / "ui/LauncherIconController.java").read_text(), re.M)
assert len(icons) >= 13, icons
for icon in icons:
    alias = aliases["org.telegram.messenger." + icon]
    assert alias.get(android + "targetActivity") == "org.telegram.ui.LaunchActivity"
    if icon == "DefaultIcon" and alias.get(android + "icon") is None:
        continue  # The default alias inherits the application icon from the build flavor.
    resource = alias.get(android + "icon").split("/")[1]
    assert resource in resources["mipmap"], resource
    if icon in ("CobaltWgtgIcon", "PlumWgtgIcon", "CoralWgtgIcon"):
        for suffix in ("", "-v26", "-v33"):
            ET.parse(res / ("mipmap-anydpi" + suffix) / (resource + ".xml"))
        assert alias.get(android + "enabled") == "false"

provider = ET.parse(res / "xml/provider_paths.xml")
assert any(e.get("path") == "wgtg_themes/" for e in provider.findall("cache-path"))
print(f"PASS: WGTG XML, EN/RU parity, Java resource references, {len(icons)} launcher aliases and theme FileProvider path")
