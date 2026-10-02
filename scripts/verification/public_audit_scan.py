#!/usr/bin/env python3
"""Scan a byte-exact public Git candidate copy, preserving all past evidence."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile

from public_evidence import ROOT, forbidden
from public_scan import scan
from demo_capacity_checks import evidence_output


def safe_path(relative, *, output=False):
    """Reject linked ancestors before reading or writing any candidate path."""
    relative = Path(relative)
    if relative.is_absolute() or '..' in relative.parts or ROOT.is_symlink():
        raise ValueError('PUBLIC_CANDIDATE_PATH_REJECTED')
    current = ROOT.resolve(strict=True)
    for index, part in enumerate(relative.parts):
        current = current / part
        try:
            info = current.lstat()
        except FileNotFoundError:
            if output:
                continue
            raise ValueError('PUBLIC_CANDIDATE_MISSING') from None
        if stat.S_ISLNK(info.st_mode):
            raise ValueError('PUBLIC_CANDIDATE_LINK_REJECTED')
        final = index == len(relative.parts) - 1
        if output or not final:
            if not stat.S_ISDIR(info.st_mode):
                raise ValueError('PUBLIC_CANDIDATE_DIRECTORY_REQUIRED')
        elif not stat.S_ISREG(info.st_mode) or info.st_nlink != 1:
            raise ValueError('PUBLIC_CANDIDATE_REGULAR_FILE_REQUIRED')
    return current


def main(*, evidence_directory='PUBLIC_SCANNER_AUDIT'):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run-label', required=True)
    args = parser.parse_args()
    output = evidence_output(ROOT, evidence_directory, args.run_label)
    output = safe_path(output.relative_to(ROOT), output=True)
    env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
    env['GIT_OPTIONAL_LOCKS'] = '0'
    names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
                                    cwd=ROOT, env=env).decode().split('\0')
    files = sorted(set(names) - {''})
    if not files:
        raise ValueError('PUBLIC_CANDIDATE_SET_EMPTY')
    before = {}
    with tempfile.TemporaryDirectory(prefix='moneytoad-public-scan-') as temporary:
        snapshot = Path(temporary)
        for relative in files:
            path = Path(relative)
            if path.is_absolute() or '..' in path.parts:
                raise ValueError('PUBLIC_CANDIDATE_PATH_REJECTED')
            source, target = safe_path(path), snapshot / path
            target.parent.mkdir(parents=True, exist_ok=True)
            before[relative] = (hashlib.sha256(source.read_bytes()).hexdigest(), source.stat().st_mode)
            shutil.copy2(source, target)
            if not target.is_file() or (hashlib.sha256(target.read_bytes()).hexdigest(), target.stat().st_mode) != before[relative]:
                raise ValueError('PUBLIC_CANDIDATE_COPY_MISMATCH')
        result = scan(snapshot)
        if result.get('files_scanned') != len(files):
            raise ValueError('PUBLIC_CANDIDATE_SCAN_COUNT_MISMATCH')
        current = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'],
                                          cwd=ROOT, env=env).decode().split('\0')
        if sorted(set(current) - {''}) != files:
            raise ValueError('PUBLIC_CANDIDATE_SET_CHANGED')
        for relative, expected in before.items():
            path = safe_path(relative)
            observed = (hashlib.sha256(path.read_bytes()).hexdigest(), path.stat().st_mode)
            if observed != expected:
                raise ValueError('SOURCE_CHANGED_DURING_SCAN')
    result['source_preserved'] = True
    result['owned_copy_removed'] = not Path(temporary).exists()
    result['candidate_selection'] = 'all tracked and nonignored untracked files; no scan exclusions added'
    if forbidden(result):
        raise ValueError('PUBLIC_SCAN_PROJECTION_REJECTED')
    output = safe_path(output.relative_to(ROOT.resolve()), output=True)
    output.mkdir(parents=True, exist_ok=False)
    (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'status': result['status'], 'files': result['files_scanned'],
                      'unresolved': len(result['unresolved']), 'cleanup_complete': result['owned_copy_removed']}))
    return 0 if result['status'] == 'PASS' and result['owned_copy_removed'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
