import Lottie from "lottie-react";
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import { useNavigate, useParams } from "react-router-dom";
import { useUpdateBudgetMutation } from "../api/mutation/budgetMutation";
import {
  useMonthlyBudgetsQuery,
  useYearlyBudgetLeaksQuery,
} from "../api/queries/budgetQuery";
import Header from "../components/Header";
import LoadingOverlay from "../components/LoadingOverlay";
import type { MonthlyBudgetResponse, YearlyBudgetLeakResponse } from "../types";
import "./LeakPotPage.css";

/* ------------------------------ 이미지 & 애니메이션 ------------------------------ */
const cryingKongjwi = "/leakPot/cryingKongjwi.webp";
const happyKongjwi = "/leakPot/happyKongjwi.webp";
const happyToad = "/leakPot/happyToad.webp";
const angryToad = "/leakPot/angryToad.webp";
const bgImage = "/leakPot/joseon-bg.webp";
const customPointer = "/leakPot/money.webp";
const paper = "/leakPot/paper.webp";
const potImage = "/leakPot/pot.webp";
const broken = "/leakPot/broken.webp";
const monthGood = "/leakPot/good.webp";
const monthBad = "/leakPot/bad.webp";
const badGray = "/leakPot/bad_gray.webp";
const goodGray = "/leakPot/good_gray.webp";
const tooltipToad = "/leakPot/tooltip.webp";

export const leakPotAssets = [
  cryingKongjwi,
  happyKongjwi,
  happyToad,
  angryToad,
  bgImage,
  customPointer,
  paper,
  potImage,
  broken,
  monthGood,
  monthBad,
  badGray,
  goodGray,
  "/leakPot/water.json",
  tooltipToad,
];

/* ------------------------------ 타입 ------------------------------ */
interface Category {
  id: number;
  name: string;
  spending: number;
  threshold: number;
  initialBudget: number;
}
interface LeakingCategory extends Category {
  originalIndex: number;
}
interface TooltipState {
  visible: boolean;
  content: string;
  x: number;
  y: number;
}
interface LeakAnchor {
  u: number;
  v: number;
  scale: number;
}
interface AbsPosition {
  x: number;
  y: number;
  scale: number;
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
const VIEWBOX_H = 520;
const FLOOR_Y = 515;
const POT_W = 310;
const POT_H = 330;
const POT_X = 95;
const POT_Y = FLOOR_Y - 8 - POT_H;

/* ------------------------------ 균열 위치 ------------------------------ */
const LEAK_ANCHORS: LeakAnchor[] = [
  { u: 0.5, v: 0.35, scale: 0.17 },
  { u: 0.3, v: 0.4, scale: 0.15 },
  { u: 0.7, v: 0.42, scale: 0.16 },
  { u: 0.2, v: 0.55, scale: 0.14 },
  { u: 0.8, v: 0.58, scale: 0.18 },
  { u: 0.48, v: 0.5, scale: 0.15 },
  { u: 0.35, v: 0.75, scale: 0.2 },
  { u: 0.65, v: 0.78, scale: 0.19 },
  { u: 0.52, v: 0.82, scale: 0.16 },
  { u: 0.75, v: 0.65, scale: 0.17 },
  { u: 0.28, v: 0.68, scale: 0.14 },
  { u: 0.6, v: 0.48, scale: 0.18 },
];
const anchorToAbs = (a: LeakAnchor): AbsPosition => ({
  x: POT_X + a.u * POT_W,
  y: POT_Y + a.v * POT_H,
  scale: a.scale,
});

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
  leakingCategories: LeakingCategory[];
  totalLeak: number;
  formatter: Intl.NumberFormat;
}

const PotVisualization: React.FC<PotVisualizationProps> = ({
  leakingCategories,
  totalLeak,
  formatter,
}) => {
  const [waterAnim, setWaterAnim] = useState<any | null>(null);
  useEffect(() => {
    fetch("/leakPot/water.json")
      .then((r) => r.json())
      .then(setWaterAnim)
      .catch(console.error);
  }, []);

  const hasLeak = leakingCategories.length > 0;
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

  const handleCrackEnter = (e: React.MouseEvent, cat: LeakingCategory) => {
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
    const rect = svg.getBoundingClientRect();
    const lx = e.clientX - rect.left;
    const ly = e.clientY - rect.top;

    if (potBodyRef.current) {
      const pt = svg.createSVGPoint();
      pt.x = e.clientX;
      pt.y = e.clientY;

      const ctm = potBodyRef.current.getScreenCTM();
      if (ctm) {
        const local = pt.matrixTransform(ctm.inverse());
        const inside = (potBodyRef.current as any).isPointInFill(local);
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
      className="pot-container"
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
            transform={`translate(0, -25)`}
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

          {/* 균열 */}
          <g id="cracks">
            {leakingCategories.map((cat) => {
              const anchor =
                LEAK_ANCHORS[cat.originalIndex % LEAK_ANCHORS.length];
              const { x, y, scale: baseScale } = anchorToAbs(anchor);
              const leakAmount = cat.spending - cat.threshold;
              const crackScale =
                baseScale *
                Math.max(0.4, Math.min(1.5, 0.4 + leakAmount / 80000));

              return (
                <image
                  key={cat.id}
                  href={broken}
                  className="crack"
                  x={(-450 / 2) * crackScale + x}
                  y={(-450 / 2) * crackScale + y}
                  width={450 * crackScale}
                  height={450 * crackScale}
                  onMouseEnter={(e) => handleCrackEnter(e, cat)}
                />
              );
            })}
          </g>

          {/* 물줄기 */}
          <g id="waters">
            {leakingCategories.map((cat) => {
              const anchor =
                LEAK_ANCHORS[cat.originalIndex % LEAK_ANCHORS.length];
              const { x, y, scale: baseScale } = anchorToAbs(anchor);
              const leakAmount = cat.spending - cat.threshold;
              const isLeft = anchor.u < 0.5;
              const waterScale = Math.max(
                0.2,
                Math.min(2.0, 0.3 + leakAmount / 80000)
              );

              return (
                <foreignObject
                  key={cat.id}
                  x={x - 225 * baseScale}
                  y={y - 225 * baseScale}
                  width={450 * baseScale}
                  height={450 * baseScale}
                  style={{ overflow: "visible", pointerEvents: "none" }}
                >
                  <div className={`water-animation ${isLeft ? "flip" : ""}`}>
                    {waterAnim && (
                      <Lottie
                        animationData={waterAnim}
                        loop
                        autoplay
                        initialSegment={[0, 29]}
                        style={{
                          position: "absolute",
                          width: `${WATER_BASE_W * waterScale}px`,
                          height: `${WATER_BASE_H * waterScale}px`,
                          left: `calc(50% - ${
                            WATER_BASE_W *
                            waterScale *
                            WATER_STREAM_ORIGIN_X_RATIO
                          }px)`,
                          top: `calc(50% - ${
                            WATER_BASE_H *
                            waterScale *
                            WATER_STREAM_ORIGIN_Y_RATIO
                          }px)`,
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
}> = ({ cat, isLeaking, handleThresholdChange, formatter }) => {

  const max = Math.max(600000, cat.spending * 1.5);
  const spendingPct = (cat.spending / max) * 100;
  // const thresholdPct = (cat.threshold / max) * 100;
  const thresholdPct = Math.min((cat.threshold / max) * 100, 100);

  const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    handleThresholdChange(cat.id, parseInt(e.target.value, 10));
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
          <span className="legend-label">한도</span>
          <strong className="legend-value">
            {formatter.format(cat.threshold)}냥
          </strong>
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
          min={0}
          max={max}
          step={5000}
          value={cat.threshold}
          onChange={handleChange}
          aria-label={`${cat.name} 한도`}
          className={`custom-slider ${isLeaking ? "is-leaking" : ""}`}
        />
        <div
          className="coin-thumb"
          aria-hidden="true"
          style={{ left: `var(--threshold-pct)` }}
          title={`한도: ${formatter.format(cat.threshold)}냥`}
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
const LeakPotPage = () => {
  const now = new Date();
  const nowMonth = now.getMonth() + 1;
  const nowYear = now.getFullYear();
  const { month } = useParams();
  const navigate = useNavigate();

  const [leakingCategories, setLeakingCategories] = useState<LeakingCategory[]>(
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
      const { [budgetId]: _, ...rest } = prev;
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
      threshold: pendingThresholds[cat.id] ?? cat.threshold
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

  // 타이머 정리
  useEffect(() => {
    return () => {
      debounceTimers.current.forEach((timerId) => clearTimeout(timerId));
      debounceTimers.current.clear();
    };
  }, []);


  // 누수 계산
  useEffect(() => {
    const currentLeaking = categories
      .map((cat, index) => ({ ...cat, originalIndex: index }))
      .filter((cat) => cat.spending > cat.threshold);
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
                    key={cat.id}
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

export default LeakPotPage;
