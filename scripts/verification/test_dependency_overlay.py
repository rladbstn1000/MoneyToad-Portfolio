"""Offline ownership/cache isolation tests using only temporary synthetic packages."""
from pathlib import Path
import tempfile
import unittest

from dependency_overlay import DependencyOverlay


class DependencyOverlayTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='moneytoad-overlay-unit-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.frontend, self.dependencies = self.root / 'frontend', self.root / 'dependencies'
        for directory in (self.frontend, self.dependencies):
            directory.mkdir()
            for name in ('package.json', 'package-lock.json'):
                (directory / name).write_text('{}\n')
        self.modules = self.dependencies / 'node_modules'
        (self.modules / 'package').mkdir(parents=True)
        (self.modules / 'package/index.js').write_text('export default 1;\n')
        (self.modules / '.bin').mkdir()
        (self.modules / '.bin/tool').symlink_to('../package/index.js')
        (self.modules / '@scope/library').mkdir(parents=True)
        (self.modules / '@scope/library/index.js').write_text('export default 2;\n')

    def overlay(self):
        return DependencyOverlay(self.frontend, self.dependencies)

    def test_mutable_caches_remain_private_and_packages_read_unchanged(self):
        overlay = self.overlay().create()
        for directory in ('.tmp', '.vite', '.vite-temp'):
            target = self.frontend / 'node_modules' / directory
            target.mkdir()
            (target / 'cache').write_text('private cache\n')
            self.assertFalse((self.modules / directory).exists())
        self.assertEqual((self.frontend / 'node_modules/.bin/tool').read_text(), 'export default 1;\n')
        self.assertEqual((self.frontend / 'node_modules/@scope/library/index.js').read_text(), 'export default 2;\n')
        overlay.close()
        self.assertTrue(all(overlay.report.values()))
        self.assertFalse((self.frontend / 'node_modules').exists())

    def test_mismatched_lock_refused_without_target_creation(self):
        (self.frontend / 'package-lock.json').write_text('{"changed":true}\n')
        with self.assertRaisesRegex(ValueError, 'MANIFEST_MISMATCH'):
            self.overlay().create()
        self.assertFalse((self.frontend / 'node_modules').exists())

    def test_existing_target_preserved(self):
        (self.frontend / 'node_modules').mkdir()
        marker = self.frontend / 'node_modules/existing'
        marker.write_text('keep')
        with self.assertRaises(ValueError):
            self.overlay().create()
        self.assertEqual(marker.read_text(), 'keep')

    def test_package_link_outside_installation_refused_and_partial_overlay_cleaned(self):
        foreign = self.root / 'foreign'
        foreign.mkdir()
        (self.modules / 'outside').symlink_to(foreign, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'OUTSIDE_INSTALLATION'):
            self.overlay().create()
        self.assertFalse((self.frontend / 'node_modules').exists())
        self.assertTrue(foreign.is_dir())

    def test_changed_source_dependency_is_not_reported_preserved(self):
        overlay = self.overlay().create()
        (self.modules / 'package/index.js').write_text('changed synthetic package\n')
        with self.assertRaisesRegex(ValueError, 'PRESERVATION_FAILED'):
            overlay.close()
        self.assertFalse(overlay.report['dependencies_preserved'])
        self.assertTrue(overlay.report['overlay_removed'])

    def test_missing_owner_marker_refuses_cleanup(self):
        overlay = self.overlay().create()
        overlay.marker.unlink()
        with self.assertRaisesRegex(ValueError, 'OWNERSHIP_UNCONFIRMED'):
            overlay.close()
        self.assertTrue(overlay.target.is_dir())
        self.assertFalse(overlay.report['overlay_removed'])

    def test_successful_close_is_idempotent(self):
        overlay = self.overlay().create()
        overlay.close()
        overlay.close()
        self.assertTrue(all(overlay.report.values()))


if __name__ == '__main__':
    unittest.main()
