import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../store/authStore';
import { loginDemo, logoutDemo, restoreDemo } from '../auth/demoCoordinator';
import './DemoAuthStatus.css';

export default function DemoAuthStatus({ landing = false }: { landing?: boolean }) {
  const { status, operation, message, recovery } = useAuthStore();
  const navigate = useNavigate();
  const busy = status === 'restoring' || operation !== null;
  const act = async () => {
    try {
      if (status === 'authenticated') { navigate('/chart'); return; }
      if (status === 'unavailable') {
        if (recovery === 'logout') { await logoutDemo(); return; }
        await restoreDemo();
      } else await loginDemo();
      if (useAuthStore.getState().status === 'authenticated') navigate('/chart');
    } catch { /* The coordinator publishes a sanitized, recoverable state. */ }
  };
  const label = busy ? operation === 'logout' ? '체험 종료 확인 중…' : '체험 연결 확인 중…'
    : status === 'authenticated' ? '체험 이어가기'
    : status === 'unavailable' ? recovery === 'logout' ? '종료 다시 시도' : '연결 다시 시도'
    : '샘플 데이터로 체험하기';
  return <section className={landing ? 'demo-auth demo-auth-landing' : 'demo-auth'} aria-label="체험 인증">
    {message && <p role="status">{message}</p>}
    {status === 'restoring' && <p role="status">체험 연결을 확인하고 있습니다.</p>}
    <button type="button" disabled={busy} onClick={() => void act()}>{label}</button>
    {!landing && <Link to="/">마당으로</Link>}
  </section>;
}

export function DemoComingSoon() {
  return <main className="demo-auth"><h1>준비 중입니다</h1>
    <p>현재 체험에서는 씀씀이 화면을 이용할 수 있습니다.</p><Link to="/chart">씀씀이로</Link><Link to="/">마당으로</Link></main>;
}
