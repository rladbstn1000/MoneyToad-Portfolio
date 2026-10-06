/** Positions refer to the native pot image (280 × 319), not a sorted row list.
 * Keep the dark hole centers below the rim and inside the curved body.
 * Decorative fracture lines may overlap at maximum severity; hole centers do not.
 * These deliberately uneven positions also stay fixed when other leaks vanish.
 */
export const POT_LEAK_ANCHORS = {
  '식비': { u: 0.34, v: 0.34, width: 0.12 },
  '카페': { u: 0.65, v: 0.48, width: 0.125 },
  '마트 / 편의점': { u: 0.24, v: 0.63, width: 0.115 },
  '문화생활': { u: 0.57, v: 0.78, width: 0.125 },
  '교통 / 차량': { u: 0.75, v: 0.31, width: 0.11 },
  '패션 / 미용': { u: 0.44, v: 0.58, width: 0.12 },
  '생활용품': { u: 0.78, v: 0.66, width: 0.105 },
  '주거 / 통신': { u: 0.53, v: 0.30, width: 0.115 },
  '건강 / 병원': { u: 0.31, v: 0.81, width: 0.11 },
  '교육': { u: 0.22, v: 0.45, width: 0.12 },
  '경조사 / 회비': { u: 0.70, v: 0.845, width: 0.105 },
  '기타': { u: 0.52, v: 0.44, width: 0.105 },
} as const;

// Only the legacy no-response presentation uses these names. The live API
// already supplies the twelve keys above; budget IDs vary between months.
const LEGACY_CATEGORY_KEYS: Record<string, keyof typeof POT_LEAK_ANCHORS> = {
  '쇼핑': '패션 / 미용', '교통': '교통 / 차량', '여가': '문화생활',
  '주거': '주거 / 통신', '통신': '카페', '반려동물': '생활용품',
  '의료/건강': '건강 / 병원', '경조사비': '경조사 / 회비', '저축/투자': '마트 / 편의점',
};

export function getPotLeakAnchor(category: string) {
  if (Object.hasOwn(POT_LEAK_ANCHORS, category)) {
    return POT_LEAK_ANCHORS[category as keyof typeof POT_LEAK_ANCHORS];
  }
  return POT_LEAK_ANCHORS[LEGACY_CATEGORY_KEYS[category] ?? '기타'];
}

export const POT_IMAGE_FRAME = {
  x: 95, y: 480 - 310 * 319 / 280, width: 310, height: 310 * 319 / 280,
} as const;

/** Original category-local severity curves. The crack artwork includes broad,
 * transparent fracture lines: doubling the step-26 base restores the original
 * 63–90-unit base range without moving or proportionally scaling the anchors.
 */
export function getPotLeakSeverity(leakAmount: number) {
  const amount = Math.max(0, leakAmount);
  return {
    crackScale: amount === 0 ? 0 : Math.max(0.4, Math.min(1.5, 0.4 + amount / 80000)),
    waterScale: amount === 0 ? 0 : Math.max(0.2, Math.min(2, 0.3 + amount / 80000)),
  };
}

function buildPotLeakGeometry(category: string, severity: ReturnType<typeof getPotLeakSeverity>) {
  const anchor = getPotLeakAnchor(category);
  const x = POT_IMAGE_FRAME.x + anchor.u * POT_IMAGE_FRAME.width;
  const y = POT_IMAGE_FRAME.y + anchor.v * POT_IMAGE_FRAME.height;
  const width = POT_IMAGE_FRAME.width * anchor.width * 2 * severity.crackScale;
  const height = width * 442 / 565;
  // The dark hole in broken.webp is at (52%, 57%), slightly below its center.
  return { anchor, ...severity, x, y, width, height, left: x - width * 0.52, top: y - height * 0.57 };
}

/** Remote/OAuth retains the original absolute-amount visual contract. */
export function getPotLeakGeometry(category: string, leakAmount: number) {
  return buildPotLeakGeometry(category, getPotLeakSeverity(leakAmount));
}

/** Local presentation maps the complete editable range to visual severity.
 * A missing budget is not a zero budget. Monetary totals are not calculated here.
 */
export function getLocalPotLeakGeometry(category: string, spending: number, budget: number | null) {
  const ratio = budget === null || spending <= 0 ? 0 : Math.max(0, Math.min(1, (spending - budget) / spending));
  const severity = ratio === 0 ? { crackScale: 0, waterScale: 0 }
    : { crackScale: 0.4 + 1.1 * ratio, waterScale: 0.3 + 1.7 * ratio };
  return { ...buildPotLeakGeometry(category, severity), ratio };
}
