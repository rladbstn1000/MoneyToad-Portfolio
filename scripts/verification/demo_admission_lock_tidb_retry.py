#!/usr/bin/env python3
"""Separately authorized retry; only the public evidence namespace differs."""
import demo_admission_lock_tidb as probe


def main():
    previous = probe.OUTPUT
    probe.OUTPUT = probe.ROOT / 'docs/deployment/evidence/DEMO_ADMISSION_LOCK_TIDB_RETRY'
    try:
        return probe.main()
    finally:
        probe.OUTPUT = previous


if __name__ == '__main__':
    raise SystemExit(main())
