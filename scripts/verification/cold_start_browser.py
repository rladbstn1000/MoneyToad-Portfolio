#!/usr/bin/env python3
"""One real Chromium recovery flow; only initial ready503 is a server fixture."""
import json
import shutil

import mobile_chart_browser as boundary
from public_evidence import forbidden


def worker(args):
    import demo_browser_e2e as harness
    runs = []
    code = harness.run_once(args, runs, modes=('public-demo',), browser_config='playwright.cold-start.config.ts',
                            expected_cases=1, copy_screenshots=False, cold_start_fixture=True)
    summaries = [summary for _, summary in runs]
    result = {'status': 'FAIL', 'cleanup_complete': bool(summaries) and all(row['cleanup_complete'] for row in summaries),
              'managed_provider_used': False, 'browser_api_mocks': False,
              'initial_ready_server_fixture': True, 'workers': 1, 'retries': 0,
              'external_attempts': 0, 'cases': 0,
              'setup_diagnostics': [{key: summary[key] for key in ('status', 'failed_phase',
                  'failure_classes', 'failure_tags', 'diagnostic') if key in summary} for summary in summaries]}
    for summary in summaries:
        for mode in summary['modes']:
            if mode['mode'] == 'public-demo':
                result['cases'] += len(mode.get('cases', []))
                result['tests'] = mode.get('cases', [])
                result['external_attempts'] += sum(row['external_attempts'] + row['backend_attempts'] for row in mode['networks'])
    output = boundary.ROOT / 'mobile-result'
    output.mkdir()
    for folder in harness.OUT.glob('*/public-demo'):
        for filename in ('cold-contract.json', 'cold-network.json', 'cold-failed-check.json', 'tests.json'):
            path = folder / filename
            if not path.is_file(): continue
            value = json.loads(path.read_text())
            if forbidden(value): raise ValueError('COLD_EVIDENCE_REJECTED')
            (output / filename).write_text(json.dumps(value, ensure_ascii=False, indent=2))
            if filename == 'cold-contract.json': result['contract'] = value
        screenshot = folder / 'cold-recovery-390.png'
        if screenshot.is_file():
            contents = screenshot.read_bytes()
            if contents[:8] != b'\x89PNG\r\n\x1a\n': raise ValueError('SCREENSHOT_FORMAT_REJECTED')
            offset = 8
            while offset < len(contents):
                length = int.from_bytes(contents[offset:offset + 4], 'big')
                if contents[offset + 4:offset + 8] in (b'tEXt', b'zTXt', b'iTXt', b'eXIf'):
                    raise ValueError('SCREENSHOT_METADATA_REJECTED')
                offset += length + 12
            shutil.copy2(screenshot, output / screenshot.name)
    passed = (code == 0 and result['cases'] == 1 and 'contract' in result and result['external_attempts'] == 0
              and result['cleanup_complete'] and all(case['status'] == 'passed' for case in result.get('tests', [])))
    result['status'] = 'PASS' if passed else 'FAIL'
    if forbidden(result): raise ValueError('COLD_SUMMARY_REJECTED')
    (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if passed else 1


def main():
    return boundary.main(evidence_directory='COLD_START_RECOVERY',
        entrypoint='scripts/verification/cold_start_browser.py', worker_function=worker, phase_choices=('verify',))

if __name__ == '__main__':
    raise SystemExit(main())
