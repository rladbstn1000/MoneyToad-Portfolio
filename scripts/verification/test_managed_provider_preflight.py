"""Offline safety contracts. All inputs are synthetic; no network or provider access."""
import contextlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import managed_provider_preflight as gate
from public_evidence import forbidden


def synthetic_values():
    values = dict.fromkeys(gate.REQUIRED, ''.join(('synthetic', '-only-value')))
    values.update(TIDB_HOST='database.example.com', TIDB_PORT='4000',
                  REDIS_HOST='redis.example.com', REDIS_PORT='6379', REDIS_SSL_ENABLED='true')
    return values


def encoded(values):
    return '\n'.join(key + '=' + value for key, value in sorted(values.items())) + '\n'


class ManagedProviderPreflightTest(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory(prefix='managed-input-unit-')
        # Resolve the platform temp-directory alias, not the credential path under test.
        self.directory = Path(self.work.name).resolve()
        self.directory.chmod(0o700)
        self.path = self.directory / 'input.env'
        self.path.write_text(encoded(synthetic_values()))
        self.path.chmod(0o600)

    def tearDown(self):
        self.work.cleanup()

    def test_safe_read_has_no_network_and_redacted_representation(self):
        with patch('socket.create_connection', side_effect=AssertionError('Network forbidden')):
            loaded = gate.read_inputs(self.path)
        self.assertEqual(loaded.values, synthetic_values())
        self.assertEqual(repr(loaded), 'ProviderInputs(<redacted>)')

    def test_missing_file_is_blocked_without_side_effect(self):
        self.path.unlink()
        result = gate.assess(self.path)
        self.assertEqual(result['reason'], 'CONNECTION_FILE_MISSING')
        self.assertEqual(result['provider_connections_attempted'], 0)
        self.assertEqual(list(self.directory.iterdir()), [])

    def test_valid_file_is_not_provider_pass(self):
        result = gate.assess(self.path)
        self.assertEqual(result['input_check'], 'PASS')
        self.assertEqual(result['status'], 'BLOCKED')
        self.assertFalse(result['managed_provider_contracts_verified'])

    def test_final_symlink_rejected(self):
        other = self.directory / 'other'
        self.path.rename(other)
        self.path.symlink_to(other)
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_parent_symlink_rejected(self):
        alias = self.directory / 'alias'
        nested = self.directory / 'nested'
        nested.mkdir(mode=0o700)
        self.path.rename(nested / 'input.env')
        alias.symlink_to(nested, target_is_directory=True)
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(alias / 'input.env')

    def test_ancestor_symlink_rejected(self):
        nested = self.directory / 'real' / 'nested'
        nested.mkdir(parents=True, mode=0o700)
        self.path.rename(nested / 'input.env')
        alias = self.directory / 'alias'
        alias.symlink_to(nested.parent, target_is_directory=True)
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(alias / 'nested/input.env')

    def test_hardlink_rejected(self):
        os.link(self.path, self.directory / 'alias')
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_fifo_rejected_without_blocking(self):
        self.path.unlink()
        os.mkfifo(self.path, 0o600)
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_non_private_file_modes_rejected(self):
        for mode in (0o644, 0o640, 0o400, 0o700):
            with self.subTest(mode=mode):
                self.path.chmod(mode)
                with self.assertRaises(gate.InputRejected):
                    gate.read_inputs(self.path)

    def test_non_private_directory_mode_rejected(self):
        self.directory.chmod(0o755)
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_wrong_owner_rejected(self):
        with patch.object(gate.os, 'getuid', return_value=os.getuid() + 1):
            with self.assertRaises(gate.InputRejected):
                gate.read_inputs(self.path)

    def test_oversized_file_rejected(self):
        self.path.write_bytes(b'#' * (gate.MAX_BYTES + 1))
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_invalid_encoding_rejected(self):
        self.path.write_bytes(b'\xff')
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.path)

    def test_unknown_keys_not_propagated_or_executed(self):
        text = encoded(synthetic_values()) + 'UNRELATED=$(never-run)\n'
        self.assertEqual(gate.parse_values(text).values, synthetic_values())

    def test_duplicates_missing_empty_or_placeholder_rejected(self):
        original = synthetic_values()
        cases = [encoded(original) + 'REDIS_PORT=6379\n',
                 encoded({k: v for k, v in original.items() if k != 'REDIS_PORT'})]
        for bad in ('', '<console-host>', '$(command)', '`command`', '${EXTERNAL}'):
            cases.append(encoded({**original, 'TIDB_HOST': bad}))
        for text in cases:
            with self.subTest(case=cases.index(text)):
                with self.assertRaises(gate.InputRejected):
                    gate.parse_values(text)

    def test_plaintext_and_rest_urls_rejected(self):
        for changes in ({'REDIS_SSL_ENABLED': 'false'}, {'REDIS_SSL_ENABLED': 'TRUE'},
                        {'REDIS_HOST': 'https://redis.example.com'},
                        {'REDIS_HOST': 'rediss://redis.example.com:6379'}):
            with self.assertRaises(gate.InputRejected):
                gate.parse_values(encoded({**synthetic_values(), **changes}))

    def test_ip_loopback_malformed_host_and_port_rejected(self):
        cases = [{'TIDB_HOST': host} for host in ('127.0.0.1', 'localhost', 'db.localhost',
                  'db.invalid', 'db..com', 'db_with_underscore.example.com', 'db.example.com/path')]
        cases += [{'REDIS_PORT': value} for value in ('0', '65536', '-1', '6 379', 'rest')]
        for changes in cases:
            with self.assertRaises(gate.InputRejected):
                gate.parse_values(encoded({**synthetic_values(), **changes}))

    def test_literal_quotes_without_expansion(self):
        original = synthetic_values()
        text = '\n'.join(key + '=\"' + value + '\"' for key, value in original.items())
        self.assertEqual(gate.parse_values(text).values, original)
        with self.assertRaises(gate.InputRejected):
            gate.parse_values(encoded(original).replace('TIDB_HOST=', 'TIDB_HOST=\"'))

    def test_traversal_rejected(self):
        with self.assertRaises(gate.InputRejected):
            gate.read_inputs(self.directory / 'missing/../input.env')

    def test_cli_never_leaks_input_and_has_non_success_exit(self):
        with patch.object(gate.Path, 'home', return_value=self.directory), \
                patch('sys.argv', ['preflight', '--check-only']), \
                contextlib.redirect_stdout(io.StringIO()) as output:
            code = gate.main()
        self.assertEqual(code, 2)
        result = json.loads(output.getvalue())
        self.assertEqual(forbidden(result), [])
        for value in synthetic_values().values():
            # Short numeric/config literals are intentionally not sensitive input identifiers.
            if len(value) > 8:
                self.assertNotIn(value, output.getvalue())

    def test_all_results_are_sanitized(self):
        for filename in (self.path, self.directory / 'absent'):
            self.assertEqual(forbidden(gate.assess(filename)), [])


if __name__ == '__main__':
    unittest.main()
