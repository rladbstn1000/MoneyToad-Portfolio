import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import { useUserInfoQuery } from "../api/queries/userQuery";

interface RouteGuardProps {
  children: React.ReactNode;
}

export default function RouteGuard({ children }: RouteGuardProps) {
  const navigate = useNavigate();
  const { accessToken } = useAuthStore();
  const { data: userInfo, isLoading, isError } = useUserInfoQuery();

  useEffect(() => {
    if (!accessToken) {
      navigate("/");
      return;
    }
  }, [accessToken, navigate]);

  useEffect(() => {
    if (accessToken && !isLoading && (isError || !userInfo)) {
      navigate("/");
      return;
    }
  }, [accessToken, userInfo, isLoading, isError, navigate]);

  // 토큰이 없으면 아무것도 렌더링하지 않음 (리다이렉트 중)
  if (!accessToken) {
    return null;
  }

  // 로딩 중일 때는 자식 컴포넌트 렌더링
  if (isLoading) {
    return <>{children}</>;
  }

  // 사용자 정보가 없거나 에러가 있으면 아무것도 렌더링하지 않음 (리다이렉트 중)
  if (isError || !userInfo) {
    return null;
  }

  return <>{children}</>;
}