"""
Tray Icon Tests
===============

Unit tests for the bundled brand icon and dynamic quota gauge renderer.
"""
from __future__ import annotations

import unittest
from unittest.mock import patch

from PIL import Image

import usage_monitor_for_codex.tray_icon as tray_icon_mod


class TestLoadTrayIcon(unittest.TestCase):
    """Tests for load_tray_icon()."""

    def test_returns_rgba_image(self):
        img = tray_icon_mod.load_tray_icon()

        self.assertIsInstance(img, Image.Image)
        self.assertEqual(img.mode, 'RGBA')

    def test_is_non_empty_square(self):
        img = tray_icon_mod.load_tray_icon()
        width, height = img.size

        self.assertEqual(width, height)
        self.assertGreater(width, 0)

    def test_has_opaque_pixels(self):
        img = tray_icon_mod.load_tray_icon()
        _min_alpha, max_alpha = img.getchannel('A').getextrema()

        self.assertGreater(max_alpha, 0)

    def test_asset_file_bundled(self):
        self.assertTrue(tray_icon_mod._ICON_PATH.is_file())


class TestRenderUsageTrayIcon(unittest.TestCase):
    """Tests for render_usage_tray_icon()."""

    def test_no_usage_falls_back_to_brand_icon(self):
        sentinel = Image.new('RGBA', (32, 32), (1, 2, 3, 255))
        with patch.object(tray_icon_mod, 'load_tray_icon', return_value=sentinel) as load:
            result = tray_icon_mod.render_usage_tray_icon({'error': 'offline'})

        self.assertIs(result, sentinel)
        load.assert_called_once_with()

    def test_usage_renders_fixed_size_rgba_gauge(self):
        result = tray_icon_mod.render_usage_tray_icon({
            'five_hour': {'utilization': 50},
            'seven_day': {'utilization': 75},
        })

        self.assertEqual(result.mode, 'RGBA')
        self.assertEqual(result.size, (64, 64))
        self.assertGreater(result.getchannel('A').getextrema()[1], 0)

    def test_single_window_still_renders_gauge(self):
        result = tray_icon_mod.render_usage_tray_icon({
            'five_hour': {'utilization': 25},
        })

        self.assertEqual(result.size, (64, 64))
        self.assertGreater(result.getchannel('A').getextrema()[1], 0)

    def test_warning_usage_uses_warning_color(self):
        with patch.object(tray_icon_mod, 'BAR_FG_WARN', '#ff0000'):
            result = tray_icon_mod.render_usage_tray_icon({
                'five_hour': {'utilization': 100},
            })

        pixels = set(result.getdata())
        self.assertIn((255, 0, 0, 255), pixels)


if __name__ == '__main__':
    unittest.main()
