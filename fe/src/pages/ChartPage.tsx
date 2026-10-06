import { chartAssets } from "../assets/pageAssets";
import React, { useEffect, useMemo, useState, useRef } from "react";
import {
  ResponsiveContainer,
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  PieChart,
  Pie,
  Cell,
} from "recharts";
import "./ChartPage.css";
import Header from "../components/Header";
import JPSelect from "../components/JPSelect";
import {
  useYearTransactionQuery,
  usePeerYearTransactionQuery,
  useMonthlyTransactionsQuery,
  useCategoryTransactionsQuery,
} from "../api/queries/transactionQuery";
import { useUpdateTransactionCategoryMutation } from "../api/mutation/transactionMutation";
import type { MonthlyTransaction } from "../types";
import { useQueryClient } from "@tanstack/react-query";
import { transactionQueryKeys } from "../api/queryKeys";
import { belongsToPeriod, findEditableTransaction, isTransactionId } from "./chartDataBoundary";
import type { ChartPeriod } from "./chartDataBoundary";
import { authMode, isLocalDemo } from "../auth/authMode";
import { parseDemoChartPeriod } from "./demoChartPeriod";

const isDemo = authMode === "demo";

/* ------------------------------ 상수/타입 ------------------------------ */

const CATEGORIES = [
  "식비",
  "카페",
  "마트 / 편의점",
  "문화생활",
  "교통 / 차량",
  "패션 / 미용",
  "생활용품",
  "주거 / 통신",
  "건강 / 병원",
  "교육",
  "경조사 / 회비",
  "보험 / 세금",
  "기타",
] as const;

type Category = (typeof CATEGORIES)[number];

type Txn = {
  id: number;
  date: string;
  merchant: string;
  amount: number;
  category: Category;
  leaked?: boolean;
};

type MonthData = {
  amount: number;
  isLastYear: boolean;
  leaked: boolean;
  month: number;
};

const JP_COLORS = [
  "#8B4A6B", // 자주색 (茄紫)
  "#D4AF37", // 황금색 (金色)
  "#4A6741", // 청록색 (靑綠)
  "#B87333", // 갈색 (褐色)
  "#6B8E23", // 올리브색 (橄欖)
  "#8B7355", // 베이지 갈색
  "#556B2F", // 진한 올리브
  "#CD853F", // 모래 갈색
  "#708090", // 청회색
  "#A0522D", // 황토색
  "#8FBC8F", // 연한 청록
  "#F4A460", // 모래색
  "#9370DB", // 자주 보라
  "#20B2AA", // 진한 청록
];

const CATEGORY_COLORS: Record<Category, string> = CATEGORIES.reduce(
  (acc, c, i) => {
    acc[c] = JP_COLORS[i % JP_COLORS.length];
    return acc;
  },
  {} as Record<Category, string>
);

const monthLabel = (i: number) => `${i + 1}월`;
const KRW = (n: number) => n.toLocaleString("ko-KR");
const toNum = (v: unknown) => (typeof v === "number" ? v : Number(v) || 0);

/* ------------------------------ Tooltip ------------------------------ */

// Recharts exposes loosely typed payloads. Narrow only fields this page reads.
const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;
const isConsumptionEntry = (value: unknown): value is { dataKey: string; value: number } =>
  isRecord(value) && typeof value.dataKey === "string" &&
  typeof value.value === "number" && Number.isFinite(value.value);
const flag = (payload: unknown, key: "leaked" | "isLastYear") =>
  isRecord(payload) && payload[key] === true;

type ChartDotProps = {
  cx?: number;
  cy?: number;
  index?: number;
  payload?: unknown;
};
type ChartTooltipProps = {
  active?: boolean;
  payload?: readonly unknown[];
  label?: React.ReactNode;
};

const CustomTooltip = ({ active, payload, label }: ChartTooltipProps) => {
  if (active && payload && payload.length) {
    const entries = payload.filter(isConsumptionEntry);
    const me = entries.find((p) => p.dataKey === "me");
    const peer = entries.find((p) => p.dataKey === "peers");
    const first = payload[0];
    const leaked = isRecord(first) && flag(first.payload, "leaked");

    return (
      <div
        style={{
          backgroundColor: "#fff",
          borderRadius: "10px",
          padding: "10px 10px",
        }}
      >
        <div style={{ fontWeight: "bold", color: "#2A3437" }}>
          {label}
          {leaked && (
            <span style={{ color: "#FF4444", marginLeft: 8 }}>[누수]</span>
          )}
        </div>
        <div style={{ color: "#817716" }}>
          내 소비 : {me ? KRW(me.value) : "-"}원
        </div>
        {!isDemo && <div style={{ color: "#BA5910" }}>
          또래 소비 : {peer ? KRW(peer.value) : "-"}원
        </div>}
      </div>
    );
  }
  return null;
};

/* ============================== 메인 컴포넌트 ============================== */

export default function ChartPage() {
  /* 무대(연못)의 비율 고정용 상태 */
  const [pondAR, setPondAR] = useState(1280 / 853);
  const [compactLayout, setCompactLayout] = useState(
    () => window.matchMedia?.("(max-width: 900px)").matches ?? false
  );
  const [pieWidth, setPieWidth] = useState(0);

  useEffect(() => {
    const media = window.matchMedia?.("(max-width: 900px)");
    if (!media) return;
    const update = () => setCompactLayout(media.matches);
    media.addEventListener("change", update);
    return () => media.removeEventListener("change", update);
  }, []);

  const screen1Ref = useRef<HTMLElement | null>(null);

  /* 현재 날짜 정보 공통 함수 */
  const getCurrentDateInfo = () => {
    const currentDate = new Date();
    return {
      currentYear: currentDate.getFullYear(),
      currentMonth: currentDate.getMonth(),
    };
  };

  /* API 데이터 가져오기 */
  const yearQuery = useYearTransactionQuery();
  const peerQuery = usePeerYearTransactionQuery();
  const yearTransactionData = yearQuery.data;
  const peerYearTransactionData = peerQuery.data;
  const demoPeriod = useMemo(() => isDemo ? parseDemoChartPeriod(yearTransactionData) : null, [yearTransactionData]);
  const yearDataReady = yearQuery.isSuccess && (!isDemo || demoPeriod !== null);

  const apiMonthlyData = useMemo(() => {
    if (!yearTransactionData || isDemo && !demoPeriod) {
      return Array.from({ length: 12 }, (_, index) => ({
        amount: 0,
        isLastYear: false,
        leaked: false,
        month: index,
      }));
    }

    if (isDemo && demoPeriod) {
      const monthlyData: MonthData[] = Array.from({ length: 12 }, (_, index) => ({
        amount: 0, isLastYear: demoPeriod.yearsByMonth[index] < demoPeriod.year,
        leaked: false, month: index,
      }));
      yearTransactionData.forEach(transaction => {
        const monthIndex = Number(transaction.date.slice(5)) - 1;
        monthlyData[monthIndex].amount = transaction.totalAmount;
        monthlyData[monthIndex].leaked = transaction.leaked;
      });
      return monthlyData;
    }

    const { currentYear, currentMonth } = getCurrentDateInfo();

    // 각 월에 대해 사용할 연도 결정: 미래 달이면 작년, 아니면 올해
    const desiredYearByMonth = Array.from({ length: 12 }, (_, m) =>
      m > currentMonth ? currentYear - 1 : currentYear
    );

    const monthlyData: MonthData[] = Array.from({ length: 12 }, (_, index) => ({
      amount: 0,
      isLastYear: desiredYearByMonth[index] < currentYear,
      leaked: false,
      month: index,
    }));

    // 선택한 '그 해'의 데이터만 합계에 반영
    yearTransactionData.forEach((transaction) => {
      const [year, month] = transaction.date.split("-").map(Number);
      const mIdx = month - 1;
      if (year === desiredYearByMonth[mIdx]) {
        monthlyData[mIdx].amount += transaction.totalAmount;
        if (transaction.leaked) monthlyData[mIdx].leaked = true; // OR 집계
      }
    });

    return monthlyData;
  }, [yearTransactionData, demoPeriod]);

  // Unavailable data is unknown; a successful empty response represents zero.
  const myMonthly = useMemo(
    () => apiMonthlyData.map(month => yearDataReady ? month.amount : null),
    [apiMonthlyData, yearDataReady]
  );
  /* 또래 API 데이터를 월별 데이터로 변환 */
  const apiPeerMonthlyData = useMemo(() => {
    if (!peerYearTransactionData) {
      return Array.from({ length: 12 }, (_, index) => ({
        amount: 0,
        isLastYear: false,
        leaked: false,
        month: index,
      }));
    }

    const { currentYear, currentMonth } = getCurrentDateInfo();

    const desiredYearByMonth = Array.from({ length: 12 }, (_, m) =>
      m > currentMonth ? currentYear - 1 : currentYear
    );

    const monthlyData: MonthData[] = Array.from({ length: 12 }, (_, index) => ({
      amount: 0,
      isLastYear: desiredYearByMonth[index] < currentYear,
      leaked: false,
      month: index,
    }));

    peerYearTransactionData.forEach((transaction) => {
      const [year, month] = transaction.date.split("-").map(Number);
      const mIdx = month - 1;
      if (year === desiredYearByMonth[mIdx]) {
        monthlyData[mIdx].amount += transaction.totalAmount;
      }
    });

    return monthlyData;
  }, [peerYearTransactionData]);

  const peerMonthly = useMemo(
    () => apiPeerMonthlyData.map(month => peerQuery.isSuccess ? month.amount : null),
    [apiPeerMonthlyData, peerQuery.isSuccess]
  );

  const lineData = useMemo(
    () =>
      Array.from({ length: 12 }, (_, i) => ({
        idx: i,
        month: monthLabel(i),
        me: myMonthly[i],
        peers: peerMonthly[i],
        leaked:
          yearTransactionData && yearTransactionData.length > 0
            ? apiMonthlyData[i].leaked
            : false,
        isLastYear:
          yearTransactionData && yearTransactionData.length > 0
            ? apiMonthlyData[i].isLastYear
            : false, // ← 추가
      })),
    [myMonthly, peerMonthly, yearTransactionData, apiMonthlyData]
  );
  /* 커스텀 X축 Tick */
  const CustomXAxisTick = (props: Record<string, unknown>) => {
    const { x, y, payload } = props as {
      x: number;
      y: number;
      payload: { value: string; index: number };
    };

    const monthIndex = payload.index;

    // 현재 월 이후의 달(과거 데이터)이면 회색으로 표시
    const isLastYear = yearTransactionData && yearTransactionData.length > 0
      ? apiMonthlyData[monthIndex]?.isLastYear
      : false;

    return (
      <g transform={`translate(${x},${y})`}>
        <text
          x={0}
          y={0}
          dy={11}
          textAnchor="middle"
          fill={isLastYear ? "#999999" : "#ffffff"}
          fontSize={16}
        >
          {payload.value}
        </text>
      </g>
    );
  };

  /* 상세 상태 */
  const [selectedMonth, setSelectedMonth] = useState<number | null>(null);
  const [selectedCategory, setSelectedCategory] = useState<"전체" | Category>(
    "전체"
  );

  /* 연월 계산 */
  const selectedYear = useMemo(() => {
    if (selectedMonth === null) return null;
    if (isDemo) return demoPeriod?.yearsByMonth[selectedMonth] ?? null;
    const { currentYear, currentMonth } = getCurrentDateInfo();

    // selectedMonth가 현재 달보다 큰 경우(미래 달) 작년으로 간주
    return selectedMonth > currentMonth ? currentYear - 1 : currentYear;
  }, [selectedMonth, demoPeriod]);

  const selectedMonthNum = useMemo(() => {
    if (selectedMonth === null) return null;
    return selectedMonth + 1; // 1-based month
  }, [selectedMonth]);

  /* 월별 거래 내역 API */
  const monthlyQuery = useMonthlyTransactionsQuery(
    selectedYear || 0,
    selectedMonthNum || 0
  );

  /* 카테고리별 거래 내역 API */
  const categoryQuery = useCategoryTransactionsQuery(
    selectedYear || 0,
    selectedMonthNum || 0
  );

  /* 카테고리 업데이트 Mutation */
  const updateCategoryMutation = useUpdateTransactionCategoryMutation();
  const queryClient = useQueryClient();
  const activePeriod = useRef<ChartPeriod | null>(null);
  const saving = useRef(false);
  const [saveError, setSaveError] = useState<{ period: string; message: string } | null>(null);
  const monthlyTransactionsData = monthlyQuery.data;
  const categoryTransactionsData = categoryQuery.data;
  const monthlyReady = selectedMonth !== null && (!isDemo || demoPeriod !== null) && monthlyQuery.isSuccess && !monthlyQuery.isPlaceholderData;
  const canEdit = monthlyReady && monthlyQuery.fetchStatus === "idle" && !updateCategoryMutation.isPending;

  /* MonthlyTransaction을 Txn으로 변환 */
  const convertToTxn = (monthlyTxn: MonthlyTransaction): Txn => ({
    id: monthlyTxn.id,
    date: monthlyTxn.transactionDateTime.split("T")[0], // YYYY-MM-DD 형태로 변환
    merchant: monthlyTxn.merchantName,
    amount: monthlyTxn.amount,
    category: monthlyTxn.category as Category,
    leaked: "leaked" in monthlyTxn && Boolean(monthlyTxn.leaked),
  });

  /* 안전 클릭 핸들러: index → payload.idx */
  const onPointClickSafe = (props: ChartDotProps) => {
    const idx =
      typeof props?.index === "number"
        ? props.index
        : isRecord(props.payload) && typeof props.payload.idx === "number"
        ? props.payload.idx
        : null;

    if (idx !== null && Number.isInteger(idx) && idx >= 0 && idx < 12) {
      if (isDemo) {
        if (!demoPeriod) return;
        activePeriod.current = { year: demoPeriod.yearsByMonth[idx], month: idx + 1 };
      } else {
        const { currentYear, currentMonth } = getCurrentDateInfo();
        activePeriod.current = { year: idx > currentMonth ? currentYear - 1 : currentYear, month: idx + 1 };
      }
      setSelectedMonth(idx);
      setSelectedCategory("전체");
      setSaveError(null);
      requestAnimationFrame(() => {
        document
          .getElementById("screen2")
          ?.scrollIntoView({ behavior: "smooth", block: "start" });
      });
    }
  };

  /* 서버 확정값을 표시하고, 호출 직전 현재 월/캐시/ID를 다시 검증한다. */
  const updateTxnCategory = (period: ChartPeriod, id: number, cat: Category) => {
    const state = queryClient.getQueryState<MonthlyTransaction[]>(
      transactionQueryKeys.monthly(period.year, period.month)
    );
    const transaction = findEditableTransaction({
      id, category: cat, period, activePeriod: activePeriod.current,
      transactions: state?.data,
      ready: monthlyReady && state?.status === "success" && state.data === monthlyTransactionsData,
      fetching: monthlyQuery.isFetching || state?.fetchStatus !== "idle",
      placeholder: monthlyQuery.isPlaceholderData,
      saving: saving.current || updateCategoryMutation.isPending,
      allowedCategories: CATEGORIES,
    });
    if (!transaction) return;

    saving.current = true; // Close the interval before isPending triggers a render.
    setSaveError(null);
    updateCategoryMutation.mutate({
      transactionId: transaction.id,
      data: { category: cat },
      period,
    }, {
      onError: () => setSaveError({
        period: String(period.year) + "/" + period.month,
        message: "카테고리를 변경하지 못했습니다.",
      }),
      onSettled: () => { saving.current = false; },
    });
  };

  /* 성공한 현재 월의 API 응답만 거래로 표시한다. */
  const detailTxns = useMemo(
    () => monthlyReady ? (monthlyTransactionsData ?? []).map(convertToTxn) : [],
    [monthlyReady, monthlyTransactionsData]
  );

  const filteredTxns =
    selectedMonth === null
      ? []
      : selectedCategory === "전체"
      ? detailTxns
      : detailTxns.filter((t) => t.category === selectedCategory);
  const monthTotal = useMemo(() => {
    if (!monthlyReady || selectedMonth === null) return null;
    if (detailTxns.length === 0) return 0;
    // Preserve the existing annual aggregate priority for a nonempty confirmed month.
    if (yearQuery.isSuccess && yearTransactionData && yearTransactionData.length > 0) {
      return apiMonthlyData[selectedMonth].amount;
    }
    return detailTxns.reduce((total, transaction) => total + transaction.amount, 0);
  }, [monthlyReady, selectedMonth, yearQuery.isSuccess, yearTransactionData, apiMonthlyData, detailTxns]);

  const leakTotal = useMemo(() => {
    if (selectedMonth === null || !categoryQuery.isSuccess) return null;
    return (categoryTransactionsData ?? []).reduce((total, category) => total + category.leakedAmount, 0);
  }, [selectedMonth, categoryQuery.isSuccess, categoryTransactionsData]);

  /* 파이 데이터 */
  const pieData = useMemo(() => {
    if (!monthlyReady || selectedMonth === null || monthTotal === null) return [];

    if (selectedCategory === "전체") {
      const categoryData = CATEGORIES.map((c, i) => ({
        name: c,
        value: detailTxns
          .filter((t) => t.category === c)
          .reduce((a, t) => a + t.amount, 0),
        color: JP_COLORS[i % JP_COLORS.length],
      })).filter((item) => item.value > 0); // 0원인 항목 제거

      return categoryData;
    }

    const sel = detailTxns
      .filter((t) => t.category === selectedCategory)
      .reduce((a, t) => a + t.amount, 0);
    const others = monthTotal - sel;

    // 하나의 카테고리만 선택된 경우 또는 선택된 카테고리가 100%인 경우
    if (others === 0 || sel === monthTotal) {
      return [{ name: selectedCategory, value: sel, color: "#D4AF37" }];
    }

    return [
      {
        name: selectedCategory,
        value: sel,
        color: CATEGORY_COLORS[selectedCategory as Category],
      },
      { name: "나머지", value: others, color: "#4a5568" },
    ];
  }, [monthlyReady, selectedMonth, selectedCategory, detailTxns, monthTotal]);

  /* 파이 라벨 */
  // 파이차트 커스텀 라벨 - 균일한 위치
  // 라벨: 이름 + 퍼센트 (>= 3%만 표시), 폰트 업
  const renderCustomLabel = (props: unknown) => {
    if (!isRecord(props)) return null;
    const { cx, cy, midAngle, outerRadius, percent, name } = props;
    if (typeof cx !== "number" || !Number.isFinite(cx) ||
        typeof cy !== "number" || !Number.isFinite(cy) ||
        typeof midAngle !== "number" || !Number.isFinite(midAngle) ||
        typeof outerRadius !== "number" || !Number.isFinite(outerRadius) ||
        typeof percent !== "number" || !Number.isFinite(percent) ||
        typeof name !== "string") return null;
    if (percent < 0.03) return null; // 3% 미만 숨김
    const RADIAN = Math.PI / 180;
    const labelRadius = compactLayout ? outerRadius * 0.5 : outerRadius + 52;
    const x = cx + labelRadius * Math.cos(-midAngle * RADIAN);
    const y = cy + labelRadius * Math.sin(-midAngle * RADIAN);
    const pct = Math.round(percent * 100);

    // ★ '나머지'는 항상 기본색, 나머지는 누수 카테고리면 붉은색
    const isLeakedCategory = categoryTransactionsData?.find((category) => category.category === name && category.leakedAmount > 0);
    const labelColor = isLeakedCategory ? "#EC6665" : "#212A2D";

    return (
      <text
        x={x}
        y={y}
        fill={labelColor}
        fontSize={16}
        fontWeight={800}
        textAnchor="middle"
        dominantBaseline="central"
        pointerEvents="none"
        style={{ overflow: "visible" }}
      >
        {compactLayout ? `${pct}%` : `${name} ${pct}%`}
      </text>
    );
  };

  /* 커스텀 점 */
  const MyConsumptionDot = (props: ChartDotProps): React.ReactElement<SVGElement> => {
    const { cx, cy, index, payload } = props;
    if (cx == null || cy == null || !Number.isFinite(cx) || !Number.isFinite(cy)) return <g />;

    const flower = chartAssets[5];
    const leaf = chartAssets[6];
    const deadFlower = chartAssets[7];
    const deadLeaf = chartAssets[8];

    const isCurrentMonth = index === (isDemo ? demoPeriod?.monthIndex : getCurrentDateInfo().currentMonth);
    const leaked = flag(payload, "leaked");
    const isLastYear = flag(payload, "isLastYear");

    const href = leaked
      ? isCurrentMonth
        ? deadFlower
        : deadLeaf
      : isCurrentMonth
      ? flower
      : leaf;

    const size = 40;
    const x = cx - size / 2;
    const y = cy - size / 2;

    return (
      <g
        key={`dot-${index}`}
        transform={`translate(${x}, ${y})`}
        aria-disabled={isDemo && !demoPeriod}
        style={{ cursor: "pointer", opacity: isLastYear ? 0.6 : 1 }}
        onClick={(e) => {
          e.stopPropagation();
          onPointClickSafe(props);
        }}
      >
        <image xlinkHref={href} width={size} height={size} />
      </g>
    );
  };

  const PeerDot = (props: ChartDotProps): React.ReactElement<SVGElement> => {
    const { cx, cy } = props;
    if (cx == null || cy == null || !Number.isFinite(cx) || !Number.isFinite(cy)) return <g />;
    return (
      <g
        key={`dot-peer-${props.index}`}
        onClick={(e) => {
          e.stopPropagation();
          onPointClickSafe(props);
        }}
        style={{ cursor: "pointer" }}
      >
        <circle
          cx={cx}
          cy={cy}
          r={5}
          stroke="#a47690ff"
          strokeWidth={2}
          fill="#a47690ff"
        />
      </g>
    );
  };

  const handleClickCategory = (categoryName: string) => {
    if(categoryName === "나머지") {
      setSelectedCategory("전체");
      return;
    }
    
    setSelectedCategory(categoryName as Category);
  }

  /* 화면1이 보일 때만 애니메이션 실행 */
  useEffect(() => {
    const el = screen1Ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) el.classList.add("is-visible");
          else el.classList.remove("is-visible");
        });
      },
      { threshold: 0.35 } // 화면에 35% 이상 보이면 실행
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);

  /* ------------------------------ 렌더 ------------------------------ */

  return (
    <div className="jp-wrap">
      <Header />

      {/* ===== 화면 1: 라인차트 섹션 ===== */}
      <section id="screen1" className="jp-screen" ref={screen1Ref}>
        <div className="jp-page-title-section">
            <h1>{isDemo ? "월간 소비 내역" : "월간 소비 비교"}</h1>
            <p>연꽃과 잎을 클릭하면 해당 달의 상세 소비를 볼 수 있습니다!</p>
            {isDemo && <>
              <p>{isLocalDemo ? "샘플 소비·기준 예산" : "직접 작성한 합성 소비·기준 예산입니다. AI 예측이 아닙니다."}{demoPeriod && ` ${isLocalDemo ? "샘플 기준월" : "기준월"} ${demoPeriod.anchor}`}</p>
              <details className="jp-demo-explanation"><summary>샘플 데이터 안내</summary><p>또래 비교 데이터는 이번 체험에서 제공하지 않습니다.{isLocalDemo && " AI 예측이 아닙니다."}</p></details>
            </>}
            <div className="jp-query-status">
              {yearQuery.isError ? (
                <p role="alert">연간 소비를 불러오지 못했습니다. <button onClick={() => void yearQuery.refetch()}>연간 소비 다시 시도</button></p>
              ) : isDemo && yearQuery.isSuccess && !demoPeriod ? (
                <p role="alert">체험 소비 기간을 확인하지 못했습니다. <button onClick={() => void yearQuery.refetch()}>연간 소비 다시 시도</button></p>
              ) : yearQuery.isPending || yearQuery.fetchStatus !== "idle" ? (
                <p role="status">연간 소비를 불러오는 중입니다.</p>
              ) : yearQuery.isSuccess && yearTransactionData?.length === 0 ? (
                <p role="status">연간 소비 내역이 없습니다.</p>
              ) : null}
              {!isDemo && (peerQuery.isError ? (
                <p role="alert">또래 소비를 불러오지 못했습니다. <button onClick={() => void peerQuery.refetch()}>또래 소비 다시 시도</button></p>
              ) : peerQuery.isPending || peerQuery.fetchStatus !== "idle" ? (
                <p role="status">또래 소비를 불러오는 중입니다.</p>
              ) : peerQuery.isSuccess && peerYearTransactionData?.length === 0 ? (
                <p role="status">또래 소비 내역이 없습니다.</p>
              ) : null)}
              {selectedMonth === null && <p role="status">월을 선택해 주세요.</p>}
            </div>
          </div>

        <div className="jp-stage" style={{ aspectRatio: pondAR }}>
          {/* 연못 바닥: onLoad에서 실제 비율로 교체 */}
          <img
            src="/charts/water.webp"
            alt="Water"
            className="jp-water-image"
            onLoad={(e) => {
              const img = e.currentTarget;
              if (img.naturalWidth && img.naturalHeight) {
                setPondAR(img.naturalWidth / img.naturalHeight);
              }
            }}
          />

          {/* 장식 이미지(무대 내부의 % 좌표) */}
          <img
            src="/charts/sitting_girl.webp"
            alt="Sitting Girl"
            className="jp-page-image"
          />
          <img src="/charts/toad.webp" alt="Toad" className="jp-toad-image" />

          {/* 라인차트 */}
          <div className="jp-linechart-wrap">
            <ResponsiveContainer width="100%" height="100%">
              <LineChart
                className="jp-linechart"
                data={lineData}
                margin={{ top: 24, right: 12, left: 0, bottom: 20 }}
              >
                <CartesianGrid stroke="#fff" strokeDasharray="3 3" />
                <XAxis
                  dataKey="month"
                  tick={<CustomXAxisTick />}
                  axisLine={false}
                  tickLine={false}
                  padding={{ left: 20, right: 20 }}
                  tickMargin={8}
                />
                <YAxis
                  tickFormatter={(v) => `${KRW(toNum(v) / 10000)}만`}
                  tick={{ fill: "#ffffff" }}
                  axisLine={false}
                  tickLine={false}
                />
                <Tooltip content={<CustomTooltip />} cursor={false} />
                <Legend
                  wrapperStyle={{ color: "#E0FFFF", paddingTop: "10px" }}
                />
                {!isDemo && <Line
                  type="monotone"
                  dataKey="peers"
                  name="또래 소비"
                  stroke="#a47690ff"
                  strokeWidth={3}
                  dot={PeerDot}
                  activeDot={{ r: 7 }}
                />}
                <Line
                  type="monotone"
                  dataKey="me"
                  name="내 소비"
                  stroke="#DCE775"
                  strokeWidth={3}
                  dot={MyConsumptionDot}
                  activeDot={MyConsumptionDot}
                />
                
              </LineChart>
            </ResponsiveContainer>
          </div>
        </div>
          <nav className="jp-mobile-months" aria-label="월별 상세 보기">
            {lineData.map(point => {
              const { currentYear, currentMonth } = getCurrentDateInfo();
              const year = isDemo ? demoPeriod?.yearsByMonth[point.idx]
                : point.idx > currentMonth ? currentYear - 1 : currentYear;
              return <button key={point.idx} type="button" disabled={!yearDataReady}
                aria-label={`${year ? `${year}년 ` : ""}${point.month} 상세 보기`}
                onClick={() => onPointClickSafe({ index: point.idx })}>
                {point.month}
                {isDemo && point.idx === demoPeriod?.monthIndex && <span>기준월</span>}
              </button>;
            })}
          </nav>
      </section>

      {/* ===== 화면 2: 상세(선택 시 나타남) ===== */}
      {selectedMonth !== null && (!isDemo || demoPeriod !== null) && (
        <section id="screen2" className="jp-screen jp-detail-screen">
          <div className="jp-card">
            <div className="jp-card-head">
              <h1>{monthLabel(selectedMonth)} 상세</h1>
              <div className="jp-head-actions">
                <span className="jp-leak">누수 금액: {leakTotal === null ? "—" : KRW(leakTotal) + "원"}</span>
                <span className="jp-total">
                  | 총 소비 금액: {monthTotal === null ? "—" : KRW(monthTotal) + "원"}
                </span>
                <button
                  className="jp-close"
                  onClick={() => {
                    activePeriod.current = null;
                    setSelectedMonth(null);
                    setSaveError(null);
                    // 사용자가 위로 스크롤하여 라인차트로 올라가면 됨
                  }}
                >
                  닫기
                </button>
              </div>
            </div>

            <div className="jp-grid">
              <div className="jp-panel">
                <div className="jp-detail-status">
                  {monthlyQuery.isError ? (
                    <p role="alert">거래 내역을 불러오지 못했습니다. <button onClick={() => void monthlyQuery.refetch()}>거래 내역 다시 시도</button></p>
                  ) : monthlyQuery.isPending || monthlyQuery.fetchStatus !== "idle" ? (
                    <p role="status">거래 내역을 불러오는 중입니다.</p>
                  ) : monthlyReady && detailTxns.length === 0 ? (
                    <p role="status">거래 내역이 없습니다.</p>
                  ) : null}
                  {categoryQuery.isError && <p role="alert">누수 금액을 불러오지 못했습니다. <button onClick={() => void categoryQuery.refetch()}>누수 금액 다시 시도</button></p>}
                  {updateCategoryMutation.isPending && <p role="status">카테고리를 저장하는 중입니다.</p>}
                  {saveError?.period === String(selectedYear) + "/" + selectedMonthNum && <p role="alert">{saveError.message}</p>}
                </div>
                <div className="jp-toolbar">
                  <JPSelect
                    ariaLabel="거래 카테고리 필터"
                    className="jp-chart-select"
                    contentClassName="jp-chart-select-content"
                    value={selectedCategory}
                    disabled={!monthlyReady || monthlyQuery.fetchStatus !== "idle"}
                    onChange={(v) => setSelectedCategory(v as "전체" | Category)}
                    options={[
                      { label: "전체", value: "전체" },
                      ...CATEGORIES.map((c) => ({ label: c, value: c })),
                    ]}
                    colorMap={CATEGORY_COLORS}
                  />
                </div>

                <table className="jp-table" role="table" aria-label="월별 거래 내역">
                  <thead role="rowgroup">
                    <tr role="row">
                      <th scope="col">날짜</th>
                      <th scope="col" className="left">가맹점</th>
                      <th scope="col">금액</th>
                      <th scope="col">카테고리</th>
                    </tr>
                  </thead>
                  <tbody role="rowgroup">
                    {filteredTxns.map((tx, index) => (
                      <tr role="row" key={isTransactionId(tx.id) ? tx.id : "invalid-" + index}>
                        <td role="cell" className="jp-date-cell"><span className="jp-cell-label" aria-hidden="true">날짜</span><span className="jp-cell-value">{tx.date}</span></td>
                        <td role="cell" className="left jp-merchant-cell"><span className="jp-cell-label" aria-hidden="true">가맹점</span><span className="jp-cell-value">{tx.merchant}</span></td>
                        <td role="cell" className="jp-amount-cell"><span className="jp-cell-label" aria-hidden="true">금액</span><span className="jp-cell-value">{KRW(tx.amount)} 냥</span></td>
                        <td role="cell" className="jp-category-cell">
                          <span className="jp-cell-label" aria-hidden="true">카테고리</span>
                          <JPSelect
                            ariaLabel={`${tx.merchant} 카테고리`}
                            contentClassName="jp-chart-select-content"
                            value={tx.category}
                            disabled={!canEdit || !isTransactionId(tx.id) || !belongsToPeriod(tx.date, { year: selectedYear!, month: selectedMonthNum! })}
                            onChange={(v) =>
                              updateTxnCategory(
                                { year: selectedYear!, month: selectedMonthNum! },
                                tx.id,
                                v as Category
                              )
                            }
                            options={CATEGORIES.map((c) => ({
                              label: c,
                              value: c,
                            }))}
                            className="min-w-[120px] jp-chart-select"
                            colorMap={CATEGORY_COLORS}
                          />
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="jp-panel jp-pie-panel">
                <div className="jp-pie-frame">
                  <ResponsiveContainer width="100%" height="100%" onResize={width => setPieWidth(width)}>
                    <PieChart
                      margin={{ top: 10, right: 80, bottom: 30, left: 80 }}
                    >
                      <Pie
                        data={pieData}
                        dataKey="value"
                        nameKey="name"
                        outerRadius={compactLayout ? Math.min(200, Math.max(40, pieWidth / 2 - 12)) : 200}
                        paddingAngle={0}
                        label={renderCustomLabel}
                        labelLine={false}
                        stroke="transparent"
                        strokeWidth={0}
                      >
                        {pieData.map((entry, index) => (
                          <Cell
                            key={entry.name}
                            fill={
                              entry.color || JP_COLORS[index % JP_COLORS.length]
                            }
                            stroke="transparent"
                            strokeWidth={0}
                            onClick={() => handleClickCategory(entry.name)}
                            style={{ outline: 'none', cursor: 'pointer' }}
                          />
                        ))}
                      </Pie>
                      <Tooltip
                        formatter={(value, name) => [
                          `${KRW(toNum(value))}원`,
                          name,
                        ]}
                        position={{ x: undefined, y: undefined }} // 자동 위치 조정
                        allowEscapeViewBox={{ x: false, y: false }}
                        contentStyle={{
                          background:
                            "linear-gradient(145deg, rgba(42, 56, 84, 0.95), rgba(30, 42, 58, 0.95))",
                          border: "none",
                          borderRadius: "12px",
                          boxShadow: "0 8px 24px rgba(0, 0, 0, 0.4)",
                          color: "#f0e6d2",
                          backdropFilter: "blur(10px)",
                          fontSize: "13px",
                          fontWeight: "600",
                          padding: "12px 16px",
                        }}
                        labelStyle={{
                          color: "#ffd700",
                          fontWeight: "bold",
                          marginBottom: "4px",
                        }}
                        itemStyle={{
                          color: "#f0e6d2",
                          fontSize: "13px",
                        }}
                      />
                    </PieChart>
                  </ResponsiveContainer>
                </div>
                {compactLayout && <ul className="jp-mobile-pie-legend" aria-label="카테고리별 소비">
                  {pieData.map((entry, index) => <li key={entry.name}>
                    <span className="jp-pie-legend-dot" aria-hidden="true"
                      style={{ backgroundColor: entry.color || JP_COLORS[index % JP_COLORS.length] }} />
                    <span className="jp-pie-legend-name">{entry.name}</span>
                    <span className="jp-pie-legend-amount">{KRW(entry.value)}원</span>
                  </li>)}
                </ul>}
              </div>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
