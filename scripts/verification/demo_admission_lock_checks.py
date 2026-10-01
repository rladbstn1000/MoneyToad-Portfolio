#!/usr/bin/env python3
"""Admission lock checks through the existing owned local verification boundary.

Provider input/state is never loaded. Only DEMO_ADMISSION_LOCK receives new
summaries; earlier capacity evidence remains unchanged.
"""
import demo_capacity_checks


def main():
    return demo_capacity_checks.main(evidence_directory='DEMO_ADMISSION_LOCK')


if __name__ == '__main__':
    raise SystemExit(main())
