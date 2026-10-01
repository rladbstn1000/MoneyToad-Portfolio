"""Guard exact local-harness adaptation; no Docker, database or network access."""
import ast
import re
import unittest

import managed_schema_local as adapter


class ManagedSchemaLocalAdapterTest(unittest.TestCase):
    def setUp(self):
        self.source = (adapter.ROOT / adapter.RUNNER).read_text()

    def test_only_reviewed_three_replacements(self):
        result = adapter.adapt_runner(self.source)
        ast.parse(result)
        addition = ("        test_env['RUNNER_MANAGED_SCHEMA_DDL'] = str(ROOT / "
                    + repr(adapter.DDL.as_posix()) + ")\n")
        restored = result.replace(adapter.NEW_CLASS, adapter.OLD_CLASS).replace(addition, '')
        restored = restored.replace(adapter.MANAGED_DIAGNOSTIC + '|', '')
        self.assertEqual(restored, self.source)

    def test_missing_or_duplicated_anchor_refused(self):
        for anchor in (adapter.OLD_CLASS, adapter.PREPARATION, adapter.DIAGNOSTIC):
            for broken in (self.source.replace(anchor, ''), self.source + '\n' + anchor):
                with self.subTest(anchor=anchor, count=broken.count(anchor)):
                    with self.assertRaises(ValueError):
                        adapter.adapt_runner(broken)

    def test_reapplying_adaptation_refused(self):
        with self.assertRaises(ValueError):
            adapter.adapt_runner(adapter.adapt_runner(self.source))

    def test_failure_projection_accepts_fixed_marker_only(self):
        marker = 'MANAGED_SCHEMA_LOCAL_PREPARATION_FAIL: APP_SESSION_TIMEZONE'
        matches = re.findall(adapter.MANAGED_DIAGNOSTIC, marker + '\nprivate payload must be discarded')
        self.assertEqual(matches, [marker])

    def test_original_runner_is_not_modified(self):
        adapter.adapt_runner(self.source)
        self.assertEqual((adapter.ROOT / adapter.RUNNER).read_text(), self.source)


if __name__ == '__main__':
    unittest.main()
