#!/usr/bin/env python3
"""Local-only FE checks: explicit existing dependencies, owned static servers, no backend."""
import argparse
import functools
import hashlib
import http.server
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
from dependency_overlay import DependencyOverlay
from public_evidence import forbidden

ROOT = Path(__file__).resolve().parents[2]

def copy_frontend(destination):
    for path in (ROOT / 'fe').rglob('*'):
        rel = path.relative_to(ROOT / 'fe')
        if set(rel.parts) & {'node_modules', 'dist', 'dist-local', 'test-results', 'playwright-report', 'blob-report'}:
            continue
        if path.name.startswith('.env') and path.name != '.env.example':
            continue
        if path.is_file():
            if path.is_symlink(): raise ValueError('LINKED_SOURCE_REJECTED')
            target = destination / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)

def dependency_capsule(frontend, dependencies, work):
    # Only scripts changed; install metadata and the complete lock remain exact.
    left, right = [json.loads((p / 'package.json').read_text()) for p in (frontend, dependencies)]
    for field in set(left) | set(right):
        if field != 'scripts' and left.get(field) != right.get(field): raise ValueError('DEPENDENCY_CONTRACT_MISMATCH')
    if (frontend / 'package-lock.json').read_bytes() != (dependencies / 'package-lock.json').read_bytes():
        raise ValueError('LOCKFILE_MISMATCH')
    capsule = work / 'dependencies'; capsule.mkdir()
    for name in ('package.json', 'package-lock.json'): shutil.copy2(frontend / name, capsule / name)
    (capsule / 'node_modules').symlink_to(dependencies / 'node_modules', target_is_directory=True)
    return capsule

class OwnedHTTPServer(http.server.ThreadingHTTPServer):
    # Intentional navigation can close asset sockets; other handler errors fail verification.
    def handle_error(self, *_):
        self.handler_errors += 1

class Static(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *_): pass
    def copyfile(self, source, output):
        try:super().copyfile(source, output)
        except (BrokenPipeError, ConnectionResetError):self.server.cancelled_assets += 1
    def do_GET(self):
        path = self.path.split('?', 1)[0]
        if path.startswith('/api/'):
            self.server.api_attempts += 1
            self.send_error(500); return
        if not Path(self.translate_path(path)).is_file() and '.' not in Path(path).name:
            self.path = '/index.html'
        super().do_GET()

class Away(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        self.send_response(200);self.send_header('Content-Type','text/html; charset=utf-8');self.end_headers()
        self.wfile.write(b'<!doctype html><title>Owned navigation boundary</title><p>Local history check</p>')

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--phase', choices=('fe','browser'), required=True)
    p.add_argument('--dependencies', type=Path, required=True)
    p.add_argument('--browser-path', type=Path)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    output = args.output.resolve()
    if output.exists(): p.error('Choose a new output directory')
    if args.phase == 'browser' and not args.browser_path: p.error('Explicit installed Chromium required')
    output.mkdir(parents=True)
    work = Path(tempfile.mkdtemp(prefix='moneytoad-static-check-'))
    frontend = work / 'fe';frontend.mkdir()
    copy_frontend(frontend)
    before = {str(f.relative_to(frontend)):hashlib.sha256(f.read_bytes()).hexdigest() for f in frontend.rglob('*') if f.is_file()}
    env = {'PATH':'/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin','HOME':str(work/'home'),
           'LANG':'en_US.UTF-8','NO_COLOR':'1','CI':'1','TMPDIR':str(work),'PYTHONDONTWRITEBYTECODE':'1'}
    (work/'home').mkdir()
    overlay = None; child = None; servers = []; threads=[]; browser_gone=True
    result = {'status':'FAIL','phase':args.phase,'checks':[],'backend_started':False,'provider_connections':0}
    previous = {}
    def interrupted(*_):raise KeyboardInterrupt()
    def command(label, cmd, extra=None, deadline=240):
        nonlocal child
        child = subprocess.Popen(cmd,cwd=frontend,env={**env,**(extra or {})},stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,start_new_session=True)
        raw,_ = child.communicate(timeout=deadline)
        (work/(label+'.txt')).write_text(raw)
        return child.returncode,raw
    try:
        for sig in (signal.SIGINT,signal.SIGTERM):previous[sig]=signal.signal(sig,interrupted)
        capsule=dependency_capsule(frontend,args.dependencies.resolve(),work)
        overlay=DependencyOverlay(frontend,capsule).create()
        if args.phase=='fe':
            # Measure the actual pure generator separately from build and browser downloads.
            module=work/'scenario.mjs'
            code,_=command('scenario-module',['./node_modules/.bin/esbuild','src/demo/localDemoScenario.ts','--bundle','--platform=node','--format=esm','--outfile='+str(module)])
            if code!=0:raise ValueError('SCENARIO_MEASUREMENT_PREPARATION_FAILED')
            measurement="import {createLocalDemoScenario} from "+json.dumps(module.as_uri())+"; const start=performance.now(); const value=createLocalDemoScenario(); console.log(JSON.stringify({runtime:'local Node pure generator',milliseconds:performance.now()-start,transactions:value.transactions.length,budgets:value.budgets.length}));"
            code,raw=command('scenario-generation',['node','--input-type=module','-e',measurement])
            measured=json.loads(raw)
            (output/'scenario-timing.json').write_text(json.dumps(measured,indent=2)+'\n')
            result['checks'].append({'check':'scenario-generation','status':'PASS' if code==0 and measured['transactions']==240 and measured['budgets']==72 else 'FAIL'})
            for mode,script,minimum in [('oauth','test:run',239),('remote-demo','test:demo',234),('local-demo','test:local',1)]:
                report=work/(mode+'.json')
                code,_=command(mode,['npm','run',script,'--','--reporter=json','--outputFile='+str(report)])
                data=json.loads(report.read_text())
                row={'check':mode,'status':'PASS' if code==0 and data['numTotalTests']>=minimum and data['numTotalTests']==data['numPassedTests'] and data['numPendingTests']==data['numTodoTests']==0 else 'FAIL',
                     'tests':data['numTotalTests'],'passed':data['numPassedTests'],'failed':data['numFailedTests'],'skipped':data['numPendingTests'],'todo':data['numTodoTests']}
                result['checks'].append(row);print(json.dumps(row),flush=True)
            for label,cmd in [('product-types',['./node_modules/.bin/tsc','-b']),('test-types',['npm','run','test:check']),('e2e-types',['npm','run','test:e2e:check']),('functions-types',['./node_modules/.bin/tsc','-p','tsconfig.functions.json','--noEmit']),('lint',['npm','run','lint','--','--max-warnings','0','--format','json'])]:
                code,raw=command(label,cmd);row={'check':label,'status':'PASS' if code==0 else 'FAIL'}
                if label=='lint':
                    data=json.loads(raw[raw.index('[{'):]);row.update(errors=sum(v['errorCount'] for v in data),warnings=sum(v['warningCount'] for v in data))
                result['checks'].append(row);print(json.dumps(row),flush=True)
            config=frontend/'owned-build.config.ts'
            config.write_text("import base from './vite.config';\nexport default {...base,envDir:false};\n")
            for mode,extra in [('oauth',{'VITE_AUTH_MODE':'oauth'}),('remote-demo',{'VITE_AUTH_MODE':'demo','VITE_DEMO_DATA_MODE':'remote'})]:
                code,_=command(mode+'-build',['npm','run','build','--','--config',str(config)],{'VITE_BACK_URL':'http://127.0.0.1:18080',**extra})
                result['checks'].append({'check':mode+'-build','status':'PASS' if code==0 else 'FAIL'})
            for i,extra in enumerate([{'VITE_AUTH_MODE':'invalid'},{'VITE_AUTH_MODE':'demo','VITE_DEMO_DATA_MODE':''},{'VITE_AUTH_MODE':'demo','VITE_DEMO_DATA_MODE':' '},{'VITE_AUTH_MODE':'demo','VITE_DEMO_DATA_MODE':'invalid'},{'VITE_AUTH_MODE':'oauth','VITE_DEMO_DATA_MODE':'local'}]):
                code,raw=command('invalid-'+str(i),['./node_modules/.bin/vite','build','--config',str(config)],extra)
                result['checks'].append({'check':'invalid-mode-'+str(i),'status':'PASS' if code!=0 and 'VITE_' in raw else 'FAIL'})
        code,_=command('local-build',['npm','run','build:demo-local'])
        build=frontend/'dist-local'
        static=code==0 and (build/'index.html').is_file() and (build/'_redirects').read_text()=='/* /index.html 200\n' and not any((build/n).exists() for n in ['_routes.json','_worker.js','functions'])
        result['checks'].append({'check':'static-local-build','status':'PASS' if static else 'FAIL','backend_env_provided':False,'function_artifacts_present':False if static else None})
        if args.phase=='browser' and static:
            # Test-only entry exercises the same visualization with all supported categories.
            fixture=build/'__visual__';fixture.mkdir()
            code,_=command('pot-fixture',['./node_modules/.bin/esbuild','e2e/pot-visual-fixture.tsx','--bundle','--main-fields=module,browser,main','--external:/leakPot/*','--format=esm','--jsx=automatic','--outfile='+str(fixture/'pot.js'),
                '--define:import.meta.env={"VITE_AUTH_MODE":"demo","VITE_DEMO_DATA_MODE":"local","DEV":false}', '--define:process.env.NODE_ENV="production"'])
            if code!=0:raise ValueError('VISUAL_FIXTURE_BUILD_FAILED')
            (fixture/'pot.html').write_text('<!doctype html><html lang="ko"><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Local visual fixture</title><link rel="stylesheet" href="/assets-placeholder.css"><link rel="stylesheet" href="/\x5f\x5fvisual__/pot.css"><style>body{margin:0;font-family:system-ui}nav{display:flex;flex-wrap:wrap;gap:8px}button{min-height:48px}.pot-visual-fixture{height:100dvh;display:flex;flex-direction:column;overflow:visible}.pot-visual-fixture>.demo-pot-stage{width:100%;max-width:1000px;min-height:0;flex:1;container-type:size;align-self:center;display:grid;place-items:center}.pot-visual-fixture .pot-visualization{width:min(100%,130cqh)}h1{font-size:20px}</style><div id="root"></div><script type="module" src="/\x5f\x5fvisual__/pot.js"></script></html>'.replace('<link rel="stylesheet" href="/assets-placeholder.css">',''))
            handler=functools.partial(Static,directory=str(build))
            for kind in [handler,Away]:
                server=OwnedHTTPServer(('127.0.0.1',0),kind);server.api_attempts=0;server.handler_errors=0;server.cancelled_assets=0;servers.append(server)
                thread=threading.Thread(target=server.serve_forever,daemon=True);threads.append(thread);thread.start()
            origin='http://127.0.0.1:'+str(servers[0].server_port)
            # A readiness connection proves this owned server accepts sockets; no arbitrary sleep.
            deadline=time.monotonic()+10
            while True:
                try:
                    with socket.create_connection(('127.0.0.1',servers[0].server_port),timeout=.3):break
                except OSError:
                    if time.monotonic()>deadline:raise ValueError('STATIC_READINESS_FAILED')
            artifact=work/'artifacts';artifact.mkdir()
            code,_=command('browser',['./node_modules/.bin/playwright','test','--config','playwright.local.config.ts'],
                {'PLAYWRIGHT_BROWSERS_PATH':str(args.browser_path.resolve()),'E2E_ORIGIN':origin,'E2E_AWAY_ORIGIN':'http://127.0.0.1:'+str(servers[1].server_port),'E2E_WORK':str(work),'E2E_ARTIFACTS':str(artifact)},deadline=420)
            report=json.loads((artifact/'tests.json').read_text())
            result['checks'].append({'check':'chromium-static','status':'PASS' if code==0 and report['status']=='passed' and report['globalErrors']==0 and len(report['cases'])==16 and all(v['status']=='passed' for v in report['cases']) else 'FAIL', 'cases':len(report['cases']),'global_errors':report['globalErrors'],'exit_code':code})
            result['api_attempts_at_static_server']=servers[0].api_attempts
            result['cancelled_static_responses']=sum(server.cancelled_assets for server in servers)
            result['static_handler_errors']=sum(server.handler_errors for server in servers)
            result['checks'].append({'check':'static-server-boundary','status':'PASS' if result['api_attempts_at_static_server']==0 and result['static_handler_errors']==0 else 'FAIL'})
            for path in artifact.glob('*.json'):
                value=json.loads(path.read_text())
                if forbidden(value):raise ValueError('PUBLIC_RESULT_REJECTED')
                shutil.copy2(path,output/path.name)
            # These screenshots contain only local sample data. Their metadata is checked before publication.
            for path in artifact.glob('after-*.png'):shutil.copy2(path,output/path.name)
        result['status']='PASS' if all(row['status']=='PASS' for row in result['checks']) else 'FAIL'
    except (KeyboardInterrupt,Exception) as error:
        result['diagnostic']=type(error).__name__
    finally:
        if child and child.poll() is None:
            os.killpg(child.pid,signal.SIGTERM)
            try:child.communicate(timeout=20)
            except subprocess.TimeoutExpired:os.killpg(child.pid,signal.SIGKILL);child.communicate()
        ledger=work/'browser-owned.jsonl'
        if ledger.is_file():
            for line in ledger.read_text().splitlines():
                pid=json.loads(line)['pid']
                try:os.killpg(pid,0)
                except ProcessLookupError:continue
                os.killpg(pid,signal.SIGTERM)
                for _ in range(40):
                    try:os.killpg(pid,0)
                    except ProcessLookupError:break
                    time.sleep(.1)
                else:browser_gone=False
        for server in servers:server.shutdown();server.server_close()
        for thread in threads:thread.join(timeout=5)
        if overlay:overlay.close()
        result['cleanup_complete']=browser_gone and all(not t.is_alive() for t in threads) and (child is None or child.poll() is not None) and (overlay is None or all(overlay.report.values()))
        result['input_copy_preserved']=all((frontend/f).is_file() and hashlib.sha256((frontend/f).read_bytes()).hexdigest()==v for f,v in before.items())
        if not result['cleanup_complete'] or not result['input_copy_preserved']:result['status']='FAIL'
        for sig,handler in previous.items():signal.signal(sig,handler)
        if forbidden(result):raise ValueError('PUBLIC_SUMMARY_REJECTED')
        (output/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        # Private setup logs remain outside the source/evidence for local failure diagnosis.
        print(json.dumps({'status':result['status'],'cleanup_complete':result['cleanup_complete'],'private_work':str(work)}),flush=True)
    return 0 if result['status']=='PASS' else 1

if __name__=='__main__':raise SystemExit(main())
