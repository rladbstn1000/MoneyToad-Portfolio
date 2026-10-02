import { authMode } from "../auth/authMode";
import { useDemoExperience } from "../demo/useDemoExperience";
import { DEMO_AGES } from "../demo/demoProfile";
import { Link } from "react-router-dom";
import { SCENE_BG } from "../assets/pageAssets";
import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useRegisterCardMutation } from "../api/mutation/cardMutation";
import { useUpdateUserBasicInfoMutation } from "../api/mutation/userMutation";
import "./UserInfoInputPage.css";


// 유틸
const digitsOnly = (v: string, max?: number) =>
  v.replace(/\D/g, "").slice(0, typeof max === "number" ? max : undefined);

const formatAccount = (raw: string) => {
  const d = digitsOnly(raw, 16);
  const parts = d.match(/.{1,4}/g) || [];
  return parts.join("-").slice(0, 19); // 0000-0000-0000-0000
};

type Gender = "여성" | "남성" | "";
type Step = "intro" | "gender" | "age" | "account";

export default function UserInfoInputPage() {
  return authMode === "demo" ? <DemoUserInfoInput /> : <OAuthUserInfoInput />;
}

function OAuthUserInfoInput() {
  const navigate = useNavigate();
  const registerCardMutation = useRegisterCardMutation();
  const updateUserBasicInfoMutation = useUpdateUserBasicInfoMutation();

  // Fog
  const [, setFogGone] = useState(false);
  useEffect(() => {
    const t = setTimeout(() => setFogGone(true), 1200);
    return () => clearTimeout(t);
  }, []);

  // Form states
  const [gender, setGender] = useState<Gender>("");
  const [age, setAge] = useState("");
  const [account, setAccount] = useState("");
  const [cvc, setCvc] = useState("");
  const [step, setStep] = useState<Step>("intro");
  const [touched, setTouched] = useState(false);

  // 타자 효과 (두꺼비 질문)
  const [typing, setTyping] = useState(true);
  const [replyReady, setReplyReady] = useState(false); // ← 두꺼비 말 끝나야 콩쥐 등장
  const typeRef = useRef<HTMLSpanElement>(null);

  const question = useMemo(() => {
    switch (step) {
      case "intro":
        return "반갑구나! 지금부터 너의 성별, 나이, 카드 정보를 물을 게다.\n네 장독대를 맞춤으로 꾸미고, 지출을 계산하는 데 쓰일 것이니 안심하거라.";
      case "gender":
        return "먼저 성별을 고르거라.";
      case "age":
        return "다음은 나이를 입력하거라. 숫자만! 예: 20";
      case "account":
        return "마지막으로 카드 정보다.\n카드번호(4-4-4-4)와\nCVC(3자리)만 적어주면 된다.";
      default:
        return "";
    }
  }, [step]);

  // 질문이 바뀌면 타자 시작 + 콩쥐 숨김
  useEffect(() => {
    setTyping(true);
    setReplyReady(false);
  }, [question]);

  // 타자 효과 실행
  useEffect(() => {
    if (!typing) return;
    const el = typeRef.current;
    if (!el) return;
    const text = question;
    el.textContent = "";
    let i = 0;
    const it = setInterval(() => {
      el.textContent = text.slice(0, ++i);
      if (i >= text.length) {
        clearInterval(it);
        setTyping(false);
        setTimeout(() => setReplyReady(true), 200); // 두꺼비 말 끝난 뒤 콩쥐 등장
      }
    }, 18);
    return () => clearInterval(it);
  }, [typing, question]);

  // 검증
  const ageValid = useMemo(() => /^\d+$/.test(age) && age.length > 0, [age]);
  const accountValid = useMemo(
    () => /^\d{4}-\d{4}-\d{4}-\d{4}$/.test(account),
    [account]
  );
  const cvcValid = useMemo(() => /^\d{3}$/.test(cvc), [cvc]);
  const allValid = gender !== "" && ageValid && accountValid && cvcValid;

  // 이동
  const toNext = () => {
    if (step === "intro") {
      setStep("gender");
      setTouched(false);
      return;
    }
    if (step === "gender") {
      if (!gender) return setTouched(true);
      setStep("age");
      setTouched(false);
      return;
    }
    if (step === "age") {
      if (!ageValid) return setTouched(true);
      setStep("account");
      setTouched(false);
      return;
    }
  };

  const goFinish = async () => {
    setTouched(true);
    if (!allValid) return;
    
    try {
      await registerCardMutation.mutateAsync({
        cardNo: account,
        cvc
      });

      await updateUserBasicInfoMutation.mutateAsync({
        age: Number(age),
        gender
      });

      // 성공 후 페이지 이동
      const month = new Date().getMonth() + 1; // 1~12
      navigate(`/pot/${month}`);
    } catch (error) {
      console.error('API 호출 실패:', error);
      alert('정보 등록 중 오류가 발생했습니다. 다시 시도해주세요.');
    }
  };

  const cancel = () => navigate("/");

  return (
    <div className="ui-wrap">
      {/* 배경 */}
      <div
        className="ui-scene"
        style={{ backgroundImage: `url(${SCENE_BG})` }}
        aria-live="polite"
      >
        {/* 가운데 대화 컨테이너 */}
        <div className="ui-convo">
          {/* 두꺼비(질문) */}
          <div className="bubble toad tail-right center" aria-live="polite">
            <span ref={typeRef} className={typing ? "typing" : ""}>
              {!typing && question}
            </span>
          </div>

          {/* 콩쥐(응답) - 두꺼비 말이 끝난 뒤에만 표시 */}
          {step === "intro" && replyReady && (
            <div className="bubble kong tail-left center appear">
              <div className="ui-actions center">
                <button className="btn ghost" type="button" onClick={cancel}>
                  그만두기
                </button>
                <button className="btn primary" type="button" onClick={toNext}>
                  알겠어요
                </button>
              </div>
            </div>
          )}

          {step === "gender" && replyReady && (
            <div className="bubble kong tail-left center appear">
              <span className="label">성별</span>
              <div className="gender-group">
                {(["여성", "남성"] as const).map((g) => (
                  <button
                    key={g}
                    type="button"
                    className={`gender-btn ${gender === g ? "on" : ""}`}
                    aria-pressed={gender === g}
                    onClick={() => setGender(g)}
                  >
                    {g}
                  </button>
                ))}
              </div>
              {touched && !gender && (
                <small className="help err">성별을 선택해 주세요.</small>
              )}
              <div className="ui-actions center">
                <button className="btn ghost" type="button" onClick={cancel}>
                  그만두기
                </button>
                <button className="btn primary" type="button" onClick={toNext}>
                  다음
                </button>
              </div>
            </div>
          )}

          {step === "age" && replyReady && (
            <div className="bubble kong tail-left center appear">
              <label>
                <span className="label">나이</span>
                <input
                  className={`input ${touched && !ageValid ? "err" : ""}`}
                  placeholder="예) 20"
                  inputMode="numeric"
                  pattern="\d*"
                  maxLength={2}
                  value={age}
                  onChange={(e) => setAge(digitsOnly(e.target.value, 2))}
                  onBlur={() => setTouched(true)}
                  aria-invalid={touched && !ageValid}
                />
              </label>
              <small className="help">숫자만 입력돼요. (20살 X → 20 O)</small>
              <div className="ui-actions center">
                <button className="btn ghost" type="button" onClick={cancel}>
                  그만두기
                </button>
                <button className="btn primary" type="button" onClick={toNext}>
                  다음
                </button>
              </div>
            </div>
          )}

          {step === "account" && replyReady && (
            <>
              {/* 단락 1: 계좌번호 */}
              <div className="bubble kong paper tail-left center appear">
                <label>
                  <span className="label">계좌번호</span>
                  <input
                    className={`input ${
                      touched && !accountValid ? "err" : ""
                    }`}
                    placeholder="0000-0000-0000-0000"
                    inputMode="numeric"
                    maxLength={19}
                    value={account}
                    onChange={(e) => setAccount(formatAccount(e.target.value))}
                    onBlur={() => setTouched(true)}
                    aria-invalid={touched && !accountValid}
                  />
                </label>
                <small className="help">
                  숫자만 입력되고, 자동으로 하이픈이 들어가요.
                </small>
              </div>

              {/* 단락 2: CVC */}
              <div className="bubble kong tail-left center appear">
                <label>
                  <span className="label">CVC</span>
                  <input
                    className={`input ${touched && !cvcValid ? "err" : ""}`}
                    placeholder="000"
                    inputMode="numeric"
                    maxLength={3}
                    value={cvc}
                    onChange={(e) => setCvc(digitsOnly(e.target.value, 3))}
                    onBlur={() => setTouched(true)}
                    aria-invalid={touched && !cvcValid}
                  />
                </label>
              </div>

              {/* 버튼 */}
              <div className="ui-actions center appear">
                <button className="btn ghost" type="button" onClick={cancel}>
                  그만두기
                </button>
                <button
                  className="btn primary"
                  type="button"
                  onClick={goFinish}
                  disabled={!allValid}
                  aria-disabled={!allValid}
                >
                  정보 입력 완료
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

const demoQuestions = [
  '반갑구나! 오늘은 가상의 콩쥐가 되어 장독대를 둘러보자.\n샘플 인물과 연습용 카드를 함께 골라 보거라.',
  '먼저 샘플 인물의 성별을 골라 보거라.\n너의 실제 정보를 알려 줄 필요는 없단다.',
  '이번에는 샘플 인물의 나이를 골라 보거라.\n준비된 나이 중 마음에 드는 것을 고르면 된다.',
  '마지막으로 연습용 카드를 골라 보거라.\n잎과 꽃 중 어떤 그림이 마음에 드느냐?',
];

function DemoUserInfoInput() {
  const { profile, updateProfile } = useDemoExperience();
  const [draft, setDraft] = useState(profile);
  const [step, setStep] = useState(0);
  const [typed, setTyped] = useState('');
  const [ready, setReady] = useState(false);
  const [revealed, setRevealed] = useState('');
  const navigate = useNavigate();
  const question = demoQuestions[step];
  useEffect(() => {
    setTyped('');
    setReady(false);
    if (revealed === question || window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setTyped(question); setReady(true); return;
    }
    let count = 0;
    let reply: ReturnType<typeof setTimeout> | undefined;
    const timer = setInterval(() => {
      count += 1;
      setTyped(question.slice(0, count));
      if (count >= question.length) {
        clearInterval(timer);
        reply = setTimeout(() => setReady(true), 200);
      }
    }, 18);
    return () => { clearInterval(timer); clearTimeout(reply); };
  }, [question, revealed]);
  const finish = () => { updateProfile(draft); navigate('/pot'); };
  return <main className="ui-wrap ui-demo" data-demo-page="user-info">
    <div className="ui-scene" style={{ backgroundImage: `url(${SCENE_BG})` }}>
      <div className="ui-demo-top"><Link to="/mypage">콩쥐의 곳간으로</Link><span>샘플 설정 {step + 1} / 4</span></div>
      <div className="ui-convo">
        <h1 className="ui-demo-title">콩쥐와 첫 인사</h1>
        <p className="ui-demo-note">가상의 설정만 골라요. 새로고침하면 초기화돼요.</p>
        <div className="bubble toad tail-right center" aria-live="polite"><span className="ui-demo-sr-only">{question}</span><span aria-hidden="true" className={ready ? '' : 'typing'}>{typed}</span></div>
        {!ready && <button className="btn ghost ui-demo-skip" type="button" onClick={() => setRevealed(question)}>대화 바로 보기</button>}
        {ready && <div className="bubble kong tail-left center appear">
          {step === 0 && <p>샘플 인물의 설정을 바꿔 볼게요!</p>}
          {step === 1 && <fieldset><legend>샘플 성별</legend><div className="gender-group">{(['여성', '남성'] as const).map(gender => <button key={gender} className={`gender-btn ${draft.gender === gender ? 'on' : ''}`} type="button" aria-pressed={draft.gender === gender} onClick={() => setDraft(current => ({ ...current, gender }))}>{gender}</button>)}</div></fieldset>}
          {step === 2 && <fieldset><legend>샘플 나이</legend><div className="ui-demo-choices">{DEMO_AGES.map(age => <button key={age} className="btn ghost" type="button" aria-pressed={draft.age === age} onClick={() => setDraft(current => ({ ...current, age }))}>{age}세</button>)}</div></fieldset>}
          {step === 3 && <fieldset><legend>샘플 카드</legend><div className="ui-demo-choices">{(['A', 'B'] as const).map(cardPreset => <button key={cardPreset} className="btn ghost ui-demo-card" type="button" aria-pressed={draft.cardPreset === cardPreset} onClick={() => setDraft(current => ({ ...current, cardPreset }))}><span aria-hidden="true">{cardPreset === 'A' ? '🌿' : '🌼'}</span> 샘플 카드 {cardPreset}</button>)}</div><p className="help">모양만 바뀌고 준비된 거래와 예산은 유지돼요.</p></fieldset>}
          <div className="ui-actions center">{step > 0 && <button className="btn ghost" type="button" onClick={() => setStep(current => current - 1)}>이전</button>}<button className="btn primary" type="button" onClick={step === 3 ? finish : () => setStep(current => current + 1)}>{step === 0 ? '샘플 설정 시작' : step === 3 ? '샘플 설정 완료' : '다음'}</button></div>
        </div>}
        <button className="btn ghost ui-demo-direct" type="button" onClick={finish}>샘플로 바로 시작</button>
      </div>
    </div>
  </main>;
}
