import { useEffect } from "react";
import { useNavigate } from "react-router-dom";
import { useAuthStore } from "../store/authStore";
import { useUserInfoQuery } from "../api/queries/userQuery";

interface RouteGuardProps {
  children: React.ReactNode;
}

import { authMode, isLocalDemo } from '../auth/authMode';
import DemoAuthStatus from './DemoAuthStatus';

export default function RouteGuard(props: RouteGuardProps) {
  if (isLocalDemo) return <>{props.children}</>;
  return authMode === 'demo' ? <DemoGuard {...props} /> : <OAuthGuard {...props} />;
}

function DemoGuard({ children }: RouteGuardProps) {
  const status = useAuthStore(state => state.status);
  return status === 'authenticated' ? <DemoUserGate>{children}</DemoUserGate> : <DemoAuthStatus />;
}

function DemoUserGate({ children }: RouteGuardProps) {
  const user = useUserInfoQuery();
  if (user.isPending) return <p role="status">체험 화면을 준비하고 있습니다.</p>;
  if (user.isError || !user.data) return <section role="status">사용자 정보를 불러오지 못했습니다. <button onClick={() => void user.refetch()}>다시 시도</button></section>;
  return children;
}

function OAuthGuard({ children }: RouteGuardProps) {
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