from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
import tempfile
from threading import Thread
import types
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "main/python"))
from elyx import Asset


class AssetsTest(unittest.TestCase):
    def test_http_temporary_asset_download_and_invalid_filename(self):
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'{"downloaded": true}')
            def log_message(self, *args):
                pass
        with ThreadingHTTPServer(("127.0.0.1", 0), Handler) as server, tempfile.TemporaryDirectory() as cache:
            thread = Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                with patch("elyx._assets._cache_dir", return_value=cache):
                    url = f"http://127.0.0.1:{server.server_port}/resource"
                    asset = Asset.temp_asset_from_url(url, "download.json")
                    self.assertEqual(asset.content_json(), {"downloaded": True})
                    self.assertTrue(asset.path.is_relative_to(cache))
                    for name in ("../escape", "/absolute", "a\\b", "..", ""):
                        with self.subTest(name=name), self.assertRaises(ValueError):
                            Asset.temp_asset_from_url(url, name)
            finally:
                server.shutdown()
                thread.join()

    def test_failed_download_removes_temporary_directory(self):
        with tempfile.TemporaryDirectory() as cache:
            with patch("elyx._assets._cache_dir", return_value=cache), patch("elyx._assets.urlopen", side_effect=OSError("offline")):
                with self.assertRaises(OSError):
                    Asset.temp_asset_from_url("https://example.org/asset", "file.json")
            self.assertEqual(list(Path(cache).iterdir()), [])

    def test_android_conversion_signatures_and_lottie_preserves_original(self):
        classes = {}
        def jclass(name):
            return classes.setdefault(name, Mock(name=name))
        with tempfile.TemporaryDirectory() as cache, patch.dict(sys.modules, {"java": types.SimpleNamespace(jclass=jclass)}):
            path = Path(cache) / "asset.svg"
            path.write_text('<svg width="32" height="32"/>')
            asset = Asset.from_path(path)
            asset.to_bitmap_drawable(48, 64)
            bitmap = jclass("android.graphics.BitmapFactory").decodeFile.return_value
            jclass("android.graphics.Bitmap").createScaledBitmap.assert_called_once_with(bitmap, 48, 64, True)
            asset.to_svg_bitmap(16, 24, True)
            jclass("org.telegram.messenger.SvgHelper").getBitmap.assert_called_once_with(jclass("java.io.File").return_value, 16, 24, True)
            drawable = asset.to_svg_thumb(42, 0.5)
            drawable.setupGradient.assert_called_once_with(42, 0.5, False)
            asset.to_svg_drawable(16, 24)
            jclass("android.graphics.drawable.BitmapDrawable").assert_called()
            jclass("org.telegram.messenger.AndroidUtilities").dp.side_effect = lambda value: value * 2
            jclass("java.io.File").side_effect = lambda value: value
            def decode(file, json, width, height, options, limit, colors, fitz, single):
                self.assertNotEqual(file, str(path))
                self.assertEqual(Path(file).read_text(), path.read_text())
                self.assertEqual((width, height), (64, 96))
                Path(file).unlink()  # Simulate Telegram's invalid-input deletion.
                return "lottie"
            jclass("org.telegram.ui.Components.RLottieDrawable").side_effect = decode
            with patch("elyx._assets._cache_dir", return_value=cache):
                self.assertEqual(asset.to_lottie_drawable(32, 48), "lottie")
            self.assertTrue(path.exists())
            with self.assertRaises(ValueError):
                asset.to_bitmap_drawable(0, 32)


if __name__ == "__main__":
    unittest.main()
