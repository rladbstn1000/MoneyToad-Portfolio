export const DEMO_AGES = [20, 30, 40] as const;

export type DemoProfile = {
  displayName: '체험 콩쥐';
  gender: '여성' | '남성';
  age: typeof DEMO_AGES[number];
  cardPreset: 'A' | 'B';
};

export type DemoProfileUpdate = Partial<Pick<DemoProfile, 'gender' | 'age' | 'cardPreset'>>;

export function createDemoProfile(): DemoProfile {
  return { displayName: '체험 콩쥐', gender: '여성', age: 20, cardPreset: 'A' };
}

export function applyDemoProfile(profile: DemoProfile, update: DemoProfileUpdate): DemoProfile {
  // Only these sample choices can enter the memory model, even from an untyped caller.
  return {
    displayName: '체험 콩쥐',
    gender: update.gender === '여성' || update.gender === '남성' ? update.gender : profile.gender,
    age: DEMO_AGES.some(age => age === update.age) ? update.age as DemoProfile['age'] : profile.age,
    cardPreset: update.cardPreset === 'A' || update.cardPreset === 'B' ? update.cardPreset : profile.cardPreset,
  };
}
