import json
from pathlib import Path
import re
import shutil
import tempfile
from urllib.parse import urlparse
from urllib.request import urlopen


def _cache_dir():
    try:
        from java import jclass
    except ImportError:
        return tempfile.gettempdir()
    return str(jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getCacheDir().getAbsolutePath())


def _dimensions(width, height):
    if int(width) <= 0 or int(height) <= 0:
        raise ValueError("Image dimensions must be positive")
    return int(width), int(height)


class AssetNotFoundException(FileNotFoundError):
    pass


class AssetsDirNotFoundException(FileNotFoundError):
    pass


def _name(filename):
    return re.sub(r"[^A-Za-z0-9]", "_", filename.split(".")[0])


class Asset:
    def __init__(self, dir_path, filename, name):
        self.path = (Path(dir_path) / filename).resolve()
        if not self.path.is_file():
            raise AssetNotFoundException(str(self.path))
        self.filename, self.name = filename, name
        self.ext = self.path.suffix.lstrip(".")
        self.path_str = str(self.path)

    @classmethod
    def from_path(cls, path):
        if hasattr(path, "getAbsolutePath"):
            path = str(path.getAbsolutePath())
        path = Path(path)
        return cls(path.parent, path.name, _name(path.name))

    @classmethod
    def temp_asset_from_url(cls, url, filename):
        if not isinstance(filename, str) or not filename or filename in (".", "..") or Path(filename).name != filename or "\\" in filename:
            raise ValueError("Temporary asset filename must be a basename")
        if urlparse(url).scheme not in ("http", "https"):
            raise ValueError("Temporary assets require an HTTP(S) URL")
        directory = Path(tempfile.mkdtemp(prefix="elyx-asset-", dir=_cache_dir()))
        try:
            with urlopen(url, timeout=30) as response, (directory / filename).open("wb") as output:
                if urlparse(response.geturl()).scheme not in ("http", "https"):
                    raise ValueError("Unsupported asset redirect")
                size = 0
                while True:
                    chunk = response.read(65536)
                    if not chunk:
                        break
                    size += len(chunk)
                    if size > 32 * 1024 * 1024:
                        raise ValueError("Temporary asset exceeds 32 MiB")
                    output.write(chunk)
            return cls.from_path(directory / filename)
        except BaseException:
            shutil.rmtree(directory)
            raise

    @property
    def java_file(self):
        from java import jclass
        return jclass("java.io.File")(self.path_str)

    def content_bytes(self):
        return self.path.read_bytes()

    def content_string(self):
        return self.path.read_text(encoding="utf-8")

    def content_json(self):
        return json.loads(self.content_string())

    def content_yaml(self):
        import yaml
        return yaml.safe_load(self.content_string())

    def content(self):
        if self.ext.lower() == "json":
            return self.content_json()
        if self.ext.lower() in ("yaml", "yml"):
            return self.content_yaml()
        try:
            return self.content_string()
        except UnicodeDecodeError:
            return self.content_bytes()

    def to_drawable(self):
        from java import jclass
        return jclass("android.graphics.drawable.Drawable").createFromPath(self.path_str)

    def to_image_location(self):
        from java import jclass
        return jclass("org.telegram.messenger.ImageLocation").getForPath(self.path_str)

    def to_bitmap_drawable(self, width=32, height=32):
        from java import jclass
        width, height = _dimensions(width, height)
        bitmap = jclass("android.graphics.BitmapFactory").decodeFile(self.path_str)
        if bitmap is None:
            raise ValueError(f"Cannot decode bitmap: {self.filename}")
        scaled = jclass("android.graphics.Bitmap").createScaledBitmap(bitmap, width, height, True)
        resources = jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
        return jclass("android.graphics.drawable.BitmapDrawable")(resources, scaled)

    def to_svg_bitmap(self, width=32, height=32, white=False):
        from java import jclass
        width, height = _dimensions(width, height)
        bitmap = jclass("org.telegram.messenger.SvgHelper").getBitmap(self.java_file, width, height, bool(white))
        if bitmap is None:
            raise ValueError(f"Cannot decode SVG: {self.filename}")
        return bitmap

    def to_svg_drawable(self, width=None, height=None):
        from java import jclass
        if width is not None and height is not None:
            resources = jclass("org.telegram.messenger.ApplicationLoader").applicationContext.getResources()
            return jclass("android.graphics.drawable.BitmapDrawable")(resources, self.to_svg_bitmap(width, height))
        drawable = jclass("org.telegram.messenger.SvgHelper").getDrawable(self.content_string())
        if drawable is None:
            raise ValueError(f"Cannot decode SVG: {self.filename}")
        return drawable

    def to_svg_thumb(self, color_key, alpha):
        drawable = self.to_svg_drawable()
        drawable.setupGradient(int(color_key), float(alpha), False)
        return drawable

    def to_lottie_drawable(self, width=32, height=32):
        from java import jclass
        width, height = _dimensions(width, height)
        utilities = jclass("org.telegram.messenger.AndroidUtilities")
        # RLottieDrawable deletes invalid input files. Protect bundled assets by
        # giving the native decoder an app-cache copy, retained for lazy decoding.
        with tempfile.NamedTemporaryFile(prefix="elyx-lottie-", suffix=".json", dir=_cache_dir(), delete=False) as output:
            with self.path.open("rb") as source:
                shutil.copyfileobj(source, output)
        try:
            return jclass("org.telegram.ui.Components.RLottieDrawable")(
                jclass("java.io.File")(output.name), None, utilities.dp(width), utilities.dp(height),
                None, False, None, 0, False)
        except BaseException:
            Path(output.name).unlink(missing_ok=True)
            raise


class Assets:
    def __init__(self, dir_path):
        self.dir_path = Path(dir_path).resolve()
        if not self.dir_path.is_dir():
            raise AssetsDirNotFoundException(str(self.dir_path))

    @property
    def parent(self):
        return Assets(self.dir_path.parent)

    def get(self, name):
        if not isinstance(name, str) or not name or "\\" in name:
            raise ValueError("Invalid asset path")
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError("Asset path must stay inside its directory")
        current = self.dir_path
        for part in relative.parts:
            exact = current / part
            matches = [p for p in current.iterdir() if _name(p.name) == part] if not exact.exists() else [exact]
            if len(matches) != 1:
                raise AssetNotFoundException(name)
            current = matches[0].resolve()
            if not current.is_relative_to(self.dir_path):
                raise ValueError("Asset symlink escapes its directory")
        return Assets(current) if current.is_dir() else Asset.from_path(current)

    __getitem__ = get

    def __getattr__(self, name):
        return self.get(name)

    def __iter__(self):
        return iter(sorted(p.name for p in self.dir_path.iterdir()))

    def __len__(self):
        return sum(1 for _ in self.dir_path.iterdir())

    def __contains__(self, name):
        try:
            self.get(name)
            return True
        except (AssetNotFoundException, ValueError):
            return False
