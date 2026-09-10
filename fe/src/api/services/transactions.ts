import { request, REQUEST_METHOD } from '../client';
import type { TransactionYear, MonthlyTransaction, CategoryTransaction, UpdateCategoryRequest, TransactionPeerYear } from '../../types';

export const getYearTransaction = async () => {
  const response = await request<TransactionYear[]>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/transactions`,
  });

  return response;
};

export const getPeerYearTransaction = async () => {
  const response = await request<TransactionPeerYear[]>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/transactions/peer`,
  });

  return response;
};

export const getMonthlyTransactions = async (year: number, month: number) => {
  const response = await request<MonthlyTransaction[]>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/transactions/${year}/${month}`,
  });

  return response;
};

export const getCategoryTransactions = async (year: number, month: number) => {
  const response = await request<CategoryTransaction[]>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/transactions/${year}/${month}/categories`,
  });

  return response;
};

export const updateTransactionCategory = async (transactionId: number, data: UpdateCategoryRequest) => {
  const response = await request<MonthlyTransaction>({
    method: REQUEST_METHOD.PATCH,
    url: `${import.meta.env.VITE_BACK_URL}/api/transactions/${transactionId}/category`,
    data,
  });

  return response;
};