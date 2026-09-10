import { NavLink, useNavigate } from 'react-router-dom';
import { logoutDemo } from '../auth/demoCoordinator';
import { useAuthStore } from '../store/authStore';
import './Header.css';

export default function DemoHeader() {
  const operation = useAuthStore(state => state.operation);
  const navigate = useNavigate();
  const logout = async () => {
    try { await logoutDemo(); navigate('/', { replace: true }); }
    catch { /* RouteGuard displays the coordinator's unavailable state. */ }
  };
  return <header className="app-header"><nav className="nav" aria-label="체험 메뉴">
    <NavLink to="/" className="nav-item">마당</NavLink>
    <NavLink to="/chart" className="nav-item">콩쥐의 씀씀이</NavLink>
    <button className="nav-item logout-btn" type="button" disabled={operation === 'logout'} onClick={() => void logout()}>체험 종료</button>
  </nav></header>;
}
