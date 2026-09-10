# Snapshot verification rules

Read docs/portfolio/verification-summary.md and architecture-notes.md before running checks. This snapshot intentionally has no Git metadata and no original raw evidence.

Use the public_snapshot.py entrypoint with explicit dependency/cache/browser arguments and dedicated local MySQL/Redis. Do not use shared services, ambient env files, external OAuth/AI, API mocks or weakened assertions. Runtime credentials and resource identity must never enter public evidence. Run public_scan.py after changes. Keep fonts, unverified CSVs, raw logs and private keys outside this tree.
