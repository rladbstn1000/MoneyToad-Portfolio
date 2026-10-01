"""Fail-closed, source-only review bindings; never a secret-pattern exemption.

Metadata is manually reviewed. This module cannot create, refresh, or register it.
Every candidate still passes a coded semantic predicate and all independent scans.
"""
import hashlib
import json
from pathlib import PurePosixPath
import re


REVIEW_FILE = 'scripts/verification/fixtures/scan-classifications.json'
FIELDS = {'file', 'line', 'category', 'classification', 'file_digest',
          'content_digest', 'semantic_category', 'expected_construct', 'occurrences'}
DIGEST = re.compile(r'[a-f0-9]{64}')
LITERALS = re.compile(r'"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27|`(?:\\.|[^`\\])*`')
TOKENS = re.compile(r'[A-Za-z_$][A-Za-z_0-9$]*|[0-9]+|[^\s]')
SENSITIVE_ASSIGNMENT = re.compile(
    r'(?i)(?:[A-Za-z_]*password|[A-Za-z_]*secret|[A-Za-z_]*access.?key)["\x27]?\s*[=:](?!=)\s*')
SQL_DUMP = re.compile(r'(?i)INSERT\s+INTO|mysqldump|COPY.+FROM\s+stdin')
FIXED_DDL = {
    'be/src/main/resources/db/demo/V001__demo_admission.sql':
        'INSERT INTO demo_capacity (id, max_visitors) VALUES (1, 1000);',
    'be/src/main/resources/db/demo/V002__demo_admission_lock.sql':
        'INSERT INTO demo_admission_lock (id) VALUES (1);',
}
SEMANTICS = {'reserved-invalid-domain', 'runtime-credential-expression', 'runtime-request-header',
             'cookie-name-boundary', 'negative-detection-pattern', 'sql-column-selector',
             'runtime-negative-identity', 'dependency-version', 'fixed-singleton-ddl',
             'nullable-source-identifier', 'runtime-cookie-fixture', 'source-review-regex',
             'fixed-no-output-comment'}
# Reviewed predicate grammars in this module. These are pattern fingerprints,
# not value exemptions. Changing the grammar requires a separate code review.
REVIEWED_REGEX_DIGESTS = {
    '806962f9c59e048aac05e1a5311d00aa74a5e822d3c2d74b9a8e44dcfb003c39',
    '7428fa0801ed8bd2255f0060c3f314c003000dfc753011a88da3e4b388b0a5cb',
    '2c22ce2c8f07e02c1dd33ddbe98faf9e05de6cfba7c5a964d134385bc9ec19ab',
}
# Complete, reviewed detector expressions (cookie presence, captured headers, and
# forbidden log markers). A matching suffix is insufficient to approve a regex.
REVIEWED_DETECTOR_DIGESTS = {
    'c1f6c88439777033ec3930fbb67e8291500cd71692632dad09f5163024797790',
    '1bedbbc55c036d9734739d7b130f7f1a51d25a43a30d4b7aefa2f0b5fe1ba768',
    '311cc55e0413ecdab31f70206543b437bde442417121b54815887e14ee0c4e52',
}


def evidence_path(relative):
    """No evidence format (including text/Markdown) is eligible for a source review."""
    return any('evidence' in part.lower() for part in PurePosixPath(relative).parts[:-1])


def construct(line):
    """A reviewable lexical shape without literal contents or numeric values."""
    stripped = LITERALS.sub(' LITERAL ', line)
    stripped = re.sub(r'//.*|#.*', ' COMMENT ', stripped)
    return ['NUMBER' if token.isdigit() else token for token in TOKENS.findall(stripped)]


def _expression(text):
    depth, quote, escaped = 0, None, False
    for index, character in enumerate(text):
        if quote:
            if escaped:
                escaped = False
            elif character == '\\':
                escaped = True
            elif character == quote:
                quote = None
        elif character in '\"\x27':
            quote = character
        elif character in '([':
            depth += 1
        elif character in ')]':
            if depth == 0:
                return text[:index].strip()
            depth -= 1
        elif depth == 0 and character in ',;}':
            return text[:index].strip()
    return text.strip()


def _runtime_assignments(line, context):
    found = list(SENSITIVE_ASSIGNMENT.finditer(line))
    literals = list(LITERALS.finditer(line))
    for match in found:
        if any(literal.start() <= match.end() < literal.end() for literal in literals):
            return False
        value = _expression(line[match.end():])
        simple = (r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*',
                  r'(?:secrets\.token_(?:hex|urlsafe)|randomHex)\([0-9]+\)',
                  r'crypto\.randomUUID\(\)',
                  r'(?:java\.util\.)?UUID\.randomUUID\(\)\.toString\(\)(?:\.toCharArray\(\))?',
                  r'(?:environment\.getProperty|System\.getenv|config\.get)\(["\x27][A-Za-z_][A-Za-z_0-9.-]*["\x27]\)',
                  r'secret == null \? new byte\[0\] : secret\.getBytes\(StandardCharsets\.US_ASCII\)')
        if any(re.fullmatch(form, value) for form in simple):
            continue
        if (value == 'Base64.getUrlEncoder().withoutPadding()' and context is not None
                and re.search(re.escape(line.strip()) + r'\s*\.encodeToString\(Jwts\.SIG\.HS384\.key\(\)\.build\(\)\.getEncoded\(\)\);', context)):
            continue
        return False
    return bool(found)


def _header_expression(category, line, pattern):
    for match in pattern.finditer(line):
        tail = line[match.start():]
        if category == 'bearer-marker':
            literal = next((item for item in LITERALS.finditer(line)
                            if item.start() < match.start() < item.end()), None)
            if (literal is None or literal.group()[0] != '`'
                    or not re.fullmatch(r'(?i)bearer \$\{[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\}', literal.group()[1:-1])
                    or re.match(r'\s*\+', line[literal.end():])):
                return False
        elif category == 'authorization-marker':
            if not re.match(r'(?i)authorization\s*:\s*(?:`bearer \$\{[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\}`|[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*[,}]', tail):
                return False
        elif category == 'cookie-header':
            return False
    return True


def _detector_expression(category, line, pattern):
    for site in pattern.finditer(line):
        literal = next((item for item in LITERALS.finditer(line)
                        if item.start() < site.start() < item.end()), None)
        if literal is None or not re.search(r'\b(?:re\.(?:compile|search)|Pattern\.compile)\s*\(\s*r?$',
                                            line[:literal.start()]):
            return False
        if hashlib.sha256(literal.group()[1:-1].encode()).hexdigest() not in REVIEWED_DETECTOR_DIGESTS:
            return False
    return True


def _cookie_boundary(line, pattern):
    for site in pattern.finditer(line):
        literal = next((item for item in LITERALS.finditer(line)
                        if item.start() < site.start() < item.end()), None)
        if literal is None:
            return False
        contents = literal.group()[1:-1]
        # Empty value prefixes are used to inspect names, delete cookies, or combine runtime values.
        prefix, value = contents.split('=', 1)
        if prefix != 'demoRefreshToken':
            return False
        if value not in ('', ';'):
            if (literal.group()[0] != '`'
                    or not re.fullmatch(r'\$\{[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*\}', value)
                    or re.match(r'\s*\+', line[literal.end():])):
                return False
        elif re.match(r'\s*\+', line[literal.end():]):
            expression = _expression(line[literal.end():])
            allowed = (r'\+\s*(?:[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*|'
                       r'issued\.refreshToken\(\)|secrets\.token_hex\([0-9]+\)|crypto\.randomUUID\(\))'
                       r'(?:\s*\+\s*["\x27];(?: Secure; HttpOnly; SameSite=Lax; Path=/api/auth/demo)?["\x27])?')
            if value or not re.fullmatch(allowed, expression):
                return False
    return True


def semantic_ok(kind, category, line, relative, pattern, context=None):
    """Recognize only value-free source constructs; test location alone is irrelevant."""
    if kind == 'reserved-invalid-domain' and category == 'email-marker':
        return all(match.group().lower().endswith('.invalid') for match in pattern.finditer(line))
    if (kind == 'runtime-credential-expression' and category == 'credential-assignment'
            and PurePosixPath(relative).suffix in ('.java', '.ts', '.tsx', '.py')):
        return _runtime_assignments(line, context)
    if kind == 'runtime-request-header' and category in ('bearer-marker', 'authorization-marker'):
        return _header_expression(category, line, pattern)
    if kind == 'cookie-name-boundary' and category == 'cookie-assignment':
        return _cookie_boundary(line, pattern)
    if kind == 'negative-detection-pattern' and category in ('authorization-marker', 'cookie-assignment'):
        return _detector_expression(category, line, pattern)
    if kind == 'sql-column-selector' and category == 'identity-field' and relative.endswith('.java'):
        return all(line[:match.start()].rstrip().endswith('?')
                   and re.match(r'["\x27]user_id["\x27]\s*:\s*["\x27]id["\x27]', line[match.start():])
                   for match in pattern.finditer(line))
    if kind == 'runtime-negative-identity' and category == 'identity-field' and relative.endswith('.py'):
        return all(re.fullmatch(r'(?:secrets\.token_hex\([0-9]+\)|secrets\.randbelow\([0-9_]+\)\s*\+\s*1|[A-Za-z_]\w*)',
                               _expression(line[match.end():]).strip()) for match in pattern.finditer(line))
    if kind == 'dependency-version' and category == 'cookie-header' and relative == 'fe/package-lock.json':
        return re.fullmatch(r'\s*"cookie": "[~^]?[0-9]+(?:\.[0-9]+){0,2}"[,]?\s*', line) is not None
    if kind == 'fixed-singleton-ddl' and category == 'data-dump-candidate':
        return relative in FIXED_DDL and line == FIXED_DDL[relative]
    if kind == 'nullable-source-identifier' and category == 'bearer-marker' and relative.endswith('.java'):
        return all(re.search(r'if\s*\(\s*$', line[:match.start()])
                   and re.match(r'bearer\s+!=\s+null\)', line[match.start():]) for match in pattern.finditer(line))
    if kind == 'runtime-cookie-fixture' and category == 'cookie-header' and relative.endswith('.ts'):
        return all(re.match(r'["\x27]Set-Cookie["\x27]\s*:\s*["\x27]fixture=["\x27]\s*\+\s*crypto\.randomUUID\(\)\s*}', line[match.start():])
                   for match in pattern.finditer(line))
    if kind == 'fixed-no-output-comment' and category == 'bearer-marker' and relative == 'fe/e2e/demo.spec.ts':
        return line.split() == ['//', 'Bearer', 'exists', 'only', 'in', 'this', 'test', 'process',
                                'memory;', 'no', 'request/response', 'dump', 'is', 'retained.']
    if kind == 'source-review-regex' and relative == 'scripts/verification/public_source_review.py':
        for site in pattern.finditer(line):
            literal = next((item for item in LITERALS.finditer(line)
                            if item.start() < site.start() < item.end()), None)
            if (literal is None or not re.search(r'\bre\.(?:match|fullmatch)\(r?$', line[:literal.start()])
                    or hashlib.sha256(literal.group().encode()).hexdigest() not in REVIEWED_REGEX_DIGESTS):
                return False
        return True
    return False


class SourceReviews:
    def __init__(self, root, patterns):
        self.root, self.patterns = root, {**patterns, 'data-dump-candidate': SQL_DUMP}
        self.rows, self.errors, self.used = {}, [], set()
        path = root / REVIEW_FILE
        if not path.exists():
            return
        try:
            if path.is_symlink():
                raise ValueError
            document = json.loads(path.read_text())
            if set(document) != {'version', 'sites'} or document['version'] != 2 or not isinstance(document['sites'], list):
                raise ValueError
            for row in document['sites']:
                self._load(row)
        except (ValueError, TypeError, KeyError, OSError):
            self.errors.append({'file': REVIEW_FILE, 'category': 'invalid-source-review'})

    def _load(self, row):
        if not isinstance(row, dict) or set(row) != FIELDS:
            raise ValueError
        relative = row['file']
        if (not isinstance(relative, str) or relative != PurePosixPath(relative).as_posix()
                or PurePosixPath(relative).is_absolute() or '..' in PurePosixPath(relative).parts
                or any(character in relative for character in '\\*?{}')
                or (any(character in relative for character in '[]') and relative != 'fe/functions/api/[[path]].ts')
                or relative == REVIEW_FILE or evidence_path(relative)
                or row['classification'] != 'B_NON_SECRET_SOURCE_PATTERN'
                or type(row['line']) is not int or row['line'] < 1
                or type(row['occurrences']) is not int or row['occurrences'] < 1
                or not all(isinstance(row[key], str) for key in ('category', 'file_digest', 'content_digest', 'semantic_category'))
                or not isinstance(row['expected_construct'], list)
                or not row['expected_construct']
                or not all(isinstance(token, str) and token in TOKENS.findall(token)
                           for token in row['expected_construct'])
                or row['category'] not in self.patterns or row['semantic_category'] not in SEMANTICS
                or not DIGEST.fullmatch(row['file_digest']) or not DIGEST.fullmatch(row['content_digest'])):
            raise ValueError
        key = (relative, row['category'], row['content_digest'])
        if key in self.rows:
            raise ValueError
        self.rows[key] = row

    def match(self, relative, category, line, raw):
        digest = hashlib.sha256(line.encode()).hexdigest()
        key = relative, category, digest
        row = self.rows.get(key)
        if not row or evidence_path(relative):
            return False
        pattern = self.patterns.get(category)
        lines = raw.decode('utf-8', errors='replace').splitlines()
        if (pattern is None or hashlib.sha256(raw).hexdigest() != row['file_digest']
                or construct(line) != row['expected_construct']
                or sum(candidate == line and bool(pattern.search(candidate)) for candidate in lines) != row['occurrences']
                or not semantic_ok(row['semantic_category'], category, line, relative, pattern, raw.decode('utf-8', errors='replace'))):
            return False
        self.used.add(key)
        return True

    def match_sql(self, relative, raw):
        text = raw.decode('utf-8', errors='replace')
        matches = list(SQL_DUMP.finditer(text))
        if len(matches) != 1:
            return False
        line = text.splitlines()[text[:matches[0].start()].count('\n')]
        return self.match(relative, 'data-dump-candidate', line, raw)

    def unresolved(self):
        return self.errors + [{'file': row['file'], 'line': row['line'], 'category': 'stale-or-invalid-source-review'}
                              for key, row in self.rows.items() if key not in self.used]
