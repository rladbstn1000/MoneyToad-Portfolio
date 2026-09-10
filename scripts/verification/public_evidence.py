"""Construct public evidence from explicit schemas; never serialize runtime objects."""
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'docs/portfolio/evidence'
FORBIDDEN_KEYS = frozenset(('userid', 'user_id', 'email', 'name', 'jwt', 'at', 'rt',
    'authorization', 'sid', 'refreshhash', 'refresh_hash', 'password', 'secret',
    'credential', 'credentials', 'pid', 'container_id', 'work_directory', 'cookievalue',
    'access_token', 'refreshtoken', 'accesstoken', 'token', 'budgetid', 'cardid', 'transactionid',
    'id', 'pk', 'containerid', 'processgroup', 'sessionid', 'cookie', 'hash', 'port'))
VALUE_PATTERNS = {
    'private-key': re.compile(r'-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----'),
    'jwt': re.compile(r'eyJ[\w-]{8,}\.[\w-]{10,}\.[\w-]{16,}'),
    'bearer-value': re.compile(r'(?i)bearer\s+\S+'),
    'email-value': re.compile(r'[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}'),
    'github-token': re.compile(r'(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})'),
    'aws-key': re.compile(r'\b(?:AKIA|ASIA)[A-Z0-9]{16}\b'),
    'local-path': re.compile('/' + r'(?:Users|private/tmp|var/folders|private/var/folders)/'),
    'cookie-value': re.compile(r'demoRefreshToken=\S+'),
    'credential-url': re.compile(r'https?://[^\s/@:]+:[^\s/@]+@'),
}


def forbidden(value, pointer=''):
    findings = []
    if isinstance(value, dict):
        for key, item in value.items():
            site = pointer + '/' + key
            if re.sub(r'[^a-z0-9]', '', key.lower()) in {re.sub(r'[^a-z0-9]', '', k) for k in FORBIDDEN_KEYS}:
                findings.append({'pointer': site, 'category': 'forbidden-field'})
            findings.extend(forbidden(item, site))
    elif isinstance(value, list):
        for index, item in enumerate(value):
            findings.extend(forbidden(item, pointer + '/' + str(index)))
    elif isinstance(value, str):
        findings.extend({'pointer': pointer, 'category': category}
                        for category, pattern in VALUE_PATTERNS.items() if pattern.search(value))
    return findings


def save(filename, value):
    if forbidden(value):
        raise ValueError('Public projection contains forbidden material; values suppressed')
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')


def backend(source):
    suites = []
    for suite in source.get('suites', []):
        suites.append({'test': suite['name'], 'tests': suite['tests'], 'failures': suite['failures'],
                       'errors': suite['errors'], 'skipped': suite['skipped'],
                       'cases': [{'test': (case['name'] if re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$]*(?:\(\))?', case['name']) else 'case-' + str(index + 1)), 'status': case['status']}
                                 for index, case in enumerate(suite.get('cases', []))]})
    result = {'status': source['status'], 'exit_code': source['exit_code'],
              'cleanup_complete': source['cleanup_complete'], 'suites': suites,
              'tests': sum(s['tests'] for s in suites),
              'failures': sum(s['failures'] for s in suites),
              'errors': sum(s['errors'] for s in suites),
              'skipped': sum(s['skipped'] for s in suites)}
    if 'java_version' in source:
        result['java_version'] = source['java_version']
    return result


def frontend(source):
    checks = []
    for row in source['checks']:
        check = {'check': row['name'], 'status': row['status']}
        for key in ('exit_code', 'errors', 'warnings'):
            if key in row:
                check[key] = row[key]
        if 'tests' in row:
            check['tests'] = {key: row['tests'][key] for key in (
                'numTotalTests', 'numPassedTests', 'numFailedTests', 'numPendingTests', 'numTodoTests')}
        checks.append(check)
    return {'status': source['status'], 'checks': checks}


COUNTS = ('users', 'cards', 'transactions', 'budgets', 'financial', 'jobs', 'changed',
          'sessions', 'outbound', 'options', 'optionsRejected', 'loginPosts')


def counts(source):
    return {key: source[key] for key in COUNTS if type(source.get(key)) is int}


def browser(source, directory):
    result = {'status': source['status'], 'exit_code': source['exit_code'],
              'cleanup_complete': source['cleanup_complete'], 'modes': []}
    if 'failed_phase' in source:
        result['failed_phase'] = source['failed_phase']
        result['failure_classes'] = source.get('failure_classes', [])
        result['failure_tags'] = source.get('failure_tags', [])
        result['diagnostic'] = source.get('reason', 'UNSPECIFIED')
    for mode in ('public-demo', 'local-demo'):
        folder = Path(directory) / mode
        entry = {'mode': mode}
        def read(filename):
            path = folder / filename
            return json.loads(path.read_text()) if path.exists() else None
        tests = read('tests.json')
        if tests:
            entry['status'] = tests['status']
            entry['cases'] = [{'test': row['title'], 'status': row['status']} for row in tests['cases']]
        cookie = read('cookie-contract.json')
        if cookie:
            entry['cookie_attributes'] = {key: cookie[key] for key in ('httpOnly', 'secure',
                'sameSite', 'path', 'domainAttributeAbsent', 'sentOnReissue', 'jsVisible',
                'absoluteExpiryUnchanged', 'removedOnLogout') if key in cookie}
        seed = read('seed-counts.json')
        if seed:
            entry['seed_counts'] = counts(seed)
        chart = read('chart-contract.json')
        if chart:
            entry['chart'] = {'total_before': chart['before']['total'],
                'leaked_before': chart['before']['leaked'],
                'total_after': chart['displayedTotalAfter'], 'leak_before': chart['displayedLeakBefore'],
                'leak_after': chart['displayedLeakAfter'],
                'leaked_after': chart['after'][0]['leaked'], 'db_after': counts(chart['dbAfter'])}
        cors = read('cors-contract.json')
        if cors:
            entry['cors'] = {key: cors[key] for key in ('allowedStatus', 'deniedPreflightStatus',
                                                     'deniedActualPostCount')}
        networks = []
        for path in sorted(folder.glob('*-network.json')):
            network = json.loads(path.read_text())
            events = []
            for event in network['events']:
                # An unexpected unnormalized path is a failed projection, not silently hidden.
                if not re.fullmatch(r'/[A-Za-z0-9_/:.%-]*', event['path']):
                    raise ValueError('Invalid public API path')
                events.append({key: event[key] for key in ('order', 'method', 'path', 'status', 'phase')
                               if key in event})
            networks.append({'events': events, 'external_attempts': network['externalAttempts'],
                             'backend_attempts': network['backendAttempts']})
        entry['networks'] = networks
        result['modes'].append(entry)
    return result


def browser_series(runs, expected_runs):
    """Summarize only entries returned by this invocation, never stale files on disk."""
    entries = []
    for filename, summary in runs:
        if not re.fullmatch(r'browser-[a-f0-9]{12}-summary\.json', filename):
            raise ValueError('Invalid browser evidence filename')
        modes = summary.get('modes', [])
        cases = [case for mode in modes for case in mode.get('cases', [])]
        networks = [network for mode in modes for network in mode.get('networks', [])]
        external = sum(network['external_attempts'] + network['backend_attempts'] for network in networks)
        passed = (summary['status'] == 'PASS' and summary['cleanup_complete']
                  and len(modes) == 2 and len(cases) == 4 and networks and external == 0
                  and all(case['status'] == 'passed' for case in cases))
        entries.append({'evidence_file': filename, 'status': 'PASS' if passed else 'FAIL',
                        'cases': len(cases), 'external_attempts': external,
                        'cleanup_complete': summary['cleanup_complete']})
    return {'status': 'PASS' if len(entries) == expected_runs and all(
                row['status'] == 'PASS' for row in entries) else 'FAIL',
            'expected_runs': expected_runs, 'runs': entries, 'workers': 1, 'retries': 0,
            'product_api_mocks': False, 'font_policy': 'test-only system fallback'}
