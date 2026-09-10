// Shared preload assets; order and URLs are part of the existing page behavior.

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

export const scrollLandingAssets = [
  "/landing/landing1.webp",
  "/landing/landing2.webp",
  "/landing/landing3.webp",
  "/landing/landing4.webp",
];

export const mypageAssets = [
  "/mypage/close.webp",
  "/mypage/open.webp",
  "/mypage/sitting.webp",
  "/mypage/paper.webp",
];

export const notFoundAssets = [
  "/404.webp",
];

export const SCENE_BG = "/userInfo/talk-scene.webp";

export const userInfoAssets = [
  SCENE_BG,
];

export const cryingKongjwi = "/leakPot/cryingKongjwi.webp";
export const happyKongjwi = "/leakPot/happyKongjwi.webp";
export const happyToad = "/leakPot/happyToad.webp";
export const angryToad = "/leakPot/angryToad.webp";
export const bgImage = "/leakPot/joseon-bg.webp";
export const customPointer = "/leakPot/money.webp";
export const paper = "/leakPot/paper.webp";
export const potImage = "/leakPot/pot.webp";
export const broken = "/leakPot/broken.webp";
export const monthGood = "/leakPot/good.webp";
export const monthBad = "/leakPot/bad.webp";
export const badGray = "/leakPot/bad_gray.webp";
export const goodGray = "/leakPot/good_gray.webp";
export const tooltipToad = "/leakPot/tooltip.webp";

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

export const CATEGORY_ICONS: Record<string, string> = {
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
