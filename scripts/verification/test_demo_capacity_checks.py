"""Small local discovery/projection guards; no Docker, databases or network."""
import copy
from pathlib import Path
import tempfile
import unittest
import json
from unittest import mock

import deployment_fe_checks as frontend

import demo_capacity_checks as checks


class CapacityChecksTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='moneytoad-capacity-runner-test-')
        self.root = Path(self.temporary.name).resolve()
        self.tests = self.root / 'be/src/test/java'
        self.tests.mkdir(parents=True)

    def tearDown(self):
        self.temporary.cleanup()

    def java(self, package, name, body):
        path = self.tests / Path(package.replace('.', '/')) / (name + '.java')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('package ' + package + ';\nclass ' + name + ' {\n' + body + '\n}\n')

    def test_whole_classes_include_original_context_parameterized_and_future_tests(self):
        self.java('com.potg.don', 'DonApplicationTests', '@Test void contextLoads() {}')
        self.java('com.potg.don.demo.admission', 'DemoAdmissionTest', '@ParameterizedTest void each() {}')
        self.java('com.potg.don', 'FutureTest', '@org.junit.jupiter.api.Test void future() {}')
        self.java('com.potg.don', 'Helper', '@TestConfiguration void support() {}')
        for test in checks.RENDER_CLASSES:
            package, name = test.rsplit('.', 1)
            self.java(package, name, '@Test void renderOnly() {}')
        self.assertEqual(checks.discover_classes(self.root), [
            'com.potg.don.DonApplicationTests', 'com.potg.don.FutureTest',
            'com.potg.don.demo.admission.DemoAdmissionTest'])

    def test_focus_is_explicit_and_cannot_be_mistaken_for_complete_regression(self):
        self.java('com.potg.don', 'DonApplicationTests', '@Test void original() {}')
        self.java('com.potg.don.auth', 'DemoAdmissionHttpTest', '@Test void admission() {}')
        self.java('com.potg.don.demo.seed', 'DemoDatasetValidatorTest', '@Test void immutable() {}')
        self.assertEqual(checks.selected_classes(self.root, True), [
            'com.potg.don.auth.DemoAdmissionHttpTest', 'com.potg.don.demo.seed.DemoDatasetValidatorTest'])

    def test_missing_or_unclassifiable_tests_fail_discovery(self):
        with self.assertRaises(ValueError):
            checks.discover_classes(self.root)
        (self.tests / 'Broken.java').write_text('class Broken { @Test void missingPackage() {} }')
        with self.assertRaises(ValueError):
            checks.discover_classes(self.root)

    def report(self):
        return {'status': 'PASS', 'cleanup_complete': True, 'tests': 6, 'failures': 0, 'errors': 0, 'skipped': 0,
                'suites': [{'test': 'sample.OriginalTest', 'tests': 4, 'failures': 0, 'errors': 0, 'skipped': 0},
                           {'test': 'sample.NewTest', 'tests': 2, 'failures': 0, 'errors': 0, 'skipped': 0}]}

    def valid(self, report):
        return checks.validate_backend(report, ['sample.OriginalTest', 'sample.NewTest'], {'OriginalTest': 4})

    def test_old_count_remains_exact_and_every_new_selected_class_must_run(self):
        original = self.report()
        self.assertTrue(self.valid(original))
        for changed in (0, 3, 5):
            value = copy.deepcopy(original)
            value['suites'][0]['tests'] = changed
            value['tests'] = changed + 2
            self.assertFalse(self.valid(value))
        for altered in (original['suites'][:1], original['suites'] + [original['suites'][0]]):
            value = {**original, 'suites': altered}
            self.assertFalse(self.valid(value))

    def test_fail_skip_zero_cases_or_cleanup_uncertainty_never_pass(self):
        for key in ('failures', 'errors', 'skipped'):
            value = self.report()
            value['suites'][1][key] = 1
            self.assertFalse(self.valid(value))
        value = self.report()
        value['suites'][1]['tests'] = 0
        value['tests'] = 4
        self.assertFalse(self.valid(value))
        value = self.report()
        value['cleanup_complete'] = False
        self.assertFalse(self.valid(value))

    def test_gitless_copy_manifest_requires_owner_exact_content_and_safe_paths(self):
        (self.root / '.capacity-copy-owner').write_text('owned-test')
        source = self.root / 'fe/src/probe.ts'
        source.parent.mkdir(parents=True)
        source.write_text('export const synthetic = true;')
        manifest = self.root / '.capacity-copy-inputs.json'
        good = {'owner': 'owned-test', 'files': {'fe/src/probe.ts': checks.digest(source)}}
        manifest.write_text(json.dumps(good))
        with mock.patch.object(frontend, 'ROOT', self.root):
            self.assertEqual(frontend.source_inputs(manifest), [Path('fe/src/probe.ts')])
            for changed in ({**good, 'owner': 'other'},
                            {**good, 'files': {'fe/src/probe.ts': 'wrong-digest'}},
                            {**good, 'files': {'../outside': 'wrong-digest'}},
                            {**good, 'files': {}},
                            {**good, 'extra': True}):
                manifest.write_text(json.dumps(changed))
                with self.assertRaises(ValueError):
                    frontend.source_inputs(manifest)

    def test_failure_projection_keeps_source_locations_but_never_actual_values(self):
        import a1_budget_ownership as shared
        from public_evidence import backend, forbidden
        xml = ('<testsuite name="com.potg.don.SyntheticTest" tests="1" failures="1" errors="0" skipped="0">'
               '<testcase name="safeFailure()" time="0"><failure type="java.lang.IllegalStateException" '
               'message="CREDENTIAL_FILE_REJECTED private-canary-value">java.sql.SQLException: private-canary-value\n'
               'at com.potg.don.maintenance.DemoCleanupService.execute(DemoCleanupService.java:52)\n'
               'DEMO_ADMISSION_UNAVAILABLE</failure></testcase></testsuite>')
        (self.root / 'TEST-Synthetic.xml').write_text(xml)
        suites, _ = shared.summarize_xml(self.root)
        projected = backend({'suites': suites, 'status': 'FAIL', 'exit_code': 1, 'cleanup_complete': True})
        self.assertNotIn('private-canary-value', json.dumps(projected))
        self.assertFalse(forbidden(projected))
        case = projected['suites'][0]['cases'][0]
        self.assertEqual(case['failure_sources'], [{'source': 'DemoCleanupService.java', 'line': 52}])
        self.assertEqual(case['cause_classes'], ['java.sql.SQLException'])
        self.assertEqual(case['failure_codes'], ['CREDENTIAL_FILE_REJECTED', 'DEMO_ADMISSION_UNAVAILABLE'])

    def test_report_boundary_rejects_forbidden_values_instead_of_printing_them(self):
        path = self.root / 'result.json'
        path.write_text('{"status":"PASS","accessToken":"synthetic-canary"}')
        with self.assertRaisesRegex(ValueError, 'EVIDENCE_PROJECTION_REJECTED'):
            checks.one_report([path])
        path.unlink()
        with self.assertRaises(OSError):
            checks.one_report([path])


if __name__ == '__main__':
    unittest.main()
