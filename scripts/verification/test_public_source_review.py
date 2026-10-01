"""Mutation tests for manually reviewed source syntax, using runtime canaries only."""
import copy
import hashlib
import json
from pathlib import Path
import secrets
import tempfile
import unittest

from public_scan import scan, sites, SITES
from public_source_review import construct, FIXED_DDL, REVIEW_FILE, semantic_ok


class StrictSourceReviewTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.relative = 'fixture.py'
        field = 'password'
        self.text = f'{field} = secrets.token_hex(16)\n'
        self.write(self.relative, self.text)

    def write(self, relative, text):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def row(self, relative=None, text=None, semantic='runtime-credential-expression', category=None):
        relative, text = relative or self.relative, text or self.text
        number, found, digest = next(sites(text)) if category is None else (
            1, category, hashlib.sha256(text.splitlines()[0].encode()).hexdigest())
        return {'file': relative, 'line': number, 'category': found,
                'classification': 'B_NON_SECRET_SOURCE_PATTERN',
                'file_digest': hashlib.sha256(text.encode()).hexdigest(), 'content_digest': digest,
                'semantic_category': semantic, 'expected_construct': construct(text.splitlines()[number - 1]),
                'occurrences': 1}

    def reviews(self, rows):
        self.write(REVIEW_FILE, json.dumps({'version': 2, 'sites': rows}))

    def assert_fail(self):
        result = scan(self.root)
        self.assertEqual(result['status'], 'FAIL')
        return result

    def test_exact_runtime_expression_passes(self):
        self.reviews([self.row()])
        self.assertEqual(scan(self.root)['status'], 'PASS')

    def test_context_mutation_invalidates_even_unchanged_candidate(self):
        self.reviews([self.row()])
        self.write(self.relative, self.text + 'other = 1\n')
        self.assert_fail()

    def test_line_number_is_diagnostic_not_authorization(self):
        row = self.row()
        row['line'] = 700
        self.reviews([row])
        self.assertEqual(scan(self.root)['status'], 'PASS')
        self.write(self.relative, '\n' + self.text)
        self.assert_fail()

    def test_missing_file_and_removed_candidate_are_stale(self):
        self.reviews([self.row()])
        (self.root / self.relative).unlink()
        self.assert_fail()
        self.write(self.relative, 'safe = 1\n')
        self.assert_fail()

    def test_duplicate_review_and_added_candidate_are_rejected(self):
        row = self.row()
        self.reviews([row, copy.deepcopy(row)])
        self.assert_fail()
        self.reviews([row])
        self.write(self.relative, self.text * 2)
        self.assert_fail()

    def test_unknown_rules_semantics_and_non_b_classifications_fail(self):
        for field, value in [('category', 'anything'), ('semantic_category', 'anything'),
                             ('classification', 'C_TEST_FIXTURE')]:
            with self.subTest(field=field):
                row = self.row()
                row[field] = value
                self.reviews([row])
                self.assert_fail()

    def test_v1_and_line_only_review_are_not_supported(self):
        self.write(REVIEW_FILE, json.dumps({'sites': [self.row()]}))
        self.assert_fail()
        row = self.row()
        del row['file_digest']
        self.reviews([row])
        self.assert_fail()

    def test_paths_cannot_be_wildcard_absolute_or_parent_traversal(self):
        for relative in ('*.py', '../fixture.py', '/fixture.py', 'a/../fixture.py', 'fe/functions/api/[ab].ts'):
            row = self.row()
            row['file'] = relative
            self.reviews([row])
            self.assert_fail()

    def test_literal_pages_route_brackets_are_not_a_wildcard_review(self):
        (self.root / self.relative).unlink()
        relative = 'fe/functions/api/[[path]].ts'
        self.write(relative, self.text)
        self.reviews([self.row(relative)])
        self.assertEqual(scan(self.root)['status'], 'PASS')

    def test_rebound_literal_credential_is_not_runtime_source(self):
        field = 'password'
        canary = secrets.token_hex(16)
        text = f'{field} = {json.dumps(canary)}\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text)])
        result = self.assert_fail()
        self.assertNotIn(canary, json.dumps(result))

    def test_runtime_word_inside_literal_does_not_qualify(self):
        field = 'password'
        text = f'{field} = "secrets.token_hex(16)"\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text)])
        self.assert_fail()

    def test_assignment_embedded_in_literal_is_not_runtime_source(self):
        field = 'password'
        text = f'text = "{field} = {secrets.token_hex(12)}"\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text)])
        self.assert_fail()

    def test_arbitrary_call_and_config_fallback_do_not_hide_literals(self):
        field = 'password'
        canary = secrets.token_hex(12)
        for expression in (f'decode("{canary}")', f'environment.getProperty("APP_KEY", "{canary}")'):
            text = f'{field} = {expression};\n'
            self.write(self.relative, text)
            self.reviews([self.row(text=text)])
            self.assert_fail()

    def test_header_interpolation_cannot_cover_literal_suffix(self):
        token_kind = 'Bearer'
        cookie_name = 'demoRefreshToken'
        suffix = secrets.token_hex(12)
        for text, category, semantic in (
            (f'value = `{token_kind} ${{token}}{suffix}`;', 'bearer-marker', 'runtime-request-header'),
            (f'headers: {{ {"Authorization"}: `{token_kind} ${{token}}{suffix}` }}', 'authorization-marker', 'runtime-request-header'),
            (f'value = `{cookie_name}=${{token}}{suffix}`;', 'cookie-assignment', 'cookie-name-boundary'),
            (f'value = `{token_kind} ${{token}}` + "{suffix}";', 'bearer-marker', 'runtime-request-header'),
        ):
            self.assertFalse(semantic_ok(semantic, category, text, 'fixture.ts', SITES[category]))

    def test_source_review_regex_cannot_authorize_arbitrary_regex_value(self):
        field = 'demoRefreshToken'
        text = f'if re.match(r"{field}={secrets.token_hex(12)}", text):'
        self.assertFalse(semantic_ok('source-review-regex', 'cookie-assignment', text,
                                    'scripts/verification/public_source_review.py', SITES['cookie-assignment']))

    def test_runtime_identity_expression_cannot_include_literal_suffix(self):
        field = 'sid'
        text = f'value = {{"{field}": secrets.token_hex(16) + "{secrets.token_hex(12)}"}}'
        self.assertFalse(semantic_ok('runtime-negative-identity', 'identity-field', text,
                                    'fixture.py', SITES['identity-field']))

    def test_cookie_prefix_runtime_value_cannot_include_literal_suffix(self):
        field = 'demoRefreshToken'
        suffix = secrets.token_hex(12)
        for expression in ('token', 'crypto.randomUUID()', 'secrets.token_hex(12)'):
            text = f'value = "{field}=" + {expression} + "{suffix}";'
            self.assertFalse(semantic_ok('cookie-name-boundary', 'cookie-assignment', text,
                                        'fixture.ts', SITES['cookie-assignment']))

    def test_cookie_boundary_rejects_extra_literal_prefix(self):
        field = 'demoRefreshToken'
        text = f'value = "{secrets.token_hex(12)}-{field}=" + token;'
        self.assertFalse(semantic_ok('cookie-name-boundary', 'cookie-assignment', text,
                                    'fixture.ts', SITES['cookie-assignment']))

    def test_detector_literal_rejects_extra_prefix_alternative(self):
        label = 'Authorization'
        text = f'found = re.search(r"({secrets.token_hex(12)}|{label}:|cookie:)", source)'
        self.assertFalse(semantic_ok('negative-detection-pattern', 'authorization-marker', text,
                                    'fixture.py', SITES['authorization-marker']))

    def test_request_header_template_rejects_extra_literal_prefix(self):
        kind = 'Bearer'
        text = f'value = `{secrets.token_hex(12)} {kind} ${{token}}`;'
        self.assertFalse(semantic_ok('runtime-request-header', 'bearer-marker', text,
                                    'fixture.ts', SITES['bearer-marker']))

    def test_unquoted_environment_values_never_qualify_as_source_expressions(self):
        relative = 'runtime.env.example'
        field = 'password'
        text = f'{field}={secrets.token_hex(12)}\n'
        (self.root / self.relative).unlink()
        self.write(relative, text)
        self.reviews([self.row(relative, text)])
        self.assert_fail()

    def test_safe_and_literal_assignment_on_same_line_is_rejected(self):
        field = 'other_password'
        text = self.text.rstrip() + f'; {field} = "{secrets.token_hex(12)}"\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text)])
        self.assert_fail()

    def test_evidence_cannot_be_reviewed_in_any_format_or_namespace(self):
        for relative in ('docs/deployment/evidence/item.md', 'docs/portfolio/evidence/item.txt',
                         'docs/deployment/evidence/item.data', 'docs/portfolio/evidence/item.json'):
            with self.subTest(relative=relative):
                text = 'const address = "sample' + '@' + 'example.invalid";\n'
                self.write(relative, text)
                self.reviews([self.row(relative, text, 'reserved-invalid-domain')])
                self.assert_fail()
                (self.root / relative).unlink()

    def test_ordinary_evidence_text_detects_value_without_review(self):
        (self.root / self.relative).unlink()
        self.write('docs/deployment/evidence/item.txt', 'sample' + '@' + 'example.invalid')
        self.assert_fail()

    def test_invalid_utf8_cannot_hide_text_evidence_values(self):
        (self.root / self.relative).unlink()
        field = 'demoRefreshToken'
        for suffix in ('txt', 'data', 'png'):
            relative = f'docs/deployment/evidence/item.{suffix}'
            self.write(relative, '')
            (self.root / relative).write_bytes(b'\xff' + f'{field}={secrets.token_hex(12)}'.encode())
            result = self.assert_fail()
            self.assertTrue(any(item['category'] == 'unreadable-text-evidence' for item in result['unresolved']))
            (self.root / relative).unlink()

    def test_reserved_domain_predicate_rejects_real_domain_even_rebound(self):
        text = 'const address = "sample' + '@' + 'example.com";\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text, semantic='reserved-invalid-domain')])
        self.assert_fail()

    def test_candidate_must_itself_be_the_regex_detector(self):
        field = 'demoRefreshToken'
        canary = secrets.token_hex(12)
        text = f'value = "{field}={canary}"; found = re.search(r"\\S", source)\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text, semantic='negative-detection-pattern')])
        self.assert_fail()

    def test_exact_cookie_detector_passes_and_appended_value_does_not(self):
        field = 'demoRefreshToken'
        text = f'pattern = re.compile(r"{field}=\\S+")\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text, semantic='negative-detection-pattern')])
        self.assertEqual(scan(self.root)['status'], 'PASS')
        changed = text.rstrip() + f'; value = "{field}={secrets.token_hex(12)}"\n'
        self.write(self.relative, changed)
        self.reviews([self.row(text=changed, semantic='negative-detection-pattern')])
        self.assert_fail()

    def test_sql_selector_does_not_cover_neighbouring_identity_value(self):
        field = 'user_id'
        text = f'String column = table.equals("users") ? "{field}" : "id";'
        self.assertTrue(semantic_ok('sql-column-selector', 'identity-field', text, 'Fixture.java', SITES['identity-field']))
        text += f' String extra = "{field}": "{secrets.token_hex(12)}";'
        self.assertFalse(semantic_ok('sql-column-selector', 'identity-field', text, 'Fixture.java', SITES['identity-field']))

    def test_fixed_singleton_ddl_requires_exact_file_statement_and_digest(self):
        (self.root / self.relative).unlink()
        relative, statement = next(iter(FIXED_DDL.items()))
        self.write(relative, statement + '\n')
        self.reviews([self.row(relative, statement + '\n', 'fixed-singleton-ddl', 'data-dump-candidate')])
        self.assertEqual(scan(self.root)['status'], 'PASS')
        self.write(relative, statement + '\n' + statement + '\n')
        self.assert_fail()
        changed = statement.replace('1000', '1001') + '\n'
        self.write(relative, changed)
        self.reviews([self.row(relative, changed, 'fixed-singleton-ddl', 'data-dump-candidate')])
        self.assert_fail()

    def test_fixed_ddl_cannot_approve_another_path(self):
        (self.root / self.relative).unlink()
        statement = next(iter(FIXED_DDL.values())) + '\n'
        relative = 'other.sql'
        self.write(relative, statement)
        self.reviews([self.row(relative, statement, 'fixed-singleton-ddl', 'data-dump-candidate')])
        self.assert_fail()

    def test_fresh_ddl_review_cannot_hide_adjacent_literal_values(self):
        (self.root / self.relative).unlink()
        relative, statement = next(iter(FIXED_DDL.items()))
        field = 'password'
        cookie_name = 'demoRefreshToken'
        canary = secrets.token_hex(12)
        cases = ((f'-- {field} = "{canary}"', 'credential-assignment'),
                 ('-- ' + canary + '@' + 'example.com', 'email-marker'),
                 (f'-- {cookie_name}={canary}', 'cookie-assignment'))
        for adjacent, category in cases:
            text = statement + '\n' + adjacent + '\n'
            self.write(relative, text)
            self.reviews([self.row(relative, text, 'fixed-singleton-ddl', 'data-dump-candidate')])
            result = self.assert_fail()
            self.assertTrue(any(item['category'] == category for item in result['unresolved']))
            self.assertNotIn(canary, json.dumps(result))

    def test_hard_marker_cannot_hide_next_to_a_valid_review(self):
        text = self.text + '# -----BEGIN ' + 'PRIVATE KEY-----\n'
        self.write(self.relative, text)
        self.reviews([self.row(text=text)])
        self.assert_fail()

    def test_expected_shape_and_occurrences_are_independent_checks(self):
        for field, value in [('expected_construct', ['anything']), ('occurrences', 2)]:
            row = self.row()
            row[field] = value
            self.reviews([row])
            self.assert_fail()


if __name__ == '__main__':
    unittest.main()
