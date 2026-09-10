# Independent verification

This is a history-free snapshot. No Git repository is needed to run its verification entrypoint. Product and existing test assertions are preserved; public preparation changes only verification output boundaries, portable fixtures, environment examples and documentation.

## Prerequisites

Use Java 21, Node 22, Python 3, local Docker with an already-present MySQL 8.4 image, redis-server and an explicitly installed Playwright Chromium matching the locked version. The current service runner supports macOS Java discovery and the local Docker Unix socket. It does not pull images or load ambient application environment files.

Install the snapshot's exact npm manifest/lock in a separate owned dependency directory. Replace angle-bracket placeholders before running:

```sh
mkdir -p "<dependency-directory>"
cp fe/package.json fe/package-lock.json "<dependency-directory>/"
npm ci --prefix "<dependency-directory>" --ignore-scripts --no-audit --no-fund \
  --cache "<owned-npm-cache>" --registry https://registry.npmjs.org
PLAYWRIGHT_BROWSERS_PATH="<browser-directory>" \
  "<dependency-directory>/node_modules/.bin/playwright" install chromium
```

Browser provisioning is separate from the no-external-application E2E scenario. The Gradle cache argument must point to a trusted dependency cache containing `wrapper/` and `caches/`, not an original project checkout. Do not copy the original project's node_modules. Keep dependency installs, Chromium, Gradle caches and runtime files outside the snapshot.

From the snapshot root:

```sh
python3 -B scripts/verification/public_snapshot.py --phase all \
  --cache-seed <verification-gradle-cache> \
  --browser-path <playwright-browser-directory> \
  --dependencies <installed-dependency-directory>
python3 -B scripts/verification/public_scan.py
```

The entrypoint temporarily links that owned dependency installation and removes the link in finally. The backend runner uses dedicated resources and a strict 275-test selector/count contract. Frontend checks require 125 OAuth plus 51 demo tests, product/test/E2E types, both builds, invalid auth-mode rejection and lint zero. Browser tests run twice with fresh resources, workers=1 and retries=0.

The browser uses HTTPS same-origin proxying for the core scenario and separate HTTP cookie/CORS cases. Product API responses are not mocked. Test-only font fallback blocks external font downloads. Screenshots mask the header. Application egress outside owned loopback resources fails the tests.

## Public evidence policy

Only explicit projections enter `evidence/`: test counts/status, contract assertions, row counts, normalized paths, cookie attributes and cleanup results. Runtime objects, raw logs, temporary paths and database identities are not serialized. Scanning fails on forbidden fields or unclassified value candidates. Reviewed source-code expressions and synthetic unit-test fixture sites are classified separately from execution evidence.

The compact lint fixture records historical rule findings without old runtime metadata. Earlier verification scripts remain for their imported selector/safety policies; the public entrypoint is the supported current-count invocation.

New execution results and environment interruptions are distinguished in [the commit snapshot audit](16-public-commit-plan.md). Tests are not passed by weakening assertions or suppressing skips.
