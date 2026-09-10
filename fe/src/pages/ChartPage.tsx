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

export const chartAssets = [
  "/charts/background.webp",
  "/charts/detailBackground.webp",
  "/charts/sitting_girl.webp",
  "/charts/toad.webp",
  "/charts/water.webp",
  "/charts/flower.webp",
  "/charts/leaf.webp",
  "/charts/flower_gray.webp",
  "/charts/leaf_gray.webp",
];

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
  id: string;
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

export const CATEGORY_COLORS: Record<Category, string> = CATEGORIES.reduce(
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

const CustomTooltip = ({ active, payload, label }: any) => {
  if (active && payload && payload.length) {
    const me = payload.find((p: any) => p.dataKey === "me");
    const peer = payload.find((p: any) => p.dataKey === "peers");
    const leaked = payload[0]?.payload?.leaked || false;

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
        <div style={{ color: "#BA5910" }}>
          또래 소비 : {peer ? KRW(peer.value) : "-"}원
        </div>
      </div>
    );
  }
  return null;
};

/* ------------------------------ 더미 생성 ------------------------------ */

function seedMonth(monthIdx: number): Txn[] {
  const rnd = (seed: number) => {
    const x = Math.sin(seed * 991 + monthIdx * 37) * 10000;
    return x - Math.floor(x);
  };
  const list: Txn[] = [];
  const cnt = 14 + Math.floor(rnd(1) * 10);
  for (let i = 0; i < cnt; i++) {
    const amt = Math.round((30_000 + rnd(i + 2) * 220_000) / 100) * 100;
    const cat = CATEGORIES[Math.floor(rnd(i + 3) * CATEGORIES.length)];
    const d = 1 + Math.floor(rnd(i + 4) * 27);
    list.push({
      id: `${monthIdx}-${i}`,
      date: `${monthIdx + 1}/${String(d).padStart(2, "0")}`,
      merchant: ["방앗간", "주막", "장터", "포목점", "기와집", "전당포"][
        Math.floor(rnd(i + 5) * 6)
      ],
      amount: amt,
      category: cat,
    });
  }
  return list;
}

/* ============================== 메인 컴포넌트 ============================== */

export default function ChartPage() {
  /* 무대(연못)의 비율 고정용 상태 */
  const [pondAR, setPondAR] = useState(16 / 9);

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
  const { data: yearTransactionData } = useYearTransactionQuery();
  const { data: peerYearTransactionData } = usePeerYearTransactionQuery();

  /* 더미 트랜잭션 */
  const [txnsByMonth, setTxnsByMonth] = useState<Txn[][]>(
    Array.from({ length: 12 }, (_, i) => seedMonth(i))
  );

  const apiMonthlyData = useMemo(() => {
    if (!yearTransactionData) {
      return Array.from({ length: 12 }, (_, index) => ({
        amount: 0,
        isLastYear: false,
        leaked: false,
        month: index,
      }));
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
  }, [yearTransactionData]);

  /* 월별 합계 - API 데이터 우선 사용 */
  const myMonthly = useMemo(() => {
    if (yearTransactionData && yearTransactionData.length > 0) {
      return apiMonthlyData.map((monthData) => monthData.amount);
    }
    // API 데이터가 없으면 기존 더미 데이터 사용
    return txnsByMonth.map((m) => m.reduce((a, t) => a + t.amount, 0));
  }, [yearTransactionData, apiMonthlyData, txnsByMonth]);
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

  const peerMonthly = useMemo(() => {
    if (peerYearTransactionData && peerYearTransactionData.length > 0) {
      return apiPeerMonthlyData.map((monthData) => monthData.amount);
    }
    // API 데이터가 없으면 기존 더미 계산 로직 사용
    return myMonthly.map((v, i) =>
      Math.round(v * (0.9 + ((i * 17) % 15) / 100))
    );
  }, [peerYearTransactionData, apiPeerMonthlyData, myMonthly]);

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
    const { currentYear, currentMonth } = getCurrentDateInfo();

    // selectedMonth가 현재 달보다 큰 경우(미래 달) 작년으로 간주
    return selectedMonth > currentMonth ? currentYear - 1 : currentYear;
  }, [selectedMonth]);

  const selectedMonthNum = useMemo(() => {
    if (selectedMonth === null) return null;
    return selectedMonth + 1; // 1-based month
  }, [selectedMonth]);

  /* 월별 거래 내역 API */
  const { data: monthlyTransactionsData } = useMonthlyTransactionsQuery(
    selectedYear || 0,
    selectedMonthNum || 0
  );

  /* 카테고리별 거래 내역 API */
  const { data: categoryTransactionsData } = useCategoryTransactionsQuery(
    selectedYear || 0,
    selectedMonthNum || 0
  );

  /* 카테고리 업데이트 Mutation */
  const updateCategoryMutation = useUpdateTransactionCategoryMutation();

  /* MonthlyTransaction을 Txn으로 변환 */
  const convertToTxn = (monthlyTxn: MonthlyTransaction): Txn => ({
    id: monthlyTxn.id.toString(),
    date: monthlyTxn.transactionDateTime.split("T")[0], // YYYY-MM-DD 형태로 변환
    merchant: monthlyTxn.merchantName,
    amount: monthlyTxn.amount,
    category: monthlyTxn.category as Category,
    leaked: Boolean((monthlyTxn as any).leaked),
  });

  /* 안전 클릭 핸들러: index → payload.idx */
  const onPointClickSafe = (props: any) => {
    const idx =
      typeof props?.index === "number"
        ? props.index
        : typeof props?.payload?.idx === "number"
        ? props.payload.idx
        : null;

    if (idx !== null) {
      setSelectedMonth(idx);
      setSelectedCategory("전체");
      requestAnimationFrame(() => {
        document
          .getElementById("screen2")
          ?.scrollIntoView({ behavior: "smooth", block: "start" });
      });
    }
  };

  /* 카테고리 수정 */
  const updateTxnCategory = (month: number, id: string, cat: Category) => {
    // API 호출
    updateCategoryMutation.mutate({
      transactionId: parseInt(id), // string을 number로 변환
      data: { category: cat },
    });

    // 로컬 상태 즉시 업데이트 (optimistic update)
    setTxnsByMonth((prev) => {
      const next = prev.map((arr) => arr.slice());
      const idx = next[month].findIndex((t) => t.id === id);
      if (idx >= 0) next[month][idx] = { ...next[month][idx], category: cat };
      return next;
    });
  };

  /* 상세/필터링/합계 - API 데이터 우선 사용 */
  const detailTxns = useMemo(() => {
    if (selectedMonth === null) return [];

    if (monthlyTransactionsData && monthlyTransactionsData.length > 0) {
      // API 데이터를 Txn 형태로 변환
      return monthlyTransactionsData.map(convertToTxn);
    }

    // API 데이터가 없으면 기존 더미 데이터 사용
    return txnsByMonth[selectedMonth];
  }, [selectedMonth, monthlyTransactionsData, txnsByMonth]);

  const filteredTxns =
    selectedMonth === null
      ? []
      : selectedCategory === "전체"
      ? detailTxns
      : detailTxns.filter((t) => t.category === selectedCategory);
  const monthTotal = useMemo(() => {
    if (selectedMonth === null) return 0;

    // 연간 집계 데이터가 있다면 → 라인차트와 동일한 집계값 사용
    if (yearTransactionData && yearTransactionData.length > 0) {
      return apiMonthlyData[selectedMonth].amount;
    }

    // 없을 때만 표의 개별 거래 합으로 폴백
    return detailTxns.reduce((a, t) => a + t.amount, 0);
  }, [selectedMonth, yearTransactionData, apiMonthlyData, detailTxns]);

  // ★ 누수 합계: getCategoryTransactions API에서 leakedAmount 합산
  const leakTotal = useMemo(() => {
    if (selectedMonth === null) return 0;
    
    // API 데이터가 있으면 카테고리별 leakedAmount 합산
    if (categoryTransactionsData && categoryTransactionsData.length > 0) {
      return categoryTransactionsData.reduce((total, category) => total + category.leakedAmount, 0);
    }
    
    // API 데이터가 없으면 기존 로직 사용 (상세에 뜬 거래들 중 leaked=true인 금액만 합산)
    return detailTxns.filter((t) => t.leaked).reduce((a, t) => a + t.amount, 0);
  }, [selectedMonth, categoryTransactionsData, detailTxns]);

  /* 파이 데이터 */
  const pieData = useMemo(() => {
    if (selectedMonth === null) return [];

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
  }, [selectedMonth, selectedCategory, txnsByMonth, detailTxns, monthTotal]);

  /* 파이 라벨 */
  // 파이차트 커스텀 라벨 - 균일한 위치
  // 라벨: 이름 + 퍼센트 (>= 3%만 표시), 폰트 업
  const renderCustomLabel = ({
    cx,
    cy,
    midAngle,
    outerRadius,
    percent,
    name,
  }: any) => {
    if (percent < 0.03) return null; // 3% 미만 숨김
    const RADIAN = Math.PI / 180;
    const labelRadius = outerRadius + 52;
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
        {`${name} ${pct}%`}
      </text>
    );
  };

  /* 커스텀 점 */
  const MyConsumptionDot = (props: any): React.ReactElement<SVGElement> => {
    const { cx, cy, index, payload } = props;
    if (cx == null || cy == null) return <g />;

    const flower = chartAssets[5];
    const leaf = chartAssets[6];
    const deadFlower = chartAssets[7];
    const deadLeaf = chartAssets[8];

    const isCurrentMonth = index === getCurrentDateInfo().currentMonth;
    const leaked = payload?.leaked || false;
    const isLastYear = payload?.isLastYear || false;

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

  const PeerDot = (props: any): React.ReactElement<SVGElement> => {
    const { cx, cy } = props;
    if (cx == null || cy == null) return <g />;
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
        {/* '무대' : 고정 비율 컨테이너 */}
        <div className="jp-stage" style={{ aspectRatio: pondAR }}>
          <div className="jp-page-title-section">
            <h1>월간 소비 비교</h1>
            <p>연꽃과 잎을 클릭하면 해당 달의 상세 소비를 볼 수 있습니다!</p>
          </div>

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
            {/* @ts-ignore */}
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
                <Line
                  type="monotone"
                  dataKey="peers"
                  name="또래 소비"
                  stroke="#a47690ff"
                  strokeWidth={3}
                  dot={PeerDot}
                  activeDot={{ r: 7 }}
                />
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
      </section>

      {/* ===== 화면 2: 상세(선택 시 나타남) ===== */}
      {selectedMonth !== null && (
        <section id="screen2" className="jp-screen jp-detail-screen">
          <div className="jp-card">
            <div className="jp-card-head">
              <h1>{monthLabel(selectedMonth)} 상세</h1>
              <div className="jp-head-actions">
                <span className="jp-leak">누수 금액: {KRW(leakTotal)}원</span>
                <span className="jp-total">
                  | 총 소비 금액: {KRW(monthTotal)}원
                </span>
                <button
                  className="jp-close"
                  onClick={() => {
                    setSelectedMonth(null);
                    // 사용자가 위로 스크롤하여 라인차트로 올라가면 됨
                  }}
                >
                  닫기
                </button>
              </div>
            </div>

            <div className="jp-grid">
              <div className="jp-panel">
                <div className="jp-toolbar">
                  <JPSelect
                    value={selectedCategory}
                    onChange={(v) => setSelectedCategory(v as "전체" | Category)}
                    options={[
                      { label: "전체", value: "전체" },
                      ...CATEGORIES.map((c) => ({ label: c, value: c })),
                    ]}
                    colorMap={CATEGORY_COLORS}
                  />
                </div>

                <table className="jp-table">
                  <thead>
                    <tr>
                      <th>날짜</th>
                      <th className="left">가맹점</th>
                      <th>금액</th>
                      <th>카테고리</th>
                    </tr>
                  </thead>
                  <tbody>
                    {filteredTxns.map((tx) => (
                      <tr key={tx.id}>
                        <td>{tx.date}</td>
                        <td className="left">{tx.merchant}</td>
                        <td>{KRW(tx.amount)} 냥</td>
                        <td>
                          <JPSelect
                            value={tx.category}
                            onChange={(v) =>
                              updateTxnCategory(
                                selectedMonth!,
                                tx.id,
                                v as Category
                              )
                            }
                            options={CATEGORIES.map((c) => ({
                              label: c,
                              value: c,
                            }))}
                            className="min-w-[120px]"
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
                  <ResponsiveContainer width="100%" height="100%">
                    <PieChart
                      margin={{ top: 10, right: 80, bottom: 30, left: 80 }}
                    >
                      <Pie
                        data={pieData}
                        dataKey="value"
                        nameKey="name"
                        outerRadius={200}
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
                        formatter={(value: any, name: any) => [
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
              </div>
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
