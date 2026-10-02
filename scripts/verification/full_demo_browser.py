#!/usr/bin/env python3
"""Three full UI journeys using the existing owned native backend/browser harness."""
import json
import shutil
import mobile_chart_browser as boundary
from public_evidence import forbidden


def worker(args):
    import demo_browser_e2e as harness
    runs = []
    code = harness.run_once(args, runs, modes=('public-demo',), browser_config='playwright.full-experience.config.ts',
                            expected_cases=3, copy_screenshots=False, browser_deadline=600)
    summaries = [summary for _, summary in runs]
    result = {'status': 'FAIL', 'cleanup_complete': bool(summaries) and all(row['cleanup_complete'] for row in summaries),
              'managed_provider_used': False, 'browser_api_mocks': False, 'workers': 1, 'retries': 0,
              'external_attempts': 0, 'cases': 0, 'viewport_results': [],
              'setup_diagnostics': [{key: summary[key] for key in ('status', 'failed_phase', 'failure_classes', 'failure_tags', 'diagnostic')
                                    if key in summary} for summary in summaries]}
    for summary in summaries:
        for mode in summary['modes']:
            if mode['mode'] == 'public-demo':
                result['cases'] += len(mode.get('cases', []))
                result['tests'] = mode.get('cases', [])
                result['external_attempts'] += sum(row['external_attempts'] + row['backend_attempts'] for row in mode['networks'])
    output = boundary.ROOT / 'mobile-result'
    output.mkdir()
    for folder in harness.OUT.glob('*/public-demo'):
        for path in folder.glob('*.json'):
            if not (path.name.startswith(('full-', 'failed-')) or path.name.endswith('-network.json') or path.name == 'tests.json'):
                continue
            value = json.loads(path.read_text())
            if forbidden(value): raise ValueError('FULL_EXPERIENCE_EVIDENCE_REJECTED')
            (output / path.name).write_text(json.dumps(value, ensure_ascii=False, indent=2))
            if path.name.startswith('full-') and path.name.endswith('-contract.json'): result['viewport_results'].append(value)
        for path in folder.glob('full-*.png'):
            contents = path.read_bytes()
            if contents[:8] != b'\x89PNG\r\n\x1a\n': raise ValueError('SCREENSHOT_FORMAT_REJECTED')
            offset = 8
            while offset < len(contents):
                length = int.from_bytes(contents[offset:offset + 4], 'big')
                if contents[offset + 4:offset + 8] in (b'tEXt', b'zTXt', b'iTXt', b'eXIf'):
                    raise ValueError('SCREENSHOT_METADATA_REJECTED')
                offset += length + 12
            shutil.copy2(path, output / path.name)
    passed = (code == 0 and result['cases'] == 3 and len(result['viewport_results']) == 3
              and result['external_attempts'] == 0 and result['cleanup_complete']
              and all(case['status'] == 'passed' for case in result.get('tests', [])))
    result['status'] = 'PASS' if passed else 'FAIL'
    if forbidden(result): raise ValueError('FULL_EXPERIENCE_SUMMARY_REJECTED')
    (output / 'summary.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(boundary.main(evidence_directory='FULL_DEMO_EXPERIENCE',
        entrypoint='scripts/verification/full_demo_browser.py', worker_function=worker, phase_choices=('verify',)))
