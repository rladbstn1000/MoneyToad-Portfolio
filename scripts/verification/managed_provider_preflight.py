#!/usr/bin/env python3
"""Offline preparation gate. Never connects to providers or performs remote writes.

This is deliberately not an untested remote mutation runner. After this gate,
provider execution still requires the bounded probe/ownership protocol in the
deployment report. No environment is sourced and no input value is reported.
"""
import argparse
import dataclasses
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
import sys
import uuid

from public_evidence import forbidden

ROOT = Path(__file__).resolve().parents[2]
REQUIRED = frozenset((
    'TIDB_HOST', 'TIDB_PORT', 'TIDB_SETUP_USERNAME', 'TIDB_SETUP_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'REDIS_USERNAME', 'REDIS_PASSWORD', 'REDIS_SSL_ENABLED',
))
MAX_BYTES = 16384


class InputRejected(Exception):
    """Only fixed reason codes; never include user-controlled text."""


@dataclasses.dataclass(repr=False, frozen=True)
class ProviderInputs:
    values: dict[str, str] = dataclasses.field(repr=False)

    def __repr__(self):
        return 'ProviderInputs(<redacted>)'


def parse_values(text):
    values = {}
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        key = key.strip()
        # Unrelated keys are neither interpreted nor propagated into subprocesses.
        if key not in REQUIRED:
            continue
        if not separator or key in values:
            raise InputRejected('REQUIRED_FIELD_SYNTAX_OR_DUPLICATE')
        value = value.strip()
        if value[:1] in ('"', "'"):
            if len(value) < 2 or value[-1] != value[0]:
                raise InputRejected('QUOTED_VALUE_UNCLOSED')
            value = value[1:-1]
        if (not value or any(ord(character) < 32 or ord(character) == 127 for character in value)
                or '$(' in value or '`' in value or '${' in value
                or '<' in value or '>' in value):
            raise InputRejected('EMPTY_PLACEHOLDER_OR_INTERPOLATED_VALUE')
        values[key] = value
    if set(values) != REQUIRED:
        raise InputRejected('REQUIRED_FIELDS_MISSING')
    if values['REDIS_SSL_ENABLED'] != 'true':
        raise InputRejected('NATIVE_REDIS_TLS_REQUIRED')
    for prefix in ('TIDB', 'REDIS'):
        host = values[prefix + '_HOST']
        if (len(host) > 253 or host != host.lower() or '.' not in host
                or not all(re.fullmatch(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?', part)
                           for part in host.split('.'))):
            raise InputRejected('DNS_HOSTNAME_REQUIRED')
        try:
            ipaddress.ip_address(host)
        except ValueError:
            pass
        else:
            raise InputRejected('DNS_HOSTNAME_REQUIRED')
        # No DNS lookup here. Provider ownership/region/free plan are separate checks.
        if host.endswith(('.localhost', '.local', '.invalid')):
            raise InputRejected('MANAGED_HOST_REQUIRED')
        if not re.fullmatch(r'[0-9]{1,5}', values[prefix + '_PORT']):
            raise InputRejected('PORT_INVALID')
        if not 1 <= int(values[prefix + '_PORT']) <= 65535:
            raise InputRejected('PORT_INVALID')
    return ProviderInputs(values)


def read_inputs(path):
    """Walk every directory without following links; inspect the opened inode."""
    path = Path(path).absolute()
    if '..' in path.parts:
        raise InputRejected('PATH_TRAVERSAL_REJECTED')
    directory = None
    opened = None
    try:
        directory = os.open(path.anchor, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        for part in path.parts[1:-1]:
            next_directory = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
            os.close(directory)
            directory = next_directory
        info = os.fstat(directory)
        if info.st_uid != os.getuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise InputRejected('DIRECTORY_OWNER_OR_MODE')
        opened = os.open(path.name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        info = os.fstat(opened)
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid()
                or stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
            raise InputRejected('FILE_TYPE_OWNER_OR_MODE')
        if info.st_size > MAX_BYTES:
            raise InputRejected('INPUT_TOO_LARGE')
        with os.fdopen(opened, 'rb', closefd=False) as stream:
            raw = stream.read(MAX_BYTES + 1)
        if len(raw) > MAX_BYTES:
            raise InputRejected('INPUT_TOO_LARGE')
        try:
            return parse_values(raw.decode('utf-8'))
        except UnicodeDecodeError:
            raise InputRejected('INPUT_ENCODING') from None
    except FileNotFoundError:
        raise InputRejected('CONNECTION_FILE_MISSING') from None
    except OSError:
        raise InputRejected('INPUT_UNSAFE_OR_UNREADABLE') from None
    finally:
        if opened is not None:
            os.close(opened)
        if directory is not None:
            os.close(directory)


def assess(path):
    try:
        read_inputs(path)
        input_status, reason = 'PASS', 'REMOTE_EXECUTION_NOT_PERFORMED'
    except InputRejected as failure:
        input_status, reason = 'BLOCKED', str(failure)
    return {
        'status': 'BLOCKED', 'phase': 'offline-preflight',
        'input_check': input_status, 'reason': reason,
        'provider_connections_attempted': 0, 'remote_writes': 0,
        'remote_cleanup': 'NOT_NEEDED_NO_REMOTE_RESOURCES_CREATED',
        'tidb_contracts_verified': False, 'upstash_functional_contracts_verified': False,
        'upstash_failover_revocation_guarantee': 'NOT_ESTABLISHED',
        'provider_selection': 'HOLD', 'managed_provider_contracts_verified': False,
        'public_deployment_ready': False,
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check-only', action='store_true', help='Do not save even a sanitized summary')
    args = parser.parse_args()
    result = assess(Path.home() / '.config/moneytoad/provider-check.env')
    if forbidden(result):
        raise RuntimeError('Public projection rejected')
    if not args.check_only:
        output = ROOT / 'docs/deployment/evidence/MANAGED_PROVIDER_CONTRACTS' / uuid.uuid4().hex[:12]
        output.mkdir(parents=True, exist_ok=False)
        (output / 'preflight-summary.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))
    return 2  # Offline preparation never claims provider completion, even with valid inputs.


if __name__ == '__main__':
    sys.exit(main())
