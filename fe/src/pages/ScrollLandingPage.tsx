import React, { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import "./ScrollLandingPage.css";

type Page = {
  id: number;
  title: string;
  description: string;
  backgroundImage: string;
};

type TrackVars = React.CSSProperties & {
  "--track-h"?: string;
  "--offset"?: string;
};

type PageVars = React.CSSProperties & {
  "--bg"?: string;
};

export const scrollLandingAssets = [
  "/landing/landing1.webp",
  "/landing/landing2.webp",
  "/landing/landing3.webp",
  "/landing/landing4.webp",
];

export default function ScrollLandingPage() {
  const navigate = useNavigate();
  const [currentPage, setCurrentPage] = useState(0);
  const [isScrolling, setIsScrolling] = useState(false);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const touchStartY = useRef(0);
  const [loggedIn, setLoggedIn] = useState<boolean>(() => !!localStorage.getItem("accessToken"));

  useEffect(() => {
    const recheck = () => setLoggedIn(!!localStorage.getItem("accessToken"));
    window.addEventListener("storage", recheck);
    window.addEventListener("focus", recheck);
    document.addEventListener("visibilitychange", recheck);
    return () => {
      window.removeEventListener("storage", recheck);
      window.removeEventListener("focus", recheck);
      document.removeEventListener("visibilitychange", recheck);
    };
  }, []);

  const pages: Page[] = [
    {
      id: 1,
      title: "콩쥐의 꿈",
      description:
        "콩쥐는 부자가 되고 싶었습니다.\n매일매일 가난한 생활에 지친 콩쥐는\n언젠가는 꼭 부자가 되어 행복하게 살고 싶다고 생각했어요.",
      backgroundImage: "/landing/landing1.webp",
    },
    {
      id: 2,
      title: "두꺼비를 만나다",
      description:
        "그래서 마을에서 유명한 자산관리사 두꺼비를 찾아갔습니다.\n두꺼비 선생님은 콩쥐의 소비 습관을 분석하기 시작했어요",
      backgroundImage: "/landing/landing2.webp",
    },
    {
      id: 3,
      title: "장독대의 비밀",
      description: `두꺼비 선생님은 콩쥐에게 말했습니다.\n"콩쥐야, 먼저 네 장독대의 누수를 막아야 한단다.\n새는 곳을 막지 않으면 아무리 많이 담아도 소용없어."`,
      backgroundImage: "/landing/landing3.webp",
    },
    {
      id: 4,
      title: "콩쥐의 장독대",
      description:
        "과연 콩쥐가 장독대의 누수를 모두 막아\n마을 최고의 부자가 될 수 있을까요?",
      backgroundImage: "/landing/landing4.webp",
    },
  ];

  const total = pages.length;

  const scrollToPage = (i: number) => {
    if (isScrolling) return;
    setIsScrolling(true);
    setCurrentPage(i);
    window.setTimeout(() => setIsScrolling(false), 700);
  };

  useEffect(() => {
    const handleWheel = (e: WheelEvent) => {
      e.preventDefault();
      if (isScrolling) return;
      if (e.deltaY > 0 && currentPage < total - 1)
        scrollToPage(currentPage + 1);
      else if (e.deltaY < 0 && currentPage > 0) scrollToPage(currentPage - 1);
    };

    const handleTouchStart = (e: TouchEvent) => {
      touchStartY.current = e.touches[0].clientY;
    };

    const handleTouchEnd = (e: TouchEvent) => {
      if (isScrolling) return;
      const diff = touchStartY.current - e.changedTouches[0].clientY;
      if (Math.abs(diff) > 50) {
        if (diff > 0 && currentPage < total - 1) scrollToPage(currentPage + 1);
        else if (diff < 0 && currentPage > 0) scrollToPage(currentPage - 1);
      }
    };

    const handleKeyDown = (e: KeyboardEvent) => {
      if (isScrolling) return;
      if (e.key === "ArrowDown" && currentPage < total - 1)
        scrollToPage(currentPage + 1);
      else if (e.key === "ArrowUp" && currentPage > 0)
        scrollToPage(currentPage - 1);
    };

    const el = containerRef.current;
    if (!el) return;

    el.addEventListener("wheel", handleWheel, { passive: false });
    el.addEventListener("touchstart", handleTouchStart, { passive: true });
    el.addEventListener("touchend", handleTouchEnd, { passive: true });
    window.addEventListener("keydown", handleKeyDown);

    return () => {
      el.removeEventListener("wheel", handleWheel as any);
      el.removeEventListener("touchstart", handleTouchStart as any);
      el.removeEventListener("touchend", handleTouchEnd as any);
      window.removeEventListener("keydown", handleKeyDown as any);
    };
  }, [currentPage, isScrolling, total]);

  const trackStyle: TrackVars = {
    "--track-h": `${total * 100}vh`,
    "--offset": `${currentPage * 100}vh`,
  };

  const handlePrimary = () => {
    if (loggedIn) {
      const month = String(new Date().getMonth() + 1).padStart(2, "0");
      // 장독대 라우트로 이동 (예: /pot/:month)
      navigate(`/pot/${month}`);
    } else {
      window.location.href =
        "https://j13a409.p.ssafy.io/api/oauth2/authorization/ssafy";
    }
  };

  return (
    <div ref={containerRef} className="dk-landing">
      <button className="dk-login" onClick={handlePrimary}>
        {loggedIn ? "장독대로 가기" : "로그인"}
      </button>

      {/* 슬라이드 트랙 */}
      <div className="dk-track" style={trackStyle}>
        {pages.map((p, i) => (
          <section
            key={p.id}
            className="dk-page"
            style={{ "--bg": `url(${p.backgroundImage})` } as PageVars}
          >
            <div className="dk-overlay" />
            <div className={`dk-content ${currentPage === i ? "show" : ""}`}>
              <h1 className="dk-title">{p.title}</h1>
              <p className="dk-desc">{p.description}</p>
            </div>
          </section>
        ))}
      </div>

      {/* 네비게이션 점 */}
      <div className="dk-dots">
        {pages.map((_, i) => (
          <button
            key={i}
            className={`dk-dot ${currentPage === i ? "active" : ""}`}
            onClick={() => scrollToPage(i)}
            aria-label={`Go to page ${i + 1}`}
          />
        ))}
      </div>

      {/* 페이지 카운터 */}
      <div className="dk-counter">
        <span className="cur">{String(currentPage + 1).padStart(2, "0")}</span>
        <span className="sep">/</span>
        <span className="tot">{String(total).padStart(2, "0")}</span>
      </div>
    </div>
  );
}
