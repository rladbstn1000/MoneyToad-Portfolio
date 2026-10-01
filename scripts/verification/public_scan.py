"""Scan every snapshot file; reviewed source syntax never exempts real key/token patterns."""
import hashlib
import json
from pathlib import Path
import re
import struct
from public_evidence import ROOT, VALUE_PATTERNS, forbidden, save
from public_source_review import SourceReviews, evidence_path

HARD = {key: pattern for key, pattern in VALUE_PATTERNS.items()
        if key not in ('email-value', 'bearer-value', 'cookie-value')}
SITES = {
    'email-marker': VALUE_PATTERNS['email-value'],
    'bearer-marker': re.compile(r'(?i)\bbearer\s+[^\s\"\x27+]'),
    'authorization-marker': re.compile(r'(?i)\bauthorization\s*:'),
    'credential-assignment': re.compile(r'(?i)(?:[A-Za-z_]*password|[A-Za-z_]*secret|[A-Za-z_]*access.?key)[\"\x27]?\s*[=:]\s*[\"\x27]?[A-Za-z0-9]'),
    'identity-field': re.compile(r'''["'](?:userId|user_id|sid|refreshHash|refresh_hash|email)["']\s*[:=]'''),
    'cookie-assignment': re.compile(r'demoRefreshToken=\S+'),
    'cookie-header': re.compile(r'''(?i)["'](?:cookie|set-cookie)["']\s*:\s*["'][^"']'''),
}
BAD_SUFFIXES = {'.pem', '.key', '.p12', '.pfx', '.crt', '.cer', '.rdb', '.dump', '.csv', '.zip', '.patch', '.log',
                '.har', '.webm', '.woff', '.woff2', '.ttf', '.otf', '.pyc'}
BAD_PARTS = {'.git', 'node_modules', '__pycache__', '.gradle', 'build', 'dist', 'before',
             'source', 'red-source', 'test-results', 'playwright-report', 'blob-report'}


def sites(text):
    for number, line in enumerate(text.splitlines(), 1):
        for category, pattern in SITES.items():
            if pattern.search(line):
                yield number, category, hashlib.sha256(line.encode()).hexdigest()


def png_metadata_safe(raw):
    if not raw.startswith(b'\x89PNG\r\n\x1a\n'):
        return False
    offset = 8
    while offset < len(raw):
        size = struct.unpack('>I', raw[offset:offset + 4])[0]
        kind = raw[offset + 4:offset + 8]
        if kind not in (b'IHDR', b'IDAT', b'IEND'):
            return False
        offset += size + 12
    return offset == len(raw)


def json_document(text, relative):
    # TypeScript configs are JSONC. Preserve quoted strings while parsing comments/trailing commas.
    if relative.startswith('fe/tsconfig') and relative.endswith('.json'):
        text = re.sub(r'("(?:\\.|[^"\\])*")|//[^\n]*|/\*[\s\S]*?\*/',
                      lambda match: match.group(1) or '', text)
        text = re.sub(r'("(?:\\.|[^"\\])*")|,\s*([}\]])',
                      lambda match: match.group(1) or match.group(2), text)
    return json.loads(text)


def scan(root=ROOT):
    reviews = SourceReviews(root, SITES)
    unresolved, classified = [], []
    files = sorted(path for path in root.rglob('*') if path.is_file() or path.is_symlink())
    for path in files:
        relative = path.relative_to(root).as_posix()
        if path.is_symlink():
            unresolved.append({'file': relative, 'category': 'symlink'})
            continue
        if (set(path.relative_to(root).parts) & BAD_PARTS or path.suffix in BAD_SUFFIXES
                or path.name == '.DS_Store' or (path.name.startswith('.env') and path.name != '.env.example')):
            unresolved.append({'file': relative, 'category': 'excluded-artifact'})
        raw = path.read_bytes()
        if path.suffix == '.jar' and relative != 'be/gradle/wrapper/gradle-wrapper.jar':
            unresolved.append({'file': relative, 'category': 'compiled-archive'})
        if raw[:4] in (b'\x7fELF', b'\xcf\xfa\xed\xfe', b'\xfe\xed\xfa\xcf'):
            unresolved.append({'file': relative, 'category': 'native-binary'})
        if path.suffix == '.sql' and re.search(rb'(?i)INSERT\s+INTO|mysqldump|COPY.+FROM\s+stdin', raw):
            if reviews.match_sql(relative, raw):
                classified.append({'file': relative, 'category': 'data-dump-candidate',
                                   'classification': 'B_NON_SECRET_SOURCE_PATTERN'})
            else:
                unresolved.append({'file': relative, 'category': 'data-dump-candidate'})
        if path.suffix == '.png' and not png_metadata_safe(raw):
            unresolved.append({'file': relative, 'category': 'image-metadata'})
        # Search hard patterns in all bytes, including binary resources.
        text = raw.decode('utf-8', errors='replace')
        for category, pattern in HARD.items():
            for match in pattern.finditer(text):
                unresolved.append({'file': relative, 'line': text[:match.start()].count('\n') + 1,
                                   'category': category})
        evidence = evidence_path(relative)
        try:
            raw.decode('utf-8')
            text_evidence = evidence
        except UnicodeDecodeError:
            # Compressed image bytes are not source text; hard-byte and metadata checks above remain.
            verified_png = path.suffix == '.png' and png_metadata_safe(raw)
            text_evidence = evidence and not verified_png
            if text_evidence:
                unresolved.append({'file': relative, 'category': 'unreadable-text-evidence'})
        if path.suffix == '.json':
            try:
                issues = forbidden(json_document(text, relative))
                if relative in ('fe/package.json', 'fe/package-lock.json'):
                    issues = [issue for issue in issues if not (issue['category'] == 'forbidden-field' and issue.get('pointer') in ('/name', '/packages//name', '/packages/node_modules/@bundled-es-modules/cookie/dependencies/cookie', '/packages/node_modules/react-router/dependencies/cookie'))]
            except (ValueError, TypeError):
                issues = [{'category': 'invalid-json'}]
            unresolved.extend({'file': relative, **issue} for issue in issues)
        if text_evidence or path.suffix in ('.java', '.ts', '.tsx', '.py', '.json', '.yml', '.yaml', '.md', '.example', '.sql'):
            for number, category, digest in sites(text):
                match = reviews.match(relative, category, text.splitlines()[number - 1], raw)
                dependency_metadata = (relative == 'fe/package-lock.json' and category == 'cookie-header'
                    and re.fullmatch(r'\s*"cookie": \"[~^]?[0-9]+(?:\.[0-9]+){0,2}\"[,]?\s*', text.splitlines()[number - 1]) is not None)
                if match and not evidence and (dependency_metadata or not (path.suffix == '.json' and category in ('identity-field', 'cookie-assignment', 'cookie-header'))):
                    classified.append({'file': relative, 'line': number, 'category': category,
                                       'classification': 'B_NON_SECRET_SOURCE_PATTERN'})
                else:
                    unresolved.append({'file': relative, 'line': number, 'category': category})
    unresolved.extend(reviews.unresolved())
    return {'status': 'PASS' if not unresolved else 'FAIL', 'files_scanned': len(files),
            'unresolved': unresolved, 'classified_source_sites': classified}


if __name__ == '__main__':
    result = scan()
    # Reports contain location/category only, never matched text.
    save('snapshot-scan.json', result)
    print(json.dumps({'status': result['status'], 'files': result['files_scanned'],
                      'unresolved_count': len(result['unresolved'])}))
    raise SystemExit(0 if result['status'] == 'PASS' else 1)
