import json
import hashlib
import secrets
from pathlib import Path
import tempfile
import unittest
from public_scan import scan, sites
from public_source_review import construct


def review_rows(relative, text, semantic):
    return [{'file': relative, 'line': number, 'category': category,
             'content_digest': digest, 'file_digest': hashlib.sha256(text.encode()).hexdigest(),
             'classification': 'B_NON_SECRET_SOURCE_PATTERN', 'semantic_category': semantic,
             'expected_construct': construct(text.splitlines()[number - 1]),
             'occurrences': sum(line == text.splitlines()[number - 1] for line in text.splitlines())}
            for number, category, digest in sites(text)]


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
            rows = review_rows('test.ts', text, 'reserved-invalid-domain')
            self.fixture(root, 'scripts/verification/fixtures/scan-classifications.json', json.dumps({'version': 2, 'sites': rows}))
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
            canary = 1_000_000_000 + secrets.randbelow(1_000_000_000)
            self.fixture(root, 'docs/portfolio/evidence/result.json', json.dumps({'userId': canary}))
            result = scan(root)
            self.assertEqual(result['status'], 'FAIL')
            self.assertNotIn(str(canary), json.dumps(result))

    def test_review_does_not_allow_identity_dump_in_arbitrary_json(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            text = json.dumps({'sid': secrets.token_hex(12)})
            self.fixture(root, 'dump.json', text)
            rows = review_rows('dump.json', text, 'runtime-negative-identity')
            self.fixture(root, 'scripts/verification/fixtures/scan-classifications.json', json.dumps({'version': 2, 'sites': rows}))
            self.assertEqual(scan(root)['status'], 'FAIL')

    def test_root_ignore_does_not_exclude_public_formats(self):
        root = Path(__file__).resolve().parents[2]
        rules = set((root / '.gitignore').read_text().splitlines())
        self.assertTrue({'.env', '.env.*', '!.env.example', '*.pem', '*.key', 'node_modules/'} <= rules)
        self.assertFalse({'*.json', '*.png', '*.sql'} & rules)


if __name__ == '__main__':
    unittest.main()
