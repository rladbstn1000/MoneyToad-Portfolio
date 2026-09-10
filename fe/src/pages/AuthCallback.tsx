import { useEffect, useRef, useState } from "react";
import { useNavigate, useLocation } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import { useCardInfoQuery } from "../api/queries/cardQuery";

export default function AuthCallback() {
  const navigate = useNavigate();
  const { search } = useLocation();
  const ran = useRef(false); // StrictMode에서 이펙트 2회 실행 보호
  const [shouldFetchCardInfo, setShouldFetchCardInfo] = useState(false);

  const { data: cardInfo, isLoading, isError } = useCardInfoQuery();

  useEffect(() => {
    if (ran.current) return;
    ran.current = true;

    const params = new URLSearchParams(search);
    const token = params.get("accessToken");

    if (!token) {
      console.error("No access token found in URL");
      navigate("/", { replace: true });
      return;
    }

    // 토큰 저장 (Zustand persist → localStorage)
    useAuthStore.getState().setAccessToken(token);

    // 토큰 저장 후 카드 정보 조회 시작
    setShouldFetchCardInfo(true);
  }, [navigate, search]);

  // 카드 정보 조회 결과에 따른 네비게이션
  useEffect(() => {
    if (!shouldFetchCardInfo) return;

    if (isLoading) return; // 로딩 중이면 대기

    if (isError) {
      navigate("/userInfo", { replace: true });
      return;
    }

    if (cardInfo) {
      // 카드 정보가 있으면 pot 페이지로 이동
      const month = String(new Date().getMonth() + 1);
      navigate(`/pot/${month}`, { replace: true });
      return;
    }
    
    // 데이터가 없으면 userInfo 페이지로 이동
    navigate("/userInfo", { replace: true });
  }, [shouldFetchCardInfo, isLoading, isError, cardInfo, navigate]);

  return null;
}
