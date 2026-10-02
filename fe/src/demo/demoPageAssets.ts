import { chartAssets, leakPotAssets, mypageAssets, notFoundAssets, scrollLandingAssets,
  toadAdviceAssets, userInfoAssets } from '../assets/pageAssets';

export function demoPageAssets(path: string): readonly string[] {
  if (path === '/') return scrollLandingAssets;
  if (path === '/pot') return [];
  if (path.startsWith('/pot/')) return leakPotAssets;
  if (path === '/chart') return chartAssets;
  if (path === '/toadAdvice') return toadAdviceAssets;
  if (path === '/mypage') return mypageAssets;
  if (path === '/userInfo') return userInfoAssets;
  if (path === '/auth/callback') return [];
  return notFoundAssets;
}
