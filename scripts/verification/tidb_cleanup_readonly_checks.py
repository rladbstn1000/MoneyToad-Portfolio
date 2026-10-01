#!/usr/bin/env python3
"""Local cleanup compatibility checks; no provider input or state is loaded."""
import demo_capacity_checks


def main():
    return demo_capacity_checks.main(evidence_directory='TIDB_CLEANUP_READONLY')


if __name__ == '__main__':
    raise SystemExit(main())
