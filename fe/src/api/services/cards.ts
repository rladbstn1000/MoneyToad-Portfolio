import { request, REQUEST_METHOD } from '../client';

// 백엔드 API 응답 스키마
interface CardApiResponse {
  cardNo: string;
  cvc: string;
}

// 프론트엔드에서 사용할 타입
export interface CardInfo {
  account: string;
  cvc: string;
}

// 어댑터 함수: cardNo → account 변환
const adaptCardResponse = (apiResponse: CardApiResponse): CardInfo => {
  return {
    account: apiResponse.cardNo,
    cvc: apiResponse.cvc,
  };
};

export const getCardInfo = async () => {
  const response = await request<CardApiResponse>({
    method: REQUEST_METHOD.GET,
    url: `${import.meta.env.VITE_BACK_URL}/api/cards`,
  });

  return adaptCardResponse(response);
};

// 카드 등록 요청 타입
export interface RegisterCardRequest {
  cardNo: string;
  cvc: string;
}

export const registerCard = async (data: RegisterCardRequest) => {
  const response = await request<CardApiResponse>({
    method: REQUEST_METHOD.POST,
    url: `${import.meta.env.VITE_BACK_URL}/api/cards`,
    data,
  });

  return adaptCardResponse(response);
};

export const deleteCard = async () => {
  const response = await request<void>({
    method: REQUEST_METHOD.DELETE,
    url: `${import.meta.env.VITE_BACK_URL}/api/cards`,
  });

  return response;
};

export const updateCard = async (data: RegisterCardRequest) => {
  const response = await request<CardApiResponse>({
    method: REQUEST_METHOD.PATCH,
    url: `${import.meta.env.VITE_BACK_URL}/api/cards`,
    data,
  });

  return adaptCardResponse(response);
};