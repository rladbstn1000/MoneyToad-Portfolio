import { cryingKongjwi, happyKongjwi, happyToad, angryToad, bgImage, customPointer, paper, potImage, broken, monthGood, monthBad, badGray, goodGray, leakPotAssets } from "../assets/pageAssets";
import Lottie, { type LottieComponentProps } from "lottie-react";
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useNavigate, useParams } from "react-router-dom";
import { authMode, isLocalDemo } from "../auth/authMode";
import { useAuthStore } from "../store/authStore";
import { useLocalDemoStore } from "../demo/localDemoStore";
import { useDemoPeriod } from "../demo/useDemoPeriod";
import { adaptDemoBudgets, parseDemoBudgetMonth } from "../demo/demoBudgetPresentation";
import { useDemoBudgetChanges } from "../demo/useDemoBudgetChanges";
import { useUpdateBudgetMutation } from "../api/mutation/budgetMutation";
import {
  useMonthlyBudgetsQuery,
  useYearlyBudgetLeaksQuery,
} from "../api/queries/budgetQuery";
import Header from "../components/Header";
import LoadingOverlay from "../components/LoadingOverlay";
import type { MonthlyBudgetResponse, YearlyBudgetLeakResponse } from "../types";
import "./LeakPotPage.css";
import { getPotLeakGeometry, getLocalPotLeakGeometry, POT_IMAGE_FRAME } from "./potLeakAnchors";

/* ------------------------------ 타입 ------------------------------ */
interface Category {
  id: number | null;
  name: string;
  spending: number;
  threshold: number;
  initialBudget: number;
}
interface TooltipState {
  visible: boolean;
  content: string;
  x: number;
  y: number;
}
/* ------------------------------ 데이터 ------------------------------ */
const INITIAL_CATEGORIES: Category[] = [
  { id: 1, name: "식비", spending: 300000, threshold: 300000, initialBudget: 300000 },
  { id: 2, name: "쇼핑", spending: 220000, threshold: 220000, initialBudget: 220000 },
  { id: 3, name: "교통", spending: 150000, threshold: 150000, initialBudget: 150000 },
  { id: 4, name: "여가", spending: 100000, threshold: 100000, initialBudget: 100000 },
  { id: 5, name: "주거", spending: 550000, threshold: 550000, initialBudget: 550000 },
  { id: 6, name: "교육", spending: 280000, threshold: 280000, initialBudget: 280000 },
  { id: 7, name: "통신", spending: 90000, threshold: 90000, initialBudget: 90000 },
  { id: 8, name: "반려동물", spending: 100000, threshold: 100000, initialBudget: 100000 },
  { id: 9, name: "의료/건강", spending: 80000, threshold: 80000, initialBudget: 80000 },
  { id: 10, name: "경조사비", spending: 120000, threshold: 120000, initialBudget: 120000 },
  { id: 11, name: "저축/투자", spending: 200000, threshold: 200000, initialBudget: 200000 },
  { id: 12, name: "기타", spending: 50000, threshold: 50000, initialBudget: 50000 },
];

const MONTHS = Array.from({ length: 12 }, (_, i) => ({
  value: i + 1,
  label: `${i + 1}월`,
}));

/* ------------------------------ 레이아웃 상수 ------------------------------ */
const VIEWBOX_W = 500;
// The demo owns its layout height, so reserve the measured maximum water
// trajectory below the 480-unit floor. OAuth keeps its existing parent sizing.
const VIEWBOX_H = authMode === "demo" ? 700 : 520;
const FLOOR_Y = 480;
const { x: POT_X, y: POT_Y, width: POT_W, height: POT_H } = POT_IMAGE_FRAME;

/* ------------------------------ 유틸: 연-월 누수 인덱스 ------------------------------ */
const buildLeakIndex = (rows: YearlyBudgetLeakResponse[] = []) => {
  const map = new Map<string, boolean>();
  rows.forEach((r) => {
    // r.budgetDate: "YYYY-MM" 가정
    const [y, m] = (r.budgetDate || "").split("-").map(Number);
    if (!y || !m) return;
    map.set(`${y}-${m}`, !!r.leaked);
  });
  return map;
};

/* ------------------------------ Month Navigation ------------------------------ */
const MonthNavigation: React.FC<{
  selectedMonth: number; // 현재 화면에서 선택된 달
  nowMonth: number; // 오늘 기준 달
  nowYear: number; // 오늘 기준 연도
  leakIndex: Map<string, boolean>; // `${year}-${month}` -> leaked
  onMonthChange: (month: number) => void;
  optimisticCurrentMonthLeaked?: boolean;
}> = ({
  selectedMonth,
  nowMonth,
  nowYear,
  leakIndex,
  onMonthChange,
  optimisticCurrentMonthLeaked,
}) => {
  return (
    <div className="month-navigation">
      <div className="month-grid">
        {MONTHS.map((m) => {
          // 오늘 기준으로 10,11,12월은 작년으로 고정
          const isLastYear = m.value > nowMonth;
          const yearForBtn = isLastYear ? nowYear - 1 : nowYear;

          // 기본 누수 여부 (연-월 인덱스 사용)
          let leaked = !!leakIndex.get(`${yearForBtn}-${m.value}`);

          // 낙관적 반영은 "선택된 달"만
          if (
            m.value === selectedMonth &&
            typeof optimisticCurrentMonthLeaked === "boolean"
          ) {
            leaked = optimisticCurrentMonthLeaked;
          }

          // 아이콘: 작년은 회색, 올해는 컬러(선택 여부와 무관)
          const imgSrc = isLastYear
            ? leaked
              ? badGray
              : goodGray
            : leaked
            ? monthBad
            : monthGood;

          return (
            <button
              key={m.value}
              onClick={() => onMonthChange(m.value)}
              onMouseDown={(e) => e.preventDefault()}
              className={`month-button ${
                m.value === selectedMonth ? "active" : ""
              }`}
            >
              <img
                src={imgSrc}
                alt={leaked ? "누수" : "정상"}
                className="month-img"
                draggable={false}
              />
              <span className={`month-badge ${leaked ? "leaked" : "good"}`}>
                {isLastYear ? "작년 " : ""}
                {m.label}
              </span>
            </button>
          );
        })}
      </div>
    </div>
  );
};

/* ------------------------------ Pot Visualization ------------------------------ */
const WATER_BASE_W = 120;
const WATER_BASE_H = 180;
const WATER_STREAM_ORIGIN_X_RATIO = 0.03;
const WATER_STREAM_ORIGIN_Y_RATIO = 0.28;

interface PotVisualizationProps {
  leakingCategories: Category[];
  totalLeak: number;
  formatter: Intl.NumberFormat;
}

export const PotVisualization: React.FC<PotVisualizationProps> = ({
  leakingCategories,
  totalLeak,
  formatter,
}) => {
  const [waterAnim, setWaterAnim] = useState<LottieComponentProps["animationData"]>(null);
  useEffect(() => {
    fetch("/leakPot/water.json")
      .then((r) => r.json())
      .then(setWaterAnim)
      .catch(console.error);
  }, []);

  const visibleLeaks = leakingCategories.filter(category => category.spending > category.threshold
    && (!isLocalDemo || category.id !== null));
  const geometryFor = (category: Category) => isLocalDemo
    ? getLocalPotLeakGeometry(category.name, category.spending, category.id === null ? null : category.threshold)
    : { ...getPotLeakGeometry(category.name, category.spending - category.threshold), ratio: undefined };
  const hasLeak = visibleLeaks.length > 0;
  const puddleScale = Math.min(1.0 + totalLeak / 300000, 2.2);

  // tooltip
  const potRef = useRef<HTMLDivElement>(null);
  const [tooltip, setTooltip] = useState<TooltipState>({
    visible: false,
    content: "",
    x: 0,
    y: 0,
  });
  const potBodyRef = useRef<SVGPathElement>(null);

  const potBodyD = (() => {
    const x = POT_X,
      y = POT_Y,
      w = POT_W,
      h = POT_H;
    const top = y + h * 0.2;
    const neck = y + h * 0.3;
    const mid = y + h * 0.55;
    const bottom = y + h * 0.92;
    const cx = x + w / 2;

    const leftTop = x + w * 0.25;
    const rightTop = x + w * 0.75;
    const leftNeck = x + w * 0.2;
    const rightNeck = x + w * 0.8;
    const leftMid = x + w * 0.08;
    const rightMid = x + w * 0.92;
    const leftBottom = x + w * 0.12;
    const rightBottom = x + w * 0.88;

    return `M ${leftNeck},${neck}
          C ${leftTop},${top} ${rightTop},${top} ${rightNeck},${neck}
          C ${rightMid},${mid} ${rightBottom},${bottom} ${cx},${bottom}
          C ${leftBottom},${bottom} ${leftMid},${mid} ${leftNeck},${neck}
          Z`;
  })();

  const toLocal = (e: React.MouseEvent) => {
    const rect = potRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  const handleCrackEnter = (e: React.MouseEvent, cat: Category) => {
    const leakAmount = cat.spending - cat.threshold;
    const { x, y } = toLocal(e);
    setTooltip({
      visible: true,
      content: `${cat.name}: ${formatter.format(leakAmount)}냥 누수`,
      x,
      y,
    });
  };

  const handleSvgMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const svg = e.currentTarget as SVGSVGElement;
    const { x: lx, y: ly } = toLocal(e);

    if (potBodyRef.current) {
      const pt = svg.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;

      const ctm = potBodyRef.current.getScreenCTM();
      if (ctm) {
        const local = pt.matrixTransform(ctm.inverse());
        const inside = potBodyRef.current.isPointInFill(local);
        if (!inside) {
          if (tooltip.visible)
            setTooltip({ visible: false, content: "", x: 0, y: 0 });
          return;
        }
      }
    }
    if (tooltip.visible) setTooltip((t) => ({ ...t, x: lx, y: ly }));
  };

  const handleSvgLeave = () =>
    setTooltip({ visible: false, content: "", x: 0, y: 0 });

  return (
    <div
      className="pot-container pot-visualization"
      ref={potRef}
      style={{ pointerEvents: "auto" }}
    >
      {tooltip.visible && (
        <div className="tooltip" style={{ left: tooltip.x, top: tooltip.y }}>
          {tooltip.content}
        </div>
      )}

      {/* 캐릭터 */}
      <div className="characters">
        <img
          src={hasLeak ? cryingKongjwi : happyKongjwi}
          alt="콩쥐"
          className="kongjwi"
        />
        <img
          src={hasLeak ? angryToad : happyToad}
          alt="두꺼비"
          className="toad"
        />
      </div>

      {/* 항아리 + 물 */}
      <div className="pot-svg-container">
        <svg
          viewBox={`0 0 ${VIEWBOX_W} ${VIEWBOX_H}`}
          className="pot-svg"
          preserveAspectRatio="xMidYMax meet"
          onMouseMove={handleSvgMove}
          onMouseLeave={handleSvgLeave}
        >
          <defs>
            <radialGradient id="puddleGradient" cx="50%" cy="30%" r="70%">
              <stop offset="0%" stopColor="#87CEEB" stopOpacity="0.9" />
              <stop offset="40%" stopColor="#4682B4" stopOpacity="0.8" />
              <stop offset="70%" stopColor="#2E5984" stopOpacity="0.7" />
              <stop offset="100%" stopColor="#1e3a5f" stopOpacity="0.6" />
            </radialGradient>
            <radialGradient id="puddleReflection" cx="45%" cy="25%" r="30%">
              <stop offset="0%" stopColor="#ffffff" stopOpacity="0.4" />
              <stop offset="50%" stopColor="#87CEEB" stopOpacity="0.2" />
              <stop offset="100%" stopColor="#4682B4" stopOpacity="0.1" />
            </radialGradient>
            <filter
              id="waterDistortion"
              x="-10%"
              y="-10%"
              width="120%"
              height="120%"
            >
              <feTurbulence
                baseFrequency="0.03 0.09"
                numOctaves="2"
                seed="2"
                result="turbulence"
              />
              <feDisplacementMap
                in="SourceGraphic"
                in2="turbulence"
                scale="8"
              />
            </filter>
          </defs>

          {/* puddle */}
          <g
            id="puddle-group"
            transform="translate(0, -2)"
            style={{
              opacity: hasLeak ? 0.7 : 0,
              transition: "opacity 0.7s ease-out",
            }}
          >
            <g
              transform={`translate(250, ${FLOOR_Y}) scale(${
                hasLeak ? puddleScale : 0
              }) translate(-250, -${FLOOR_Y})`}
              style={{ transition: "transform 0.7s ease-out" }}
            >
              <ellipse
                cx="250"
                cy={FLOOR_Y}
                rx="95"
                ry="16"
                fill="url(#puddleGradient)"
                filter="url(#waterDistortion)"
              />
              <ellipse
                cx="240"
                cy={FLOOR_Y - 3}
                rx="46"
                ry="6"
                fill="url(#puddleReflection)"
                filter="url(#waterDistortion)"
              />
            </g>
          </g>

          {/* pot */}
          <g id="pot-body">
            <image
              href={potImage}
              x={POT_X}
              y={POT_Y}
              width={POT_W}
              height={POT_H}
            />
            <path
              ref={potBodyRef}
              d={potBodyD}
              fill="white"
              fillOpacity={0.001}
              pointerEvents="none"
            />
          </g>

          {/* Every visible category owns a fixed anchor, including its water origin. */}
          <g id="cracks">
            {visibleLeaks.map((cat) => {
              const geometry = geometryFor(cat);
              return (
                <image
                  key={cat.name}
                  href={broken}
                  className="crack"
                  data-category={cat.name}
                  data-leak-amount={cat.spending - cat.threshold}
                  data-crack-scale={geometry.crackScale}
                  data-leak-ratio={geometry.ratio}
                  data-anchor-u={geometry.anchor.u}
                  data-anchor-v={geometry.anchor.v}
                  data-origin-x={geometry.x}
                  data-origin-y={geometry.y}
                  x={geometry.left}
                  y={geometry.top}
                  width={geometry.width}
                  height={geometry.height}
                  onMouseEnter={(e) => handleCrackEnter(e, cat)}
                />
              );
            })}
          </g>

          <g id="waters">
            {visibleLeaks.map((cat) => {
              const { anchor, x, y, waterScale, ratio } = geometryFor(cat);
              const isLeft = anchor.u < 0.5;
              return (
                <foreignObject
                  key={cat.name}
                  className="pot-leak-stream"
                  data-category={cat.name}
                  data-leak-amount={cat.spending - cat.threshold}
                  data-water-scale={waterScale}
                  data-leak-ratio={ratio}
                  data-water-width={WATER_BASE_W * waterScale}
                  data-water-height={WATER_BASE_H * waterScale}
                  data-anchor-u={anchor.u}
                  data-anchor-v={anchor.v}
                  data-origin-x={x}
                  data-origin-y={y}
                  x={x}
                  y={y}
                  width={1}
                  height={1}
                  style={{ overflow: "visible", pointerEvents: "none" }}
                >
                  <div className={`water-animation ${isLeft ? "flip" : ""}`}>
                    {Boolean(waterAnim) && (
                      <Lottie
                        animationData={waterAnim}
                        loop
                        autoplay
                        initialSegment={[0, 29]}
                        style={{
                          position: "absolute",
                          width: `${WATER_BASE_W * waterScale}px`,
                          height: `${WATER_BASE_H * waterScale}px`,
                          left: -WATER_BASE_W * waterScale * WATER_STREAM_ORIGIN_X_RATIO,
                          top: -WATER_BASE_H * waterScale * WATER_STREAM_ORIGIN_Y_RATIO,
                          pointerEvents: "none",
                        }}
                      />
                    )}
                  </div>
                </foreignObject>
              );
            })}
          </g>
        </svg>
      </div>
    </div>
  );
};

/* ------------------------------ Custom Slider ------------------------------ */
const CustomSlider: React.FC<{
  cat: Category;
  isLeaking: boolean;
  handleThresholdChange: (id: number, value: number) => void;
  formatter: Intl.NumberFormat;
  disabled?: boolean;
  budgetMissing?: boolean;
}> = ({ cat, isLeaking, handleThresholdChange, formatter, disabled = false, budgetMissing = false }) => {

  const max = Math.max(600000, cat.spending * 1.5);
  const spendingPct = (cat.spending / max) * 100;
  // const thresholdPct = (cat.threshold / max) * 100;
  const thresholdPct = Math.min((cat.threshold / max) * 100, 100);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    if (typeof cat.id === "number") handleThresholdChange(cat.id, parseInt(e.target.value, 10));
  };

  return (
    <div className="slider-item">
      <div className="slider-header">
        <label className={`slider-label ${isLeaking ? "leaking" : ""}`}>
          {cat.name}
        </label>

        <div className="slider-legend" aria-live="polite">
          <span className="legend-label">지출</span>
          <strong className={`legend-value ${isLeaking ? "leaking" : ""}`}>
            {formatter.format(cat.spending)}냥
          </strong>
          <span className="legend-sep"> | </span>
          {budgetMissing ? <strong className="legend-value">기준 예산 없음</strong> : <>
            <span className="legend-label">한도</span>
            <strong className="legend-value">{formatter.format(cat.threshold)}냥</strong>
          </>}
        </div>
      </div>

      <div
        className="slider-container"
        style={
          {
            "--spending-pct": `${spendingPct}%`,
            "--threshold-pct": `${thresholdPct}%`,
          } as React.CSSProperties
        }
      >
        <div className="slider-track" />
        <div
          className="slider-fill-normal"
          style={{ width: `${Math.min(spendingPct, thresholdPct)}%` }}
        />
        {isLeaking && (
          <div
            className="slider-fill-leak"
            style={{
              left: `${thresholdPct}%`,
              width: `${Math.max(0, spendingPct - thresholdPct)}%`,
            }}
          />
        )}
        <input
          type="range"
          disabled={disabled}
          min={0}
          max={max}
          step={5000}
          value={cat.threshold}
          onChange={handleChange}
          aria-label={`${cat.name} 한도`}
          aria-valuetext={budgetMissing ? "기준 예산 없음" : undefined}
          className={`custom-slider ${isLeaking ? "is-leaking" : ""}`}
        />
        <div
          className="coin-thumb"
          aria-hidden="true"
          style={{ left: `var(--threshold-pct)` }}
          title={budgetMissing ? "기준 예산 없음" : `한도: ${formatter.format(cat.threshold)}냥`}
        />
      </div>
    </div>
  );
};

/* ------------------------------ 어댑터 ------------------------------ */
const adaptBudgetDataToCategory = (data: MonthlyBudgetResponse): Category => ({
  id: data.id,
  name: data.category,
  spending: data.spending,
  threshold: data.budget,
  initialBudget: data.initialBudget
});

/* ------------------------------ 년/월 계산 ------------------------------ */
const calculateYearMonth = (
  monthParam: string | undefined
): { year: number; month: number } => {
  const currentDate = new Date();
  const currentYear = currentDate.getFullYear();
  const currentMonth = currentDate.getMonth() + 1;

  let targetMonth = currentMonth;
  if (monthParam) {
    const monthNum = parseInt(monthParam, 10);
    if (monthNum >= 1 && monthNum <= 12) targetMonth = monthNum;
  }
  const targetYear = targetMonth > currentMonth ? currentYear - 1 : currentYear;
  return { year: targetYear, month: targetMonth };
};

/* ------------------------------ LeakPotPage ------------------------------ */
const OAuthLeakPotPage = () => {
  const now = new Date();
  const nowMonth = now.getMonth() + 1;
  const nowYear = now.getFullYear();
  const { month } = useParams();
  const navigate = useNavigate();

  const [leakingCategories, setLeakingCategories] = useState<Category[]>(
    []
  );
  const [totalLeak, setTotalLeak] = useState<number>(0);
  const [pageLoading, setPageLoading] = useState(true);
  const [pendingThresholds, setPendingThresholds] = useState<Record<number, number>>({});
  const formatter = new Intl.NumberFormat("ko-KR");

  // 계산된 기준(요청 월의 데이터 연도/월)
  const { year, month: targetMonth } = calculateYearMonth(month);
  const currentMonth = targetMonth;
  const isSelectedLastYear = year < nowYear;

  // API
  const {
    data: budgetData,
    isLoading: isBudgetLoading,
    error,
  } = useMonthlyBudgetsQuery(year, targetMonth);

  const {
    data: yearlyLeakData,
    isLoading: isYearlyLeakLoading,
    error: yearlyLeakError,
  } = useYearlyBudgetLeaksQuery();

  // pending 상태 정리 콜백
  const handleMutationComplete = useCallback((budgetId: number) => {
    setPendingThresholds(prev => {
      const rest = { ...prev };
      delete rest[budgetId];
      return rest;
    });
  }, []);

  const updateBudgetMutation = useUpdateBudgetMutation(year, targetMonth, handleMutationComplete);

  // budgetData로부터 categories 계산 + pending 상태 병합
  const categories = useMemo(() => {
    let baseCategories: Category[] = [];

    if (budgetData?.length) {
      baseCategories = budgetData.map(adaptBudgetDataToCategory);
    } 

    if (!isBudgetLoading && !budgetData) {
      baseCategories = INITIAL_CATEGORIES.map((c) => ({ ...c, threshold: c.spending }));
    }

    // pending 상태와 병합 (드래그 중인 값 우선)
    return baseCategories.map(cat => ({
      ...cat,
      threshold: (cat.id === null ? undefined : pendingThresholds[cat.id]) ?? cat.threshold
    })).sort((a, b) => b.spending - a.spending);
  }, [budgetData, isBudgetLoading, pendingThresholds]);;;

  // 카테고리별 디바운스 타이머
  const debounceTimers = useRef<Map<number, number>>(new Map());

  // 에셋 프리로드
  useEffect(() => {
    let cancelled = false;
    Promise.all(
      leakPotAssets.map(
        (src) =>
          new Promise<void>((resolve) => {
            if (src.endsWith(".json")) {
              fetch(src)
                .then((res) => (res.ok ? res.json() : null))
                .then(() => resolve())
                .catch(() => resolve());
            } else {
              const img = new Image();
              img.src = src;
              img.onload = () => resolve();
              img.onerror = () => resolve();
            }
          })
      )
    )
      .catch(() => {})
      .finally(() => {
        if (!cancelled) setPageLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // 타이머 정리: capture the stable Map, including timers added after setup.
  useEffect(() => {
    const timers = debounceTimers.current;
    return () => {
      timers.forEach((timerId) => clearTimeout(timerId));
      timers.clear();
    };
  }, []);


  // 누수 계산
  useEffect(() => {
    const currentLeaking = categories.filter((cat) => cat.spending > cat.threshold);
    const currentTotalLeak = currentLeaking.reduce(
      (sum, cat) => sum + (cat.spending - cat.threshold),
      0
    );
    setLeakingCategories(currentLeaking);
    setTotalLeak(currentTotalLeak);
  }, [categories]);

  // 현재 달 누수(낙관적)
  const currentMonthLeaked = categories.some(
    (cat) => cat.spending > cat.threshold
  );

  // 연-월 누수 인덱스
  const leakIndex = useMemo(
    () => buildLeakIndex(yearlyLeakData || []),
    [yearlyLeakData]
  );

  const handleThresholdChange = useCallback(
    (id: number, newThreshold: number) => {
      // 즉시 로컬 상태 업데이트 (드래그 중 즉각적 피드백)
      setPendingThresholds(prev => ({
        ...prev,
        [id]: newThreshold
      }));

      // 카테고리별 debounce - 낙관적 업데이트는 mutation에서 처리
      const existingTimer = debounceTimers.current.get(id);
      if (existingTimer) clearTimeout(existingTimer);

      const timerId = setTimeout(() => {
        updateBudgetMutation.mutate({
          budgetId: id,
          budget: newThreshold,
        });
        debounceTimers.current.delete(id);
      }, 500) as unknown as number;

      debounceTimers.current.set(id, timerId);
    },
    [updateBudgetMutation]
  );

  const rootVars: React.CSSProperties = {
    "--bg": `url(${bgImage})`,
    "--paper": `url(${paper})`,
    "--pointer": `url(${customPointer})`,
  } as React.CSSProperties;

  if (error) console.error("Budget data fetch error:", error);
  if (yearlyLeakError)
    console.error("Yearly leak data fetch error:", yearlyLeakError);

  return (
    <div className="app-container" style={rootVars}>
      {pageLoading || isBudgetLoading || isYearlyLeakLoading ? (
        <LoadingOverlay />
      ) : (
        <>
          <Header />

          <MonthNavigation
            selectedMonth={currentMonth} // 기존 currentMonth 사용
            nowMonth={nowMonth} // 오늘 기준 달
            nowYear={nowYear}
            leakIndex={leakIndex}
            optimisticCurrentMonthLeaked={currentMonthLeaked}
            onMonthChange={(m) => navigate(`/pot/${m}`)}
          />

          <div className="main-container">
            <div className="pot-container" style={{ pointerEvents: "auto" }}>
              <PotVisualization
                leakingCategories={leakingCategories}
                totalLeak={totalLeak}
                formatter={formatter}
              />
            </div>

            <div className="control-panel">
              <h2 className="panel-title">
                {isSelectedLastYear ? `작년 ${currentMonth}월 지출이오` : `${currentMonth}월 지출을 다스리시오`}
                
              </h2>

              <div className="sliders-container">
                {categories.map((cat) => (
                  <CustomSlider
                    key={cat.name}
                    cat={cat}
                    isLeaking={cat.spending > cat.threshold}
                    handleThresholdChange={handleThresholdChange}
                    formatter={formatter}
                  />
                ))}
              </div>

              <div className="summary">
                {totalLeak > 0 ? (
                  <p className="summary-leak">
                    총 {formatter.format(totalLeak)}냥이 새고 있소!
                  </p>
                ) : (
                  <p className="summary-good">완벽하오! 새는 돈이 없소!</p>
                )}
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
};

function DemoLeakPotPage() {
  const { query, period } = useDemoPeriod();
  const { month: monthParam } = useParams();
  const navigate = useNavigate();
  const remoteGeneration = useAuthStore(state => state.generation);
  const localGeneration = useLocalDemoStore(state => state.generation);
  const generation = isLocalDemo ? localGeneration : remoteGeneration;
  const status = useAuthStore(state => state.status);
  const month = parseDemoBudgetMonth(monthParam);
  useEffect(() => {
    if (period && month === null) navigate(`/pot/${period.monthIndex + 1}`, { replace: true });
  }, [period, month, navigate]);
  const rootVars = { "--bg": `url(${bgImage})`, "--paper": `url(${paper})`,
    "--pointer": `url(${customPointer})` } as React.CSSProperties;
  return <div className={`app-container demo-pot-page${isLocalDemo ? " local-pot-page" : ""}`} data-demo-page="pot" style={rootVars}>
    <Header />
    <p className="demo-pot-notice">{isLocalDemo ? "샘플 소비와 기준 예산으로 누수를 살펴보세요." : "합성 소비와 직접 작성한 기준 예산입니다. AI 예측이 아닙니다."}</p>
    {(!isLocalDemo && status !== 'authenticated') || query.isPending ? <p role="status">장독대의 기준월을 확인하고 있습니다.</p>
      : query.isError ? <section role="alert">기준월을 불러오지 못했습니다. <button onClick={() => void query.refetch()}>다시 조회</button></section>
      : !period ? <p role="alert">체험 기간 데이터를 확인할 수 없습니다.</p>
      : month !== null ? <DemoMonthlyPot key={`${generation}/${period.yearsByMonth[month - 1]}/${month}`}
          year={period.yearsByMonth[month - 1]} month={month} generation={generation}
          anchorYear={period.year} anchorMonth={period.monthIndex + 1}
          leakIndex={buildLeakIndex((query.data ?? []).map(row => ({ budgetDate: row.date, leaked: row.leaked })))} />
      : <p role="status">기준월로 이동하고 있습니다.</p>}
  </div>;
}

function DemoMonthlyPot({ year, month, generation, anchorYear, anchorMonth, leakIndex }: {
  year: number; month: number; generation: number; anchorYear: number; anchorMonth: number;
  leakIndex: Map<string, boolean>;
}) {
  const navigate = useNavigate();
  const query = useMonthlyBudgetsQuery(year, month, { retry: false });
  const { pending, saving, errors, change } = useDemoBudgetChanges(year, month, generation);
  const rows = useMemo(() => adaptDemoBudgets(query.data), [query.data]);
  const categories = useMemo(() => (rows ?? []).map(row => ({
    ...adaptBudgetDataToCategory(row),
    threshold: row.id === null ? row.budget : pending[row.id]?.amount ?? row.budget,
  })).sort((a, b) => b.spending - a.spending), [rows, pending]);
  const leaking = categories.filter(cat => cat.spending > cat.threshold);
  const totalLeak = leaking.reduce((sum, cat) => sum + cat.spending - cat.threshold, 0);
  const formatter = new Intl.NumberFormat("ko-KR");
  const ready = query.isSuccess && rows !== null && rows.length > 0;
  return <>
    <MonthNavigation selectedMonth={month} nowMonth={anchorMonth} nowYear={anchorYear}
      leakIndex={leakIndex} onMonthChange={next => navigate(`/pot/${next}`)}
      optimisticCurrentMonthLeaked={ready ? totalLeak > 0 : undefined} />
    {query.isPending ? <p role="status">월별 소비와 한도를 불러오고 있습니다.</p>
      : query.isError ? <section role="alert">월별 소비를 불러오지 못했습니다. <button onClick={() => void query.refetch()}>다시 조회</button></section>
      : rows === null ? <p role="alert">월별 소비 데이터 형식을 확인할 수 없습니다.</p>
      : rows.length === 0 ? <p role="status">이 달의 기준 예산이 없습니다.</p>
      : <div className="main-container">
        <div className="demo-pot-stage" aria-label={`${year}년 ${month}월 누수 시각화`}>
          <PotVisualization leakingCategories={leaking} totalLeak={totalLeak} formatter={formatter} />
        </div>
        <section className="control-panel" aria-label="카테고리별 소비 한도">
          <img className="demo-paper-decoration" src={paper} alt="" aria-hidden="true" width={500} height={750} />
          <div className="demo-paper-safe-area">
          <h2 className="panel-title">{year}년 {month}월 지출을 다스리시오</h2>
          <p className="demo-pot-anchor">{isLocalDemo ? "샘플 기준월" : "기준월"} {anchorYear}년 {anchorMonth}월{!isLocalDemo && " · 한도는 저장 후 다시 조회합니다."}</p>
          <p className="demo-panel-scroll-hint">목록을 내려 모든 카테고리를 확인하세요.</p>
          <div className="sliders-container" tabIndex={0} aria-label="스크롤 가능한 예산 목록">
            {categories.map(cat => <div key={cat.name} className="demo-budget-row">
              <CustomSlider cat={cat} isLeaking={cat.spending > cat.threshold} formatter={formatter}
                handleThresholdChange={change} budgetMissing={cat.id === null} disabled={cat.id === null || saving.has(cat.id)} />
              <p className="demo-budget-state">
                {cat.id === null ? "기준 예산 없음 · 체험에서 조정할 수 없음"
                  : saving.has(cat.id) ? "한도를 저장하고 있습니다."
                  : pending[cat.id] ? "변경한 한도를 저장할 예정입니다." : isLocalDemo ? "이 탭의 샘플 기준 예산" : "방문자 전용 기준 예산"}
                {cat.spending > cat.threshold && ` · 누수 ${formatter.format(cat.spending - cat.threshold)}원`}
              </p>
              {cat.id !== null && errors[cat.id] && <p role="alert" className="demo-budget-error">{errors[cat.id]}</p>}
            </div>)}
          </div>
          <div className="summary" aria-live="polite">
            {totalLeak > 0 ? <p className="summary-leak">총 {formatter.format(totalLeak)}냥이 새고 있소!</p>
              : <p className="summary-good">완벽하오! 새는 돈이 없소!</p>}
          </div>
          </div>
        </section>
      </div>}
  </>;
}

export default function LeakPotPage() {
  return authMode === "demo" ? <DemoLeakPotPage /> : <OAuthLeakPotPage />;
}
