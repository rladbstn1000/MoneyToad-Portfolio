import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { HttpResponse, http } from 'msw';
import { setupServer } from 'msw/node';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { cardQueryKeys, userQueryKeys } from '../src/api/queryKeys';
import { getCardInfo, registerCard, updateCard } from '../src/api/services/cards';
import MyPage from '../src/pages/Mypage';
import UserInfoInputPage from '../src/pages/UserInfoInputPage';

const ORIGIN = 'http://127.0.0.1:18080';
// Generated nonfinancial form data; no real card information is used.
const cardDigits = Array.from({ length: 16 }, (_, index) => String(index % 10)).join('');
const formattedCard = cardDigits.match(/.{4}/g)!.join('-');
const changedDigits = [...cardDigits].reverse().join('');
const changedCard = changedDigits.match(/.{4}/g)!.join('-');
const discardedDetail = 'synthetic-rejected-detail';
let storedCard = '';
let failSave = false;
let unexpected = 0;
let writes: { method: string; body: unknown }[] = [];
const clients: QueryClient[] = [];

const server = setupServer(
  http.get(`${ORIGIN}/api/users`, () => HttpResponse.json({ name: '합성 사용자', gender: '여성', age: 20 })),
  http.get(`${ORIGIN}/api/cards`, () => HttpResponse.json({ cardNo: storedCard, cvc: discardedDetail })),
  http.patch(`${ORIGIN}/api/users`, async ({ request }) => {
    writes.push({ method: 'USER PATCH', body: await request.json() });
    return new HttpResponse(null, { status: 204 });
  }),
  ...(['post', 'patch'] as const).map(method => http[method](`${ORIGIN}/api/cards`, async ({ request }) => {
    const body = await request.json();
    writes.push({ method: method.toUpperCase(), body });
    if (failSave) return HttpResponse.json({ message: discardedDetail, details: { cvc: discardedDetail } }, { status: 400 });
    if (typeof body === 'object' && body !== null && 'cardNo' in body && typeof body.cardNo === 'string') {
      storedCard = body.cardNo;
    }
    return HttpResponse.json({ cardNo: storedCard, cvc: discardedDetail }, { status: method === 'post' ? 201 : 200 });
  })),
);

beforeAll(() => server.listen({ onUnhandledRequest() { unexpected++; throw new Error('Unexpected card form request'); } }));
afterAll(() => server.close());
beforeEach(() => {
  storedCard = ''; failSave = false; unexpected = 0; writes = [];
  localStorage.clear();
});
afterEach(() => {
  cleanup();
  for (const client of clients.splice(0)) client.clear();
  vi.useRealTimers();
  expect(unexpected).toBe(0);
});

function mount(path: '/mypage' | '/userInfo') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  clients.push(client);
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={[path]}><Routes>
    <Route path="/mypage" element={<MyPage />} />
    <Route path="/userInfo" element={<UserInfoInputPage />} />
    <Route path="/pot/:month" element={<h1>장독대 도착</h1>} />
  </Routes></MemoryRouter></QueryClientProvider>);
  return client;
}

async function openPaper() {
  const client = mount('/mypage');
  await waitFor(() => {
    expect(client.getQueryData(userQueryKeys.info())).toBeDefined();
    expect(client.getQueryData(cardQueryKeys.info())).toEqual({ account: storedCard });
  });
  fireEvent.click(screen.getByRole('button', { name: '문 열기' }));
  fireEvent.click(screen.getByRole('button', { name: '콩쥐에게 다가가기' }));
  fireEvent.click(screen.getByRole('button', { name: '문서 보기' }));
  return screen.getByRole('dialog', { name: '내 정보 수정' });
}

function reachCardStep() {
  vi.useFakeTimers();
  mount('/userInfo');
  const completeQuestion = () => act(() => vi.advanceTimersByTime(5000));
  completeQuestion();
  fireEvent.click(screen.getByRole('button', { name: '알겠어요' }));
  completeQuestion();
  fireEvent.click(screen.getByRole('button', { name: '여성' }));
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  completeQuestion();
  fireEvent.change(screen.getByLabelText('나이'), { target: { value: '20' } });
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  completeQuestion();
  vi.useRealTimers();
  expect(screen.queryByText(/CVC/i)).not.toBeInTheDocument();
  expect(screen.queryByLabelText(/CVC/i)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '정보 입력 완료' })).toBeDisabled();
  fireEvent.change(screen.getByLabelText('계좌번호'), { target: { value: cardDigits } });
  expect(screen.getByRole('button', { name: '정보 입력 완료' })).toBeEnabled();
}

describe('card service limits request and response fields at runtime', () => {
  it('discards legacy response fields while retaining the card number adapter', async () => {
    storedCard = formattedCard;
    expect(await getCardInfo()).toEqual({ account: formattedCard });
    expect(writes).toEqual([]);
  });
  it.each([['POST', registerCard], ['PATCH', updateCard]] as const)('%s projects only cardNo, including untyped extra fields', async (method, save) => {
    const legacyCaller = { cardNo: formattedCard, cvc: discardedDetail, extra: 'discard' };
    expect(await save(legacyCaller)).toEqual({ account: formattedCard });
    expect(writes).toEqual([{ method, body: { cardNo: formattedCard } }]);
  });
});

describe('OAuth storeroom card form', () => {
  it('registers without collecting, validating or displaying CVC', async () => {
    const dialog = await openPaper();
    expect(within(dialog).queryByText(/CVC/i)).not.toBeInTheDocument();
    const submit = within(dialog).getByRole('button', { name: '카드 등록' });
    expect(submit).toBeDisabled();
    fireEvent.change(within(dialog).getByLabelText('카드번호'), { target: { value: cardDigits } });
    expect(submit).toBeEnabled();
    fireEvent.click(submit);
    await waitFor(() => expect(writes).toEqual([{ method: 'POST', body: { cardNo: formattedCard } }]));
    expect(within(dialog).queryByLabelText(/CVC/i)).not.toBeInTheDocument();
    expect(within(dialog).getByRole('button', { name: '카드 변경' })).toBeInTheDocument();
  });
  it('keeps cancellation and saves the changed number without a second field', async () => {
    storedCard = formattedCard;
    const dialog = await openPaper();
    fireEvent.click(within(dialog).getByRole('button', { name: '카드 변경' }));
    fireEvent.change(within(dialog).getByLabelText('카드번호'), { target: { value: changedDigits } });
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));
    expect(writes).toEqual([]);
    fireEvent.click(within(dialog).getByRole('button', { name: '카드 변경' }));
    expect(within(dialog).getByLabelText('카드번호')).toHaveValue(formattedCard);
    fireEvent.change(within(dialog).getByLabelText('카드번호'), { target: { value: changedDigits } });
    fireEvent.click(within(dialog).getByRole('button', { name: '카드 변경' }));
    await waitFor(() => expect(writes).toEqual([{ method: 'PATCH', body: { cardNo: changedCard } }]));
    expect(within(dialog).queryByText(/CVC/i)).not.toBeInTheDocument();
  });
  it('restores the editable number on rejection without logging response details or retrying', async () => {
    storedCard = formattedCard; failSave = true;
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const dialog = await openPaper();
    fireEvent.click(within(dialog).getByRole('button', { name: '카드 변경' }));
    fireEvent.change(within(dialog).getByLabelText('카드번호'), { target: { value: changedDigits } });
    fireEvent.click(within(dialog).getByRole('button', { name: '카드 변경' }));
    await waitFor(() => expect(within(dialog).getByLabelText('카드번호')).toHaveValue(changedCard));
    expect(log.mock.calls).toEqual([['카드 수정에 실패했습니다.']]);
    expect(writes).toEqual([{ method: 'PATCH', body: { cardNo: changedCard } }]);
    expect(dialog).not.toHaveTextContent(discardedDetail);
  });
});

describe('OAuth introduction card step', () => {
  it('completes the original stages with only a card number before saving basic information', async () => {
    reachCardStep();
    fireEvent.click(screen.getByRole('button', { name: '정보 입력 완료' }));
    await screen.findByRole('heading', { name: '장독대 도착' });
    expect(writes).toEqual([
      { method: 'POST', body: { cardNo: formattedCard } },
      { method: 'USER PATCH', body: { age: 20, gender: '여성' } },
    ]);
  });
  it('keeps failed registration on the form, without basic-info mutation or raw error output', async () => {
    failSave = true;
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const alert = vi.spyOn(window, 'alert').mockImplementation(() => {});
    reachCardStep();
    fireEvent.click(screen.getByRole('button', { name: '정보 입력 완료' }));
    await waitFor(() => expect(alert).toHaveBeenCalledWith('정보 등록 중 오류가 발생했습니다. 다시 시도해주세요.'));
    expect(log.mock.calls).toEqual([['정보 등록에 실패했습니다.']]);
    expect(writes).toEqual([{ method: 'POST', body: { cardNo: formattedCard } }]);
    expect(screen.getByLabelText('계좌번호')).toHaveValue(formattedCard);
    expect(screen.queryByRole('heading', { name: '장독대 도착' })).not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(discardedDetail);
  });
});
