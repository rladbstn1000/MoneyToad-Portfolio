import json
from pathlib import Path
import tempfile
import unittest
from public_scan import scan, sites


class PublicScannerTest(unittest.TestCase):
    def fixture(self, root, relative, text):
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
        return path

    def test_unclassified_source_candidate_fails_and_review_is_content_bound(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = 'const fixture = "synthetic' + '@' + 'example.invalid";\n'
            file = self.fixture(root, 'test.ts', text)
            self.assertEqual(scan(root)['status'], 'FAIL')
            rows = [{'file': 'test.ts', 'line': n, 'category': category, 'content_digest': digest,
                     'classification': 'synthetic negative-test fixture'} for n, category, digest in sites(text)]
            self.fixture(root, 'scripts/verification/fixtures/scan-classifications.json', json.dumps({'sites': rows}))
            self.assertEqual(scan(root)['status'], 'PASS')
            file.write_text(text.replace('synthetic', 'changed'))
            self.assertEqual(scan(root)['status'], 'FAIL')

    def test_private_marker_cannot_be_exempted_as_reviewed_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, 'test.ts', '-----BEGIN ' + 'PRIVATE KEY-----')
            self.assertEqual(scan(root)['status'], 'FAIL')

    def test_actual_identity_in_evidence_fails_without_echoing_value(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root, 'docs/portfolio/evidence/result.json', json.dumps({'userId': 987654321}))
            result = scan(root)
            self.assertEqual(result['status'], 'FAIL')
            self.assertNotIn(str(987654321), json.dumps(result))

    def test_review_does_not_allow_identity_dump_in_arbitrary_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = json.dumps({'sid': 'not-issued'})
            self.fixture(root, 'dump.json', text)
            rows = [{'file': 'dump.json', 'line': n, 'category': c, 'content_digest': d,
                     'classification': 'not sufficient'} for n, c, d in sites(text)]
            self.fixture(root, 'scripts/verification/fixtures/scan-classifications.json', json.dumps({'sites': rows}))
            self.assertEqual(scan(root)['status'], 'FAIL')

    def test_root_ignore_does_not_exclude_public_formats(self):
        root = Path(__file__).resolve().parents[2]
        rules = set((root / '.gitignore').read_text().splitlines())
        self.assertTrue({'.env', '.env.*', '!.env.example', '*.pem', '*.key', 'node_modules/'} <= rules)
        self.assertFalse({'*.json', '*.png', '*.sql'} & rules)


if __name__ == '__main__':
    unittest.main()
