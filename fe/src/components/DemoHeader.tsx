import { useEffect, useRef, useState } from 'react';
import { NavLink, useLocation, useNavigate } from 'react-router-dom';
import { logoutDemo } from '../auth/demoCoordinator';
import { useAuthStore } from '../store/authStore';
import './Header.css';
import { isLocalDemo } from '../auth/authMode';
import { useLocalDemoStore } from '../demo/localDemoStore';
import LocalDemoNotice from '../demo/LocalDemoNotice';

export default function DemoHeader() {
  const operation = useAuthStore(state => state.operation);
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const toggle = useRef<HTMLButtonElement>(null);
  useEffect(() => { setOpen(false); }, [location.pathname]);
  useEffect(() => {
    if (!open) return;
    const close = (event: KeyboardEvent) => {
      if (event.key === 'Escape') { setOpen(false); toggle.current?.focus(); }
    };
    window.addEventListener('keydown', close);
    return () => window.removeEventListener('keydown', close);
  }, [open]);
  const logout = async () => {
    if (isLocalDemo) { useLocalDemoStore.getState().end(); navigate('/', { replace: true }); return; }
    try { await logoutDemo(); navigate('/', { replace: true }); }
    catch { /* The existing guard displays the recoverable logout state. */ }
  };
  return <header className="app-header demo-header"><nav className="nav" aria-label="체험 메뉴">
    <button ref={toggle} type="button" className="demo-menu-toggle" aria-expanded={open}
      aria-controls="demo-menu-links" onClick={() => setOpen(value => !value)}>체험 메뉴</button>
    <div id="demo-menu-links" className={`demo-menu-links${open ? ' is-open' : ''}`}>
      <NavLink to="/" className="nav-item">마당</NavLink>
      <NavLink to="/pot" className="nav-item">콩쥐의 장독대</NavLink>
      <NavLink to="/chart" className="nav-item">콩쥐의 씀씀이</NavLink>
      <NavLink to="/toadAdvice" className="nav-item">두꺼비의 조언</NavLink>
      <NavLink to="/mypage" className="nav-item">콩쥐의 곳간</NavLink>
      {isLocalDemo && <button className="nav-item logout-btn" type="button" onClick={() => useLocalDemoStore.getState().reset()}>처음부터 다시하기</button>}
      <button className="nav-item logout-btn" type="button" disabled={!isLocalDemo && operation === 'logout'} onClick={() => void logout()}>체험 종료</button>
      {isLocalDemo && <LocalDemoNotice />}
    </div>
  </nav></header>;
}
