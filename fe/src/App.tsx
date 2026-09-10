import { scrollLandingAssets, leakPotAssets, userInfoAssets, chartAssets, mypageAssets, notFoundAssets, toadAdviceAssets } from "./assets/pageAssets";
import { authMode } from './auth/authMode';
import { DemoComingSoon } from './components/DemoAuthStatus';
import { useEffect, useState } from "react";
import { useLocation, Routes, Route, Navigate } from "react-router-dom";
import ScrollLandingPage from "./pages/ScrollLandingPage";
import LeakPotPage from "./pages/LeakPotPage";
import UserInfoInputPage from "./pages/UserInfoInputPage";
import ChartPage from "./pages/ChartPage";
import AuthCallback from "./pages/AuthCallback";
import Mypage from "./pages/Mypage";
import NotFound from "./pages/NotFound";
import RouteGuard from "./components/RouteGuard";
import LoadingOverlay from "./components/LoadingOverlay";
import ToadAdvice from "./pages/ToadAdvice";


const allAssets = authMode === 'demo' ? [...scrollLandingAssets, ...chartAssets] : [
  ...scrollLandingAssets,
  ...userInfoAssets,
  ...leakPotAssets,
  ...mypageAssets,
  ...notFoundAssets,
  ...toadAdviceAssets,
  ...chartAssets,
];

export default function App() {
  const location = useLocation();
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (location.pathname.startsWith("/pot/")) {
      setLoading(false);         
      return;
    }

    setLoading(true);
    Promise.all(
      allAssets.map(
        (src) =>
          new Promise<void>((resolve) => {
            if (src.endsWith(".json")) {
              fetch(src).then(() => resolve()).catch(() => resolve());
            } else {
              const img = new Image();
              img.src = src;
              img.onload = () => resolve();
              img.onerror = () => resolve();
            }
          })
      )
    ).finally(() => setLoading(false));
  }, [location]);

  return (
    <>
      {loading && <LoadingOverlay />}
      {authMode === 'demo' ? <Routes>
        <Route path="/" element={<ScrollLandingPage />} />
        <Route path="/chart" element={<RouteGuard><ChartPage /></RouteGuard>} />
        <Route path="/auth/callback" element={<Navigate to="/" replace />} />
        <Route path="*" element={<DemoComingSoon />} />
      </Routes> : <Routes>
        <Route path="/" element={<ScrollLandingPage />} />
        <Route path="/userInfo" element={<RouteGuard><UserInfoInputPage /></RouteGuard>} />
        <Route path="/pot/:month" element={<RouteGuard><LeakPotPage /></RouteGuard>} />
        <Route path="/chart" element={<RouteGuard><ChartPage /></RouteGuard>} />
        <Route path="/auth/callback" element={<AuthCallback />} />
        <Route path="toadAdvice" element={<RouteGuard><ToadAdvice></ToadAdvice></RouteGuard>} />
        <Route path="/mypage" element={<RouteGuard><Mypage /></RouteGuard>} />
        <Route path="*" element={<NotFound />} />
      </Routes>}
    </>
  );
}
