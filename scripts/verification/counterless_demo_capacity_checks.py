#!/usr/bin/env python3
"""Counterless capacity checks using the reviewed owned local regression boundary.

Provider input/state is never loaded. Existing historical evidence is read for
preservation checks, and only COUNTERLESS_DEMO_CAPACITY receives new summaries.
"""
import demo_capacity_checks


def main():
    return demo_capacity_checks.main(evidence_directory='COUNTERLESS_DEMO_CAPACITY')


if __name__ == '__main__':
    raise SystemExit(main())
