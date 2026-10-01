import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { loginDemo, logoutDemo, manualCheckDemoReady, restoreDemo } from '../auth/demoCoordinator';
import './DemoAuthStatus.css';

export default function DemoAuthStatus({ landing = false }: { landing?: boolean }) {
  const { status, operation, message, recovery, loginRetryAt, readinessRetryAt } = useAuthStore();
  const navigate = useNavigate();
  const [now, setNow] = useState(Date.now);
  const retryAt = status === 'startupSlow' ? readinessRetryAt : status === 'anonymous' ? loginRetryAt : null;
  useEffect(() => {
    if (retryAt === null || retryAt <= Date.now()) return;
    // A UI clock only: reaching zero never dispatches an auth request.
    const timer = setInterval(() => {
      const current = Date.now();
      setNow(current);
      if (current >= retryAt) clearInterval(timer);
    }, 250);
    return () => clearInterval(timer);
  }, [retryAt]);
  // Returning from a status without an active UI timer must still observe an
  // already-expired deadline instead of retaining the last rendered clock value.
  const remaining = retryAt === null ? 0 : Math.max(0, Math.ceil((retryAt - Math.max(now, Date.now())) / 1_000));
  const coolingDown = (status === 'anonymous' || status === 'startupSlow') && remaining > 0;
  const busy = status === 'restoring' || operation !== null;
  const act = async () => {
    try {
      if (status === 'startupSlow') { await manualCheckDemoReady(); return; }
      if (status === 'authenticated') { navigate('/chart'); return; }
      if (status === 'unavailable') {
        if (recovery === 'logout') { await logoutDemo(); return; }
        await restoreDemo();
      } else await loginDemo();
      if (useAuthStore.getState().status === 'authenticated') navigate('/chart');
    } catch { /* The coordinator publishes a sanitized, recoverable state. */ }
  };
  const waitingMessage = operation === 'readiness' ? '데모 서버 시작 중입니다. 처음 연결할 때 잠시 걸릴 수 있습니다.'
    : operation === 'login' ? '체험 데이터를 준비하고 있습니다.' : '체험 연결을 확인하고 있습니다.';
  const label = busy ? operation === 'manualReadiness' ? '서버 확인 중…' : operation === 'logout' ? '체험 종료 확인 중…'
    : operation === 'readiness' ? '데모 서버 시작 중…'
    : operation === 'login' ? '체험 데이터 준비 중…' : '체험 연결 확인 중…'
    : status === 'startupSlow' ? '서버 다시 확인'
    : status === 'authenticated' ? '체험 이어가기'
    : status === 'unavailable' ? recovery === 'logout' ? '종료 다시 시도' : '연결 다시 시도'
    : '샘플 데이터로 체험하기';
  return <section className={landing ? 'demo-auth demo-auth-landing' : 'demo-auth'} aria-label="체험 인증">
    {status === 'startupSlow' && <>
      <h2>서버를 시작하고 있습니다</h2>
      <p role="status" aria-live="polite">무료 데모 서버의 시작이 예상보다 오래 걸리고 있습니다. 잠시 후 서버 상태를 다시 확인해 주세요.</p>
    </>}
    {message && <p role="status">{message}</p>}
    {coolingDown && <p role="status">{status === 'startupSlow' ? '잠시 후 버튼으로 다시 확인할 수 있습니다.' : `약 ${remaining}초 후 직접 다시 시도할 수 있습니다.`}</p>}
    {status === 'restoring' && <p role="status">{waitingMessage}</p>}
    <button type="button" disabled={busy || coolingDown} onClick={() => void act()}>{label}</button>
    {!landing && <Link to="/">마당으로</Link>}
  </section>;
}

export function DemoComingSoon() {
  return <main className="demo-auth"><h1>준비 중입니다</h1>
    <p>현재 체험에서는 씀씀이 화면을 이용할 수 있습니다.</p><Link to="/chart">씀씀이로</Link><Link to="/">마당으로</Link></main>;
}
