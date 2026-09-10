// 카테고리 고정 리스트와 유니온 타입
export const CATEGORIES = [
  '건강 / 병원',
  '경조사 / 회비',
  '교육',
  '교통 / 차량',
  '기타',
  '마트 / 편의점',
  '문화생활',
  '보험 / 세금',
  '생활용품',
  '식비',
  '주거 / 통신',
  '카페',
  '패션 / 미용',
] as const;

export type Category = typeof CATEGORIES[number];

// === API 응답 타입 (스네이크 케이스) ===
export interface ApiCategoryPrediction {
  min: number;
  max: number;
  current: number;
  real: number;
  result: boolean;
  avg: number;
}

export interface ApiCategoryMostSpent {
  merchant: string;
  amount: number;
  date: string;
}

export interface ApiCategoryMostFrequent {
  merchant: string;
  count: number;
  total_amount: number;
}

export interface ApiCategoryDetail {
  most_spent: ApiCategoryMostSpent;
  most_frequent: ApiCategoryMostFrequent;
}

export interface ApiDoojoItem {
  month: number;
  year: number;
  categories_count: number;
  categories_prediction: Record<Category, ApiCategoryPrediction>;
  categories_detail: Record<Category, ApiCategoryDetail>;
}

export interface ApiDoojoResponse {
  file_id: string;
  doojo: ApiDoojoItem[];
}

// === 클라이언트 타입 (카멜 케이스) ===
export interface CategoryPrediction {
  min: number;
  max: number;
  current: number;
  real: number;
  result: boolean;
  avg: number;
}

export interface CategoryMostSpent {
  merchant: string;
  amount: number;
  /** ISO 8601 문자열. 예: "2025-08-15T10:30" */
  date: string;
}

export interface CategoryMostFrequent {
  merchant: string;
  count: number;
  totalAmount: number;
}

export interface CategoryDetail {
  mostSpent: CategoryMostSpent;
  mostFrequent: CategoryMostFrequent;
}

export interface DoojoItem {
  month: number; // 1~12
  year: number;  // 4자리 연도
  categoriesCount: number;
  categoriesPrediction: Record<Category, CategoryPrediction>;
  categoriesDetail: Record<Category, CategoryDetail>;
}

export interface DoojoResponse {
  fileId: string;
  doojo: DoojoItem[];
}

// === 어댑터 함수 ===
export const adaptDoojoResponse = (apiResponse: ApiDoojoResponse): DoojoResponse => {
  const adaptCategoryMostFrequent = (api: ApiCategoryMostFrequent): CategoryMostFrequent => ({
    merchant: api.merchant,
    count: api.count,
    totalAmount: api.total_amount,
  });

  const adaptCategoryDetail = (api: ApiCategoryDetail): CategoryDetail => ({
    mostSpent: {
      merchant: api.most_spent.merchant,
      amount: api.most_spent.amount,
      date: api.most_spent.date,
    },
    mostFrequent: adaptCategoryMostFrequent(api.most_frequent),
  });

  const adaptDoojoItem = (api: ApiDoojoItem): DoojoItem => ({
    month: api.month,
    year: api.year,
    categoriesCount: api.categories_count,
    categoriesPrediction: api.categories_prediction,
    categoriesDetail: Object.fromEntries(
      Object.entries(api.categories_detail).map(([key, value]) => [
        key,
        adaptCategoryDetail(value),
      ])
    ) as Record<Category, CategoryDetail>,
  });

  return {
    fileId: apiResponse.file_id,
    doojo: apiResponse.doojo.map(adaptDoojoItem),
  };
};