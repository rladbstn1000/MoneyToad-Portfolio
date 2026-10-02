import { scrollLandingAssets, leakPotAssets, userInfoAssets, chartAssets, mypageAssets, notFoundAssets, toadAdviceAssets } from "./assets/pageAssets";
import { authMode } from './auth/authMode';
import DemoPeriodEntry from './demo/DemoPeriodEntry';
import DemoExperienceProvider from './demo/DemoExperienceProvider';
import { demoPageAssets } from './demo/demoPageAssets';
import { useAuthStore } from './store/authStore';
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


const allAssets = [
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
  const generation = useAuthStore(state => state.generation);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (authMode !== 'demo' && location.pathname.startsWith("/pot/")) {
      setLoading(false);         
      return;
    }

    let cancelled = false;
    setLoading(true);
    const assets = authMode === 'demo' ? demoPageAssets(location.pathname) : allAssets;
    Promise.all(
      assets.map(
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
    ).finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [location]);

  return (
    <>
      {loading && <LoadingOverlay />}
      {authMode === 'demo' ? <DemoExperienceProvider key={generation}><Routes>
        <Route path="/" element={<ScrollLandingPage />} />
        <Route path="/pot" element={<RouteGuard><DemoPeriodEntry /></RouteGuard>} />
        <Route path="/pot/:month" element={<RouteGuard><LeakPotPage /></RouteGuard>} />
        <Route path="/chart" element={<RouteGuard><ChartPage /></RouteGuard>} />
        <Route path="/toadAdvice" element={<RouteGuard><ToadAdvice /></RouteGuard>} />
        <Route path="/mypage" element={<RouteGuard><Mypage /></RouteGuard>} />
        <Route path="/userInfo" element={<RouteGuard><UserInfoInputPage /></RouteGuard>} />
        <Route path="/auth/callback" element={<Navigate to="/" replace />} />
        <Route path="*" element={<NotFound />} />
      </Routes></DemoExperienceProvider> : <Routes>
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
