import type { ReactNode } from "react";
import { useCallback, useMemo, useState } from "react";
import { useDoojoQuery } from "../api";
import { useYearlyBudgetLeaksQuery } from '../api/queries/budgetQuery';
import Header from "../components/Header";
import type { YearlyBudgetLeakResponse } from '../types';
import "./ToadAdvice.css";

/* ===== 카테고리 아이콘 (.webp) ===== */
const CATEGORY_ICONS: Record<string, string> = {
  식비: "/toadAdvice/eat.webp",
  카페: "/toadAdvice/tea.webp",
  "마트/편의점": "/toadAdvice/market.webp",
  문화생활: "/toadAdvice/culture.webp",
  "교통 / 차량": "/toadAdvice/transport.webp",
  "패션 / 미용": "/toadAdvice/fashion.webp",
  생활용품: "/toadAdvice/living.webp",
  "주거 / 통신": "/toadAdvice/house.webp",
  "건강 / 병원": "/toadAdvice/health.webp",
  교육: "/toadAdvice/edu.webp",
  "경조사 / 회비": "/toadAdvice/event.webp",
  "보험 / 세금": "/toadAdvice/tax.webp",
  기타: "/toadAdvice/etc.webp",
};
const getCategoryImage = (category: string) =>
  CATEGORY_ICONS[category] ?? "/toadAdvice/etc.webp";

/* ===== 프리로드 자산(.webp) ===== */
export const toadAdviceAssets = [
  ...Object.values(CATEGORY_ICONS),
  "/toadAdvice/background.webp",
  "/toadAdvice/total.webp",
  "/toadAdvice/card.webp",
  "/leakPot/good.webp",
  "/leakPot/bad.webp",
  "/leakPot/good_gray.webp",
  "/leakPot/bad_gray.webp",
];

/* ===== 공통 유틸 ===== */
const won = (n: number) =>
  new Intl.NumberFormat("ko-KR", { maximumFractionDigits: 0 }).format(n) + "냥";
const estimateAvg = (min: number, max: number) => (min + max) / 2;
const normalizeKey = (s: string) => s.replace(/\s*\/\s*/g, "/").trim();
const fmtDate = (iso: string) => {
  const d = new Date(iso);
  const mm = d.getMonth() + 1,
    dd = d.getDate();
  const hh = String(d.getHours()).padStart(2, "0");
  const mi = String(d.getMinutes()).padStart(2, "0");
  const wd = ["일", "월", "화", "수", "목", "금", "토"][d.getDay()];
  return `${mm}/${dd} ${hh}:${mi}(${wd})`;
};
const highlightNumbers = (text: string): ReactNode[] => {
  const re = /\d[\d,]*(?:\.\d+)?(?:%|원|냥)?/g;
  const parts: ReactNode[] = [];
  let last = 0;
  let m: RegExpExecArray | null;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) parts.push(text.slice(last, m.index));
    parts.push(
      <span key={parts.length} className="highlight-number">
        {m[0]}
      </span>
    );
    last = re.lastIndex;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
};
const multiline = (t?: string) => t ?? "";

/* ===== 휴리스틱 & 코멘트 템플릿 (기능 유지) ===== */
const H = {
  isLateNight: (iso: string) => {
    const h = new Date(iso).getHours();
    return h >= 22 || h < 6;
  },
  avgPerVisit: (total: number, cnt: number) =>
    cnt > 0 ? Math.round(total / cnt) : 0,
};
const BIG_TICKET: Record<string, number> = {
  식비: 120,
  카페: 20,
  "마트/편의점": 80,
  "교통 / 차량": 100,
  "패션 / 미용": 150,
  교육: 200,
  생활용품: 60,
  문화생활: 70,
  "보험 / 세금": 150,
  "주거 / 통신": 120,
  "경조사 / 회비": 50,
  "건강 / 병원": 80,
  기타: 60,
};
const pctLabel = (pct: number) =>
  pct === 0
    ? "12개월 평균과 같음"
    : `12개월 평균보다 ${Math.abs(pct).toFixed(1)}% ${
        pct > 0 ? "높음" : "낮음"
      }`;

type MostSpent = { merchant: string; amount: number; date: string };
type MostFrequent = { merchant: string; count: number; totalAmount: number };
type CategoryDetail = { mostSpent: MostSpent; mostFrequent: MostFrequent };
type DetailMap = Record<string, CategoryDetail>;
type SpentCtx = {
  merchant: string;
  amount: number;
  date: string;
  over: number;
};
type FreqCtx = { merchant: string; count: number; total: number };
type Pair = { spent: (c: SpentCtx) => string; freq: (c: FreqCtx) => string };

const TEMPLATES: Record<string, Pair> = {
  식비: {
    spent: ({ date }) =>
      [
        H.isLateNight(date) ? "야밤 결제가 눈에 띄오." : "식사 규모 관리가 필요하오.",
        "• ‘식사 전 10분 대기’로 충동구매를 누르시오.",
        "• 주 1~2회 외식, 나머진 집밥/밀프rep로 바꾸시오.",
      ].join("\n"),
    freq: ({ count, total }) => {
      const avg = H.avgPerVisit(total, count);
      return [
        `월 ${count}회 방문(평균 ${won(avg)}). 잦은 소액 누수 패턴이오.`,
        "• 평일 점심만 허용 같은 ‘상황 규칙’을 두시오.",
      ].join("\n");
    },
  },
  카페: {
    spent: () =>
      [
        "카페 지출은 상시 누수 위험이오.",
        "• 텀블러·원두구독으로 대체하시오.",
        "• ‘평일 1회’ 같은 횟수 제한을 두시오.",
      ].join("\n"),
    freq: ({ count, total }) => {
      const avg = H.avgPerVisit(total, count);
      return [
        `월 ${count}회 · 평균 ${won(avg)}.`,
        "• 출근길 1회만 허용, 나머진 사무실 커피로 전환하시오.",
      ].join("\n");
    },
  },
  "마트/편의점": {
    spent: ({ date }) =>
      [
        "목록 없이 장보면 장바구니가 무거워지오.",
        "• 품목·수량 한도표를 미리 적으시오.",
        H.isLateNight(date)
          ? "• 야간·공복 장보기 금지!"
          : "• 세일만 노리는 ‘목록 장보기’를 습관화하시오.",
      ].join("\n"),
    freq: ({ count, total }) => {
      const avg = H.avgPerVisit(total, count);
      return [
        `월 ${count}회(평균 ${won(avg)}). 잦은 소액 누수이오.`,
        "• 배달앱은 ‘장바구니 10분 룰’을 도입하시오.",
      ].join("\n");
    },
  },
  "패션 / 미용": {
    spent: () =>
      [
        `시즌성 지출은 ‘분기 1회·한도 ${won(BIG_TICKET["패션 / 미용"])}’로 관리하시오.`,
        "• 24시간 보류 후 결제.",
      ].join("\n"),
    freq: ({ count, total }) =>
      [
        `월 ${count}회 · 총 ${won(total)}.`,
        "• 미용 주기를 6~8주로 늘리고 예약제로 변동을 줄이시오.",
      ].join("\n"),
  },
  "교통 / 차량": {
    spent: () => ["이동비는 고정성 지출이오.", "• 월 이동 예산 캡 설정."].join("\n"),
    freq: ({ count, total }) =>
      [`월 ${count}회 · 총 ${won(total)}.`, "• 대중교통·주유 자동충전 한도 설정."].join("\n"),
  },
  교육: {
    spent: () => ["배움은 훌륭하오만 중복 수강은 독이 되오.", "• ‘동시 수강 1개’ 원칙."].join("\n"),
    freq: ({ count }) =>
      [`월 ${count}회 결제.`, "• 이수율 80% 미만이면 다음 달 결제 정지."].join("\n"),
  },
  생활용품: {
    spent: () => ["소모품은 단가를 낮추는 것이 핵심이오.", "• 정기배송·대용량으로 전환."].join("\n"),
    freq: ({ count, total }) =>
      [`월 ${count}회 · 총 ${won(total)}.`, "• 메모앱 재고 체크로 중복 구매 방지."].join("\n"),
  },
  문화생활: {
    spent: () => ["문화비는 상한선을 명확히 하시오.", "• ‘월 1~2회’ 캡, 얼리버드/멤버십 활용."].join("\n"),
    freq: ({ count, total }) =>
      [`월 ${count}회 · 총 ${won(total)}.`, "• OTT/멤버십 중복 결제 점검."].join("\n"),
  },
  "보험 / 세금": {
    spent: () => ["갱신·특약 중복 점검 시기요.", "• 카드납 할인 여부 확인."].join("\n"),
    freq: ({ total }) => [`총 ${won(total)} 고정비.`, "• 연 1회 리밸런싱."].join("\n"),
  },
  "주거 / 통신": {
    spent: () => ["요금제·결합할인 재점검으로 절감 여지."].join("\n"),
    freq: ({ total }) => [`총 ${won(total)}.`, "• 사용량 대비 과금 상이 여부 확인."].join("\n"),
  },
  "경조사 / 회비": {
    spent: () => ["이벤트성 지출은 예비비로 선반영."].join("\n"),
    freq: ({ total }) => [`총 ${won(total)}.`, "• 회비는 분기 결제로 묶어 관리."].join("\n"),
  },
  "건강 / 병원": {
    spent: () => ["비급여 반복이면 대체병원·보험 청구 확인."].join("\n"),
    freq: ({ total }) => [`총 ${won(total)}.`, "• 약/보충제 정기구독으로 단가 절감."].join("\n"),
  },
  기타: {
    spent: () => ["‘기타’가 커지면 추적이 어려워지오.", "• 태그 세분화로 원인 드러내기."].join("\n"),
    freq: ({ total }) => [`총 ${won(total)}.`, "• 선물/잡화는 ‘월 한도’로 관리."].join("\n"),
  },
};

const buildInsights = (category: string, d?: CategoryDetail, over?: number) => {
  if (!d) return { spentText: "데이터가 없소.", freqText: "데이터가 없소." };
  const key = normalizeKey(category);
  const tpl = TEMPLATES[key] ?? {
    spent: ({ merchant, amount, date }: SpentCtx) =>
      `${merchant}에서 ${won(amount)}(${fmtDate(date)}). 일회성 큰 지출로 보이오.`,
    freq: ({ merchant, count, total }: FreqCtx) => {
      const avg = H.avgPerVisit(total, count);
      return `${merchant} 월 ${count}회 · 1회 평균 ${won(avg)}. 반복 누수 주의하시오.`;
    },
  };
  return {
    spentText: tpl.spent({
      merchant: d.mostSpent.merchant,
      amount: d.mostSpent.amount,
      date: d.mostSpent.date,
      over: over ?? 0,
    }),
    freqText: tpl.freq({
      merchant: d.mostFrequent.merchant,
      count: d.mostFrequent.count,
      total: d.mostFrequent.totalAmount,
    }),
  };
};

/* ===== 월 선택 네비게이션 ===== */
const MONTHS = Array.from({ length: 12 }, (_, i) => ({
  value: i + 1,
  label: `${i + 1}월`,
}));

const MonthNavigation: React.FC<{
  selectedMonth: number;
  nowMonth: number;
  nowYear: number;
  leakMonthData: YearlyBudgetLeakResponse[] | undefined;
  onMonthChange: (month: number) => void;
}> = ({ selectedMonth, nowMonth, nowYear, leakMonthData, onMonthChange }) => {
  return (
    <div className="ta-month-navigation">
      <div className="ta-month-grid">
        {MONTHS.map((m) => {
          const isLastYear = m.value > nowMonth;
          const yearForBtn = isLastYear ? nowYear - 1 : nowYear;
          const leaked = leakMonthData?.find(
            (month) =>
              month.budgetDate ===
              `${yearForBtn}-${String(m.value).padStart(2, "0")}`
          )?.leaked;

          const imgSrc = isLastYear
            ? leaked
              ? "/leakPot/bad_gray.webp"
              : "/leakPot/good_gray.webp"
            : leaked
            ? "/leakPot/bad.webp"
            : "/leakPot/good.webp";

          return (
            <button
              key={m.value}
              onClick={() => onMonthChange(m.value)}
              onMouseDown={(e) => e.preventDefault()}
              className={`ta-month-button ${m.value === selectedMonth ? "active" : ""}`}
            >
              <img src={imgSrc} alt={leaked ? "누수" : "정상"} className="ta-month-img" draggable={false} />
              <span className={`ta-month-badge ${leaked ? "leaked" : "good"}`}>
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

/* ===== 메인 컴포넌트 ===== */
export default function ToadAdvice() {
  // 오늘 (연/월)
  const now = new Date();
  const nowMonth = now.getMonth() + 1;
  const nowYear = now.getFullYear();

  const {
      data: yearlyLeakData,
      isLoading: isYearlyLeakLoading,
      error: yearlyLeakError,
    } = useYearlyBudgetLeaksQuery();

  // 선택 월 (기본: 현재 달) — "선택 안했을 때는 현재 달" 규칙
  const [selectedMonth, setSelectedMonth] = useState<number>(nowMonth);

  // 선택 월의 연도: 현재달보다 큰 월은 작년
  const selectedYear = selectedMonth > nowMonth ? nowYear - 1 : nowYear;

  // ✅ 선택한 연/월로 API 호출 (순서 중요: state 계산 이후)
  const { data: doojoData, isLoading, error } = useDoojoQuery(selectedYear, selectedMonth);

  // 시트 선택 (API가 해당 월만 내려오면 0번, 여러 개면 find)
  const selectedSheet = useMemo(() => {
    const sheets = doojoData?.doojo ?? [];
    if (sheets.length === 1) return sheets[0];
    return sheets.find((s: any) => s.month === selectedMonth && s.year === selectedYear) ?? sheets[0];
  }, [doojoData?.doojo, selectedMonth, selectedYear]);

  // 상세 맵
  const detailsByCategory = useMemo<DetailMap>(() => {
    if (!selectedSheet?.categoriesDetail) return {};
    const out: DetailMap = {};
    Object.entries(selectedSheet.categoriesDetail as Record<string, CategoryDetail>).forEach(([k, v]) => {
      out[normalizeKey(k)] = v;
    });
    return out;
  }, [selectedSheet]);

  // 예측 목록 (object/array 모두 지원)
  const preds = useMemo(() => {
    if (!selectedSheet?.categoriesPrediction) return [];
    const cp = selectedSheet.categoriesPrediction as any;
    if (Array.isArray(cp)) return cp.map((x: any) => ({ title: x.title, ...x }));
    return Object.entries(cp).map(([title, data]: any) => ({ title, ...data }));
  }, [selectedSheet]);

  // 카드 데이터 (result=true)
  const advices = useMemo(() => {
    return preds
      .filter((v: any) => v.result)
      .slice(0, 12)
      .map((v: any) => {
        const avg = estimateAvg(v.min, v.max);
        const pct = avg > 0 ? ((v.real - avg) / avg) * 100 : 0;
        const over = Math.max(0, v.real - v.current);
        const detail =
          `'${v.title}'의 누수 기준액이 ${won(v.current)}인데\n실제로 ${won(v.real)}만큼 썼고\n` +
          `${pctLabel(pct)}로 보이오.\n${won(over)}만큼의 과소비가 발생했소!`;
        const preview = `과소비 ${won(over)} · ${pctLabel(pct).replace("12개월 ", "")}`;
        return { id: v.title, category: v.title, detail, preview, over, pct };
      })
      .sort((a, b) => b.over - a.over);
  }, [preds]);

  const hasAdvice = advices.length > 0;
  const totalOverspend = useMemo(() => advices.reduce((s, a) => s + a.over, 0), [advices]);
  const avgPct = useMemo(
    () => (advices.length > 0 ? Math.round(advices.reduce((s, a) => s + a.pct, 0) / advices.length) : 0),
    [advices]
  );

  // 모달
  const [open, setOpen] = useState<null | { id: string; title: string; detail: string; over: number }>(null);
  const openDetail: CategoryDetail | undefined = open ? detailsByCategory[normalizeKey(open.title)] : undefined;
  const insight = open ? buildInsights(open.title, openDetail, open.over) : null;

  // 월 변경 핸들러
  const handleMonthChange = useCallback((m: number) => setSelectedMonth(m), []);

  // 로딩/에러 처리
  if (isLoading || isYearlyLeakLoading) {
    return (
      <div className="toad-advice-container snap-container">
        <Header />
        <div style={{ padding: "2rem", textAlign: "center", color: "#fff" }}>
          <h2>데이터를 불러오는 중...</h2>
        </div>
      </div>
    );
  }
  if (error || !selectedSheet || yearlyLeakError) {
    return (
      <div className="toad-advice-container">
        <Header />
        <div style={{ padding: "2rem", textAlign: "center", color: "#fff" }}>
          <h2>데이터가 아직 준비되지 않았습니다.</h2>
        </div>
      </div>
    );
  }

  return (
    <div className="toad-advice-container snap-container">
      {/* ===== Page 1: 히어로 (월 선택 + 요약 액자) ===== */}
      <section className="page page-hero">
        <Header />
        <h1 className="main-title">두꺼비의 소비내역 조언소</h1>
        <p className="main-subtitle">원하는 달을 선택하면 해당 월의 누수 내역을 볼 수 있어요</p>

        <div className="hero-bottom">
          {/* 좌측: 월 선택 네비 */}
          <MonthNavigation
            selectedMonth={selectedMonth}
            nowMonth={nowMonth}
            nowYear={nowYear}
            leakMonthData={yearlyLeakData}
            onMonthChange={handleMonthChange}
          />

          {/* 우측: 요약 액자 */}
          <div className="stats-card big">
            <h2 className="stats-title big">
              {selectedYear < nowYear ? `작년 ${selectedMonth}월 과소비 현황` : `${selectedMonth}월 과소비 현황`}
            </h2>
            {hasAdvice ? (
              <div className="stats-grid big">
                <div className="stat-item stat-red">
                  <div className="stat-number">{advices.length}개</div>
                  <div className="stat-label">과소비 항목</div>
                </div>
                <div className="stat-item stat-orange">
                  <div className="stat-number">{won(totalOverspend)}</div>
                  <div className="stat-label">총 과소비 금액</div>
                </div>
                <div className="stat-item stat-blue">
                  <div className="stat-number">{avgPct}%</div>
                  <div className="stat-label">평균 초과율</div>
                </div>
              </div>
            ) : (
              <div className="stats-empty">축하하오! 과소비 항목이 없소!</div>
            )}
          </div>
        </div>

        {hasAdvice && <div className="scroll-hint">아래로 스크롤</div>}
      </section>

      {/* ===== Page 2: 카드 목록 (누수 있을 때만) ===== */}
      {hasAdvice && (
        <section className="page page-cards">
          <header className="cards-page-header">
            <h2 className="cards-page-title">
              {selectedYear < nowYear ? `작년 ${selectedMonth}월 과소비 요약` : `${selectedMonth}월 과소비 요약`}
            </h2>
            <div className="cards-page-stats">
              <div className="cstat">
                <div className="cstat-number">{advices.length}개</div>
                <div className="cstat-label">과소비 항목</div>
              </div>
              <div className="cstat">
                <div className="cstat-number">{won(totalOverspend)}</div>
                <div className="cstat-label">총 과소비 금액</div>
              </div>
              <div className="cstat">
                <div className="cstat-number">{avgPct}%</div>
                <div className="cstat-label">평균 초과율</div>
              </div>
            </div>
          </header>

          <div className="cards-grid">
            {advices.map((advice, index) => (
              <div
                key={advice.id}
                className="advice-card slide-in-up"
                style={{ animationDelay: `${index * 60}ms` }}
                onClick={() =>
                  setOpen({
                    id: advice.id,
                    title: advice.category,
                    detail: advice.detail,
                    over: advice.over,
                  })
                }
              >
                <div className="card-inner">
                  <div className="severity-badge">!</div>
                  <div className="card-content">
                    <div className="card-header">
                      <img
                        className="category-img"
                        src={getCategoryImage(advice.category)}
                        alt={advice.category}
                        width={44}
                        height={44}
                        draggable={false}
                      />
                      <div className="amount-info">
                        <div className="over-amount">-{won(advice.over)}</div>
                        <div className="over-percent">
                          {advice.pct === 0
                            ? "평균과 같음"
                            : `평균보다 ${Math.abs(advice.pct).toFixed(1)}% ${advice.pct > 0 ? "높음" : "낮음"}`}
                        </div>
                      </div>
                    </div>
                    <h3 className="category-title">{advice.category}</h3>
                    <div className="progress-container">
                      <div className="progress-bar">
                        <div
                          className="progress-fill"
                          style={{ width: `${Math.min(100, (advice.pct / 50) * 100)}%` }}
                        />
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* ===== 모달 ===== */}
      {open && (
        <div className="modal-overlay">
          <div className="modal-backdrop" onClick={() => setOpen(null)} />
          <div className="modal-content scale-in">
            <button className="modal-close" onClick={() => setOpen(null)}>
              ×
            </button>

            <div className="modal-flex">
              {/* 왼쪽 */}
              <div className="modal-left">
                <div className="modal-header">
                  <img
                    className="modal-icon"
                    src={getCategoryImage(open.title)}
                    alt={open.title}
                    width={120}
                    height={120}
                    draggable={false}
                  />
                  <h3 className="modal-title">{open.title}</h3>
                  <div className="modal-amount">과소비: {won(open.over)}</div>
                </div>
                <div className="modal-body">
                  <p className="modal-detail">{highlightNumbers(open.detail)}</p>
                </div>
              </div>

              {/* 오른쪽 */}
              <aside className="modal-right">
                <h4 className="insights-title">AI를 활용한 두꺼비의 코멘트</h4>

                <div className="insight-card">
                  <div className="insight-tag">가장 많이 쓴 곳</div>
                  {openDetail ? (
                    <>
                      <div className="insight-merchant">{openDetail.mostSpent.merchant}</div>
                      <div className="insight-meta">
                        <span>{won(openDetail.mostSpent.amount)}</span>
                        <span className="dot">•</span>
                        <span>{fmtDate(openDetail.mostSpent.date)}</span>
                      </div>
                      <p className="insight-ai">{multiline(insight?.spentText)}</p>
                    </>
                  ) : (
                    <div className="insight-empty">데이터가 없소.</div>
                  )}
                </div>

                <div className="insight-card">
                  <div className="insight-tag">가장 자주 간 곳</div>
                  {openDetail ? (
                    <>
                      <div className="insight-merchant">{openDetail.mostFrequent.merchant}</div>
                      <div className="insight-meta">
                        <span>{openDetail.mostFrequent.count}회</span>
                        <span className="dot">•</span>
                        <span>총 {won(openDetail.mostFrequent.totalAmount)}</span>
                      </div>
                      <p className="insight-ai">{multiline(insight?.freqText)}</p>
                    </>
                  ) : (
                    <div className="insight-empty">데이터가 없소.</div>
                  )}
                </div>
              </aside>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
