import { Link, NavLink, useMatch, useNavigate } from "react-router-dom";
import { useLogoutMutation } from "../api/services/auth";
import "./Header.css";
import axios from "axios";

export default function Header() {
  const thisMonth = new Date().getMonth() + 1; // 1..12
  const potPath = `/pot/${thisMonth}`;

  const potActive = Boolean(useMatch("/pot/:month"));

  const logoutMutation = useLogoutMutation();
  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      onSettled: () => {
        localStorage.removeItem("accessToken");
        delete axios.defaults.headers.common.Authorization;
        navigate("/", { replace: true });
      },
    });
  };

  const navigate = useNavigate();

  return (
    <header className="app-header">
      <nav className="nav">
        <NavLink to="/" className={({isActive}) => `nav-item ${isActive ? "active" : ""}`}>
          마당
        </NavLink>

        <Link to={potPath} className={`nav-item ${potActive ? "active" : ""}`}>
          콩쥐의 장독대
        </Link>

        <NavLink to="/chart" className={({isActive}) => `nav-item ${isActive ? "active" : ""}`}>
          콩쥐의 씀씀이
        </NavLink>

        <NavLink to="/toadAdvice" className={({isActive}) => `nav-item ${isActive ? "active" : ""}`}>
          두꺼비의 조언
        </NavLink>

        <NavLink to="/mypage" className={({isActive}) => `nav-item ${isActive ? "active" : ""}`}>
          콩쥐의 곳간
        </NavLink>

        <button onClick={handleLogout} className="nav-item logout-btn" type="button">
          로그아웃
        </button>
      </nav>
    </header>
  );
}
