import { useEffect, useMemo, useRef, useState } from "react";
import Header from "../components/Header";
import "./Mypage.css";
import { useUserInfoQuery } from "../api/queries/userQuery";
import { useUpdateUserBasicInfoMutation } from "../api/mutation/userMutation";
import { useCardInfoQuery } from "../api/queries/cardQuery";
import { useRegisterCardMutation, useUpdateCardMutation } from "../api/mutation/cardMutation";
import type { UserInfo as ApiUserInfo } from "../types/user";
import type { Gender } from "../types";
import type { CardInfo } from "../api/services/cards";

type Phase = "CLOSED" | "OPEN" | "SITTING" | "PAPER";
type LocalUserInfo = {
  gender: Gender;
  age: number | null;
  account?: string; // 저장/표시는 하이픈 포함 “0000-0000-0000-0000”
  cvc?: string;     // 저장/표시는 숫자 3자리
};

const IMG_CLOSE  = "/mypage/close.webp";
const IMG_OPEN   = "/mypage/open.webp";
const IMG_SIT    = "/mypage/sitting.webp";
const IMG_PAPER  = "/mypage/paper.webp";

export const mypageAssets = [
  "/mypage/close.webp",
  "/mypage/open.webp",
  "/mypage/sitting.webp",
  "/mypage/paper.webp",
];

// ---- utils (간단 버전) ----
const digitsOnly = (v: string, max?: number) =>
  v.replace(/\D/g, "").slice(0, typeof max === "number" ? max : undefined);

const formatAccountByDigits = (digits: string) => {
  const d = digitsOnly(digits, 16);
  const parts = d.match(/.{1,4}/g) || [];
  return parts.join("-").slice(0, 19);
};

const maskAccount = (acct?: string) => {
  if (!acct || !/^\d{4}-\d{4}-\d{4}-\d{4}$/.test(acct)) return "";
  const last4 = acct.slice(-4);
  return `****-****-****-${last4}`;
};

const loadUser = (apiUserData?: ApiUserInfo, cardData?: CardInfo): LocalUserInfo => {
  if (apiUserData) {
    const { gender, age } = apiUserData;
    return {
      gender: gender || "",
      age: age || null,
      account: cardData?.account, // 서버가 전체 번호를 내려준다는 전제. 마스킹이면 재입력하게 됨.
      cvc: cardData?.cvc,
    };
  }
  return { gender: "", age: null };
};

export default function MyPage() {
  const [phase, setPhase] = useState<Phase>("CLOSED");

  const { data: userData } = useUserInfoQuery();
  const { data: cardData } = useCardInfoQuery();
  const updateUserBasicInfoMutation = useUpdateUserBasicInfoMutation();
  const registerCardMutation = useRegisterCardMutation();
  const updateCardMutation = useUpdateCardMutation();

  const [user, setUser] = useState<LocalUserInfo>(() => loadUser(userData, cardData));

  // 보기/편집 모드 제어
  const [isCardEditing, setIsCardEditing] = useState(false);

  // 편집 중 인풋(마스킹 없이 보여줄 실제 값)
  // account는 digits만 보관하고 화면에선 하이픈 넣어 표시
  const [accountDigits, setAccountDigits] = useState("");
  const [cvcDigits, setCvcDigits] = useState("");

  // PAPER 열릴 때 폼 초기화
  const prevPhase = useRef<Phase>(phase);
  useEffect(() => {
    if (phase === "PAPER" && prevPhase.current !== "PAPER") {
      const fresh = loadUser(userData, cardData);
      setUser(fresh);
      setGEditing(fresh.gender);
      setAgeEditing(fresh.age === null ? "" : String(fresh.age));
      // 편집값 초기화
      setIsCardEditing(false);
      setAccountDigits("");
      setCvcDigits("");
    }
    prevPhase.current = phase;
  }, [phase, userData, cardData]);

  // 기본 정보 편집
  const [gEditing, setGEditing] = useState<Gender>(user.gender);
  const [ageEditing, setAgeEditing] = useState<string>(
    user.age === null ? "" : String(user.age)
  );

  // 유효성
  const accountValid = useMemo(() => accountDigits.length === 16, [accountDigits]);
  const cvcValid = useMemo(() => cvcDigits.length === 3, [cvcDigits]);
  const ageValid = useMemo(
    () => /^\d+$/.test(ageEditing) && Number(ageEditing) > 0,
    [ageEditing]
  );

  // 변경 여부
  const basicDirty = useMemo(() => {
    const ageNum = ageEditing ? Number(ageEditing) : null;
    return gEditing !== user.gender || (user.age ?? null) !== ageNum;
  }, [gEditing, user.gender, user.age, ageEditing]);

  const handleSaveBasic = () => {
    if (!ageValid || !basicDirty) return;
    updateUserBasicInfoMutation.mutate({
      gender: gEditing,
      age: Number(ageEditing),
    });
  };

  // 카드 편집 시작
  const handleEditCard = () => {
    setIsCardEditing(true);
    // 서버에서 받은 번호가 있다면 그걸로 프리필 (마스킹이 아닌 전체가 왔다는 전제)
    const initialDigits = user.account ? digitsOnly(user.account, 16) : "";
    setAccountDigits(initialDigits);
    setCvcDigits(user.cvc ? digitsOnly(user.cvc, 3) : "");
  };

  const handleCancelCardEdit = () => {
    setIsCardEditing(false);
    setAccountDigits("");
    setCvcDigits("");
  };

  // 카드 입력 onChange (간단/견고)
  const onChangeAccount = (e: React.ChangeEvent<HTMLInputElement>) => {
    setAccountDigits(digitsOnly(e.target.value, 16));
  };
  const onChangeCvc = (e: React.ChangeEvent<HTMLInputElement>) => {
    setCvcDigits(digitsOnly(e.target.value, 3));
  };

  // 표시용 포맷
  const accountInputValue = useMemo(
    () => formatAccountByDigits(accountDigits),
    [accountDigits]
  );

  // 저장 (변경 / 등록 공용)
  const submitCard = (mode: "update" | "register") => {
    if (!accountValid || !cvcValid) return;

    const cardNo = formatAccountByDigits(accountDigits); // 하이픈 포함
    const cvc = cvcDigits;

    // 낙관적 업데이트
    setUser((u) => ({ ...u, account: cardNo, cvc }));
    setIsCardEditing(false);
    setAccountDigits("");
    setCvcDigits("");

    const mutateFn =
      mode === "update" ? updateCardMutation.mutate : registerCardMutation.mutate;

    mutateFn(
      { cardNo, cvc },
      {
        onError: (error) => {
          console.error(mode === "update" ? "카드 수정 실패:" : "카드 등록 실패:", error);
          // 롤백
          setUser(loadUser(userData, cardData));
          // 편집모드로 복귀 + 입력값 복구
          setIsCardEditing(true);
          setAccountDigits(digitsOnly(cardNo, 16));
          setCvcDigits(cvc);
        },
      }
    );
  };

  const handleUpdateCard = () => submitCard("update");
  const handleRegisterCard = () => submitCard("register");

  const closeToHome = () => setPhase("CLOSED");

  return (
    <div className="mp-wrap">
      <div className="mp-fixed-header">
        <Header />
      </div>

      {/* 배경 레이어 */}
      {phase !== "PAPER" ? (
        <img
          className="mp-bg"
          src={phase === "CLOSED" ? IMG_CLOSE : phase === "OPEN" ? IMG_OPEN : IMG_SIT}
          alt={
            phase === "CLOSED"
              ? "문이 닫힌 초가집"
              : phase === "OPEN"
              ? "문이 열린 초가집, 콩쥐가 안에서 손짓"
              : "방 안의 콩쥐가 문서를 내미는 장면"
          }
          draggable={false}
        />
      ) : (
        <>
          <img className="mp-bg" src={IMG_SIT} alt="방 안 배경" draggable={false} />

          {/* 종이 모달 */}
          <div className="paper-modal" role="dialog" aria-modal="true" aria-label="내 정보 수정">
            <div className="paper-stage">
              <img className="paper-img" src={IMG_PAPER} alt="" aria-hidden="true" />
              <div className="paper-content">
                <header className="paper-header">
                  <strong>콩쥐의 정보 수정하기</strong>
                  <button className="btn ghost" onClick={closeToHome} aria-label="닫기">
                    닫기
                  </button>
                </header>

                <div className="paper-body">
                  {/* 기본 정보 */}
                  <section className="paper-block">
                    <h3>기본</h3>

                    <div className="grid two">
                      <label className="field">
                        <span>이름</span>
                        <div className="readonly-value">
                          {userData?.name || ""}
                        </div>
                      </label>
                      <label className="field">
                        <span>성별</span>
                        <div className="seg">
                          {(["여성", "남성"] as const).map((g) => (
                            <button
                              key={g}
                              type="button"
                              className={`chip ${gEditing === g ? "on" : ""}`}
                              aria-pressed={gEditing === g}
                              onClick={() => setGEditing(g)}
                            >
                              {g}
                            </button>
                          ))}
                        </div>
                      </label>

                      <label className="field">
                        <span>나이</span>
                        <input
                          className={`input ${ageEditing && !ageValid ? "err" : ""}`}
                          inputMode="numeric"
                          maxLength={2}
                          placeholder="예) 20"
                          value={ageEditing}
                          onChange={(e) => setAgeEditing(digitsOnly(e.target.value, 2))}
                        />
                      </label>
                    </div>

                    {basicDirty && (
                      <div className="row-end appear">
                        <button
                          className="btn primary"
                          onClick={handleSaveBasic}
                          disabled={!ageValid}
                        >
                          기본 정보 저장
                        </button>
                      </div>
                    )}
                  </section>

                  {/* 카드 정보 */}
                  <section className="paper-block">
                    <h3>카드</h3>

                    {/* 보기 모드: 마스킹 표시 */}
                    {user.account && !isCardEditing ? (
                      <div className="card-view">
                        <div>
                          <div className="kv">
                            <span className="k">카드번호</span>
                            <span className="v mono">{maskAccount(user.account)}</span>
                          </div>
                          <div className="kv">
                            <span className="k">CVC</span>
                            <span className="v mono">***</span>
                          </div>
                        </div>
                        <button className="btn primary" onClick={handleEditCard}>
                          카드 변경
                        </button>
                      </div>
                    ) : (
                      // 편집/등록 모드: 마스킹 없이 그대로 보여주기(카드번호는 자동 하이픈)
                      <>
                        <div className="grid two">
                          <label className="field">
                            <span>카드번호</span>
                            <input
                              className={`input ${accountDigits && !accountValid ? "err" : ""}`}
                              placeholder="0000-0000-0000-0000"
                              inputMode="numeric"
                              maxLength={19}
                              value={accountInputValue}
                              onChange={onChangeAccount}
                            />
                          </label>
                          <label className="field">
                            <span>CVC</span>
                            <input
                              className={`input ${cvcDigits && !cvcValid ? "err" : ""}`}
                              placeholder="000"
                              inputMode="numeric"
                              maxLength={3}
                              value={cvcDigits}
                              onChange={onChangeCvc}
                            />
                          </label>
                        </div>

                        <div className="row-end appear">
                          {user.account && (
                            <button className="btn ghost" onClick={handleCancelCardEdit}>
                              취소
                            </button>
                          )}
                          <button
                            className="btn primary"
                            onClick={user.account ? handleUpdateCard : handleRegisterCard}
                            disabled={!accountValid || !cvcValid}
                          >
                            {user.account ? "카드 변경" : "카드 등록"}
                          </button>
                        </div>
                      </>
                    )}
                  </section>
                </div>
              </div>
            </div>
          </div>
        </>
      )}

      {/* 인터랙션 핫스팟 */}
      {phase === "CLOSED" && (
        <button className="hotspot door" aria-label="문 열기" onClick={() => setPhase("OPEN")} />
      )}
      {phase === "OPEN" && (
        <button className="hotspot kong" aria-label="콩쥐에게 다가가기" onClick={() => setPhase("SITTING")} />
      )}
      {phase === "SITTING" && (
        <button className="hotspot paper" aria-label="문서 보기" onClick={() => setPhase("PAPER")} />
      )}

      {/* 하단 힌트 */}
      <div className="hint">
        {phase === "CLOSED" && "문을 클릭하세요"}
        {phase === "OPEN" && "콩쥐를 클릭하세요"}
        {phase === "SITTING" && "콩쥐가 내미는 종이를 클릭하세요"}
        {phase === "PAPER" && "종이에서 정보를 관리하세요"}
      </div>
    </div>
  );
}
