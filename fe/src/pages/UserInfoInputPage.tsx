import { useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { useRegisterCardMutation } from "../api/mutation/cardMutation";
import { useUpdateUserBasicInfoMutation } from "../api/mutation/userMutation";
import "./UserInfoInputPage.css";

const SCENE_BG = "/userInfo/talk-scene.webp";

export const userInfoAssets = [
  SCENE_BG,
];

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
