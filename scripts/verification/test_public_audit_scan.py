import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import public_audit_scan as runner


class PublicAuditScanTest(unittest.TestCase):
    def execute(self, root, names, label='fresh'):
        args = ['public_audit_scan.py', '--run-label', label]
        with patch.object(runner, 'ROOT', root), patch('sys.argv', args), \
                patch.object(runner.subprocess, 'check_output', return_value=names), \
                contextlib.redirect_stdout(io.StringIO()):
            return runner.main()

    def test_complete_tracked_and_untracked_candidates_and_cleanup(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'tracked.md').write_text('Reviewed source documentation.\n')
            (root / 'new.md').write_text('Reviewed added documentation.\n')
            code = self.execute(root, b'tracked.md\0new.md\0')
            result = json.loads((root / 'docs/deployment/evidence/PUBLIC_SCANNER_AUDIT/fresh/summary.json').read_text())
            self.assertEqual(code, 0)
            self.assertEqual(result['files_scanned'], 2)
            self.assertTrue(result['owned_copy_removed'])
            self.assertTrue(result['source_preserved'])

    def test_actual_env_candidate_is_not_silently_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / '.env').write_text('UNRELATED_FLAG=true\n')
            self.assertEqual(self.execute(root, b'.env\0'), 1)
            result = json.loads((root / 'docs/deployment/evidence/PUBLIC_SCANNER_AUDIT/fresh/summary.json').read_text())
            self.assertTrue(any(row['category'] == 'excluded-artifact' for row in result['unresolved']))

    def test_prior_evidence_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / 'docs/deployment/evidence/PUBLIC_SCANNER_AUDIT/fresh'
            output.mkdir(parents=True)
            sentinel = output / 'summary.json'
            sentinel.write_text('{}\n')
            with self.assertRaisesRegex(ValueError, 'EVIDENCE_ALREADY_EXISTS'):
                self.execute(root, b'')
            self.assertEqual(sentinel.read_text(), '{}\n')

    def test_candidate_set_change_fails_closed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'reviewed.md').write_text('Reviewed.\n')
            with patch.object(runner, 'ROOT', root), \
                    patch('sys.argv', ['public_audit_scan.py', '--run-label', 'fresh']), \
                    patch.object(runner.subprocess, 'check_output', side_effect=[b'reviewed.md\0', b'reviewed.md\0new.md\0']):
                with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_SET_CHANGED'):
                    runner.main()
            self.assertFalse((root / 'docs/deployment/evidence/PUBLIC_SCANNER_AUDIT/fresh').exists())

    def test_linked_candidate_parent_is_rejected_before_copy(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            external = Path(outside)
            (external / 'source.md').write_text('Must not be copied.\n')
            (root / 'linked').symlink_to(external, target_is_directory=True)
            with patch.object(runner.shutil, 'copy2') as copier:
                with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_LINK_REJECTED'):
                    self.execute(root, b'linked/source.md\0')
                copier.assert_not_called()

    def test_linked_evidence_parent_cannot_write_outside_repository(self):
        with tempfile.TemporaryDirectory() as directory, tempfile.TemporaryDirectory() as outside:
            root = Path(directory)
            external = Path(outside)
            (root / 'docs').symlink_to(external, target_is_directory=True)
            with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_LINK_REJECTED'):
                self.execute(root, b'')
            self.assertEqual(list(external.iterdir()), [])

    def test_empty_candidate_set_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_SET_EMPTY'):
                self.execute(Path(directory), b'')

    def test_copy_missing_or_changed_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'reviewed.md').write_text('Reviewed.\n')
            with patch.object(runner.shutil, 'copy2'):
                with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_COPY_MISMATCH'):
                    self.execute(root, b'reviewed.md\0')

    def test_scan_count_mismatch_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'reviewed.md').write_text('Reviewed.\n')
            with patch.object(runner, 'scan', return_value={'status': 'PASS', 'files_scanned': 0}):
                with self.assertRaisesRegex(ValueError, 'PUBLIC_CANDIDATE_SCAN_COUNT_MISMATCH'):
                    self.execute(root, b'reviewed.md\0')

    def test_source_content_change_during_scan_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            path = root / 'reviewed.md'
            path.write_text('Reviewed.\n')
            scan = runner.scan
            def changing_scan(snapshot):
                result = scan(snapshot)
                path.write_text('Changed.\n')
                return result
            with patch.object(runner, 'scan', side_effect=changing_scan):
                with self.assertRaisesRegex(ValueError, 'SOURCE_CHANGED_DURING_SCAN'):
                    self.execute(root, b'reviewed.md\0')
