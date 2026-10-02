import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import DemoExperienceProvider from '../../src/demo/DemoExperienceProvider';
import { useDemoExperience } from '../../src/demo/useDemoExperience';
import { applyDemoProfile, createDemoProfile, type DemoProfileUpdate } from '../../src/demo/demoProfile';
import MyPage from '../../src/pages/Mypage';
import UserInfoInputPage from '../../src/pages/UserInfoInputPage';
import { useAuthStore } from '../../src/store/authStore';

function ProfileObservation() {
  const { profile } = useDemoExperience();
  return <pre data-testid="sample-profile">{JSON.stringify(profile)}</pre>;
}
function Experience({ generation = 1, path = '/mypage' }: { generation?: number; path?: string }) {
  return <MemoryRouter initialEntries={[path]}><DemoExperienceProvider key={generation}>
    <ProfileObservation />
    <Routes><Route path="/mypage" element={<MyPage />} /><Route path="/userInfo" element={<UserInfoInputPage />} /><Route path="/pot" element={<h1>장독대 도착</h1>} /></Routes>
  </DemoExperienceProvider></MemoryRouter>;
}
function profile() { return JSON.parse(screen.getByTestId('sample-profile').textContent ?? '{}') as ReturnType<typeof createDemoProfile>; }
function openPaper() {
  fireEvent.click(screen.getByRole('button', { name: '문 열기' }));
  expect(screen.getByAltText('문이 열린 초가집에서 손짓하는 콩쥐')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '콩쥐에게 다가가기' }));
  expect(screen.getByAltText('종이를 내미는 콩쥐')).toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: '샘플 정보 보기' }));
  return screen.getByRole('dialog', { name: '콩쥐의 정보 수정하기' });
}
function reveal() { fireEvent.click(screen.getByRole('button', { name: '대화 바로 보기' })); }

beforeEach(() => {
  useAuthStore.setState({ status: 'authenticated', accessToken: null, expiresAt: null, operation: null, generation: 1, revision: 1 });
  vi.spyOn(Storage.prototype, 'setItem');
  vi.spyOn(globalThis, 'fetch');
  vi.spyOn(XMLHttpRequest.prototype, 'open');
});
afterEach(() => {
  cleanup();
  expect(Storage.prototype.setItem).not.toHaveBeenCalled();
  expect(globalThis.fetch).not.toHaveBeenCalled();
  expect(XMLHttpRequest.prototype.open).not.toHaveBeenCalled();
  vi.useRealTimers();
});

describe('demo sample profile has no financial or persistent fields', () => {
  it('only keeps the fixed name and allowed sample choices, including untyped callers', () => {
    const result = applyDemoProfile(createDemoProfile(), { gender: 'other', age: 99, cardPreset: 'X', displayName: 'override', extra: 'discard' } as unknown as DemoProfileUpdate);
    expect(result).toEqual({ displayName: '체험 콩쥐', gender: '여성', age: 20, cardPreset: 'A' });
    expect(Object.keys(result).sort()).toEqual(['age', 'cardPreset', 'displayName', 'gender']);
  });
  it.each([20, 30, 40] as const)('accepts the %i year sample without changing the fixed identity', age => {
    expect(applyDemoProfile(createDemoProfile(), { age, gender: '남성', cardPreset: 'B' })).toEqual({ displayName: '체험 콩쥐', gender: '남성', age, cardPreset: 'B' });
  });
});

describe('restored demo storeroom', () => {
  it('preserves the four scenes and saves or cancels sample edits without mounting API hooks', () => {
    const view = render(<Experience />);
    expect(screen.getByAltText('문이 닫힌 초가집')).toBeInTheDocument();
    const dialog = openPaper();
    expect(view.container.querySelector('input')).toBeNull();
    fireEvent.click(within(dialog).getByRole('button', { name: '남성' }));
    fireEvent.change(within(dialog).getByLabelText('샘플 나이'), { target: { value: '30' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /샘플 카드 B/ }));
    expect(profile()).toEqual(createDemoProfile());
    fireEvent.click(within(dialog).getByRole('button', { name: '변경 취소' }));
    expect(within(dialog).getByLabelText('샘플 나이')).toHaveValue('20');
    expect(within(dialog).getByRole('button', { name: /샘플 카드 A/ })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.change(within(dialog).getByLabelText('샘플 나이'), { target: { value: '40' } });
    fireEvent.click(within(dialog).getByRole('button', { name: /샘플 카드 B/ }));
    fireEvent.click(within(dialog).getByRole('button', { name: '샘플 설정 저장' }));
    expect(profile()).toMatchObject({ age: 40, cardPreset: 'B' });
    expect(within(dialog).getByRole('status')).toHaveTextContent('샘플 설정을 저장했어요.');
    expect(within(dialog).getByRole('button', { name: '샘플 설정 저장' })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole('button', { name: '정보 창 닫기' }));
    fireEvent.click(screen.getByRole('button', { name: '샘플 정보 보기' }));
    expect(screen.getByLabelText('샘플 나이')).toHaveValue('40');
  });
  it('traps dialog keyboard focus, dismisses with Escape and restores the scene action', async () => {
    const user = userEvent.setup();
    render(<Experience />);
    const dialog = openPaper();
    expect(within(dialog).getByRole('button', { name: '정보 창 닫기' })).toHaveFocus();
    await user.tab({ shift: true });
    expect(within(dialog).getByRole('button', { name: '변경 취소' })).toHaveFocus();
    await user.tab();
    expect(within(dialog).getByRole('button', { name: '정보 창 닫기' })).toHaveFocus();
    fireEvent.change(within(dialog).getByLabelText('샘플 나이'), { target: { value: '30' } });
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '샘플 정보 보기' })).toHaveFocus();
    fireEvent.click(screen.getByRole('button', { name: '샘플 정보 보기' }));
    expect(screen.getByLabelText('샘플 나이')).toHaveValue('20');
  });
  it('keeps edits across route navigation and token rotation, and resets when the visit key changes', () => {
    const view = render(<Experience />);
    openPaper();
    fireEvent.click(screen.getByRole('button', { name: /샘플 카드 B/ }));
    fireEvent.click(screen.getByRole('button', { name: '샘플 설정 저장' }));
    fireEvent.click(screen.getByRole('button', { name: '정보 창 닫기' }));
    fireEvent.click(screen.getByRole('link', { name: '정보 입력 과정 체험' }));
    expect(screen.getByRole('heading', { name: '콩쥐와 첫 인사' })).toBeInTheDocument();
    expect(profile().cardPreset).toBe('B');
    act(() => useAuthStore.setState({ revision: 2 }));
    view.rerender(<Experience generation={1} />);
    expect(profile().cardPreset).toBe('B');
    view.rerender(<Experience generation={2} />);
    expect(profile()).toEqual(createDemoProfile());
  });
});

describe('restored sample introduction', () => {
  it('types a question before showing the reply, and clears pending timers when unmounted', () => {
    vi.useFakeTimers();
    const view = render(<Experience path="/userInfo" />);
    expect(screen.queryByRole('button', { name: '샘플 설정 시작' })).not.toBeInTheDocument();
    act(() => vi.advanceTimersByTime(4000));
    expect(screen.getByRole('button', { name: '샘플 설정 시작' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '샘플 설정 시작' }));
    view.unmount();
    expect(vi.getTimerCount()).toBe(0);
  });
  it('supports all four steps, back navigation and completion at the period-aware pot route', () => {
    const view = render(<Experience path="/userInfo" />);
    reveal();
    fireEvent.click(screen.getByRole('button', { name: '샘플 설정 시작' })); reveal();
    fireEvent.click(screen.getByRole('button', { name: '남성' }));
    fireEvent.click(screen.getByRole('button', { name: '다음' })); reveal();
    fireEvent.click(screen.getByRole('button', { name: '30세' }));
    fireEvent.click(screen.getByRole('button', { name: '이전' })); reveal();
    expect(screen.getByRole('button', { name: '남성' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '다음' })); reveal();
    expect(screen.getByRole('button', { name: '30세' })).toHaveAttribute('aria-pressed', 'true');
    fireEvent.click(screen.getByRole('button', { name: '다음' })); reveal();
    fireEvent.click(screen.getByRole('button', { name: /샘플 카드 B/ }));
    expect(view.container.querySelector('input')).toBeNull();
    expect(profile()).toEqual(createDemoProfile());
    fireEvent.click(screen.getByRole('button', { name: '샘플 설정 완료' }));
    expect(screen.getByRole('heading', { name: '장독대 도착' })).toBeInTheDocument();
    expect(profile()).toMatchObject({ gender: '남성', age: 30, cardPreset: 'B' });
  });
  it('lets a visitor start immediately without entering personal information', () => {
    render(<Experience path="/userInfo" />);
    fireEvent.click(screen.getByRole('button', { name: '샘플로 바로 시작' }));
    expect(screen.getByRole('heading', { name: '장독대 도착' })).toBeInTheDocument();
    expect(profile()).toEqual(createDemoProfile());
  });
});
