export type Gender = "여성" | "남성" | "";

export interface TransactionYear {
  date: string;
  totalAmount: number;
  leaked: boolean;
}

export type TransactionPeerYear = Omit<TransactionYear, 'leaked'>;

export interface MonthlyTransaction {
  id: number;
  transactionDateTime: string;
  amount: number;
  merchantName: string;
  category: string;
}

export interface CategoryTransaction {
  category: string;
  totalAmount: number;
  leakedAmount: number;
}

export interface UpdateCategoryRequest {
  category: string;
}

export interface MonthlyBudgetResponse {
  id: number;
  budget: number;
  spending: number;
  category: string;
  initialBudget: number;
}

export interface YearlyBudgetLeakResponse {
  budgetDate: string;
  leaked: boolean;
}

export interface UpdateBudgetRequest {
  budgetId: number;
  budget: number;
}

export interface UpdateBudgetResponse {
  budgetId: number;
  budget: number;
}

export * from './doojo';
