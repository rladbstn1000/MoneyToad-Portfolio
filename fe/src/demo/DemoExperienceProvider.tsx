import { useCallback, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { applyDemoProfile, createDemoProfile } from './demoProfile';
import type { DemoProfileUpdate } from './demoProfile';
import { isLocalDemo } from '../auth/authMode';
import { useLocalDemoStore } from './localDemoStore';
import { DemoExperienceContext } from './useDemoExperience';

export default function DemoExperienceProvider({ children }: { children: ReactNode }) {
  const generation = useLocalDemoStore(state => state.generation);
  const [profile, setProfile] = useState(createDemoProfile);
  const updateProfile = useCallback((update: DemoProfileUpdate) => {
    if (isLocalDemo && generation !== useLocalDemoStore.getState().generation) return;
    setProfile(current => applyDemoProfile(current, update));
  }, [generation]);
  const value = useMemo(() => ({ profile, updateProfile }), [profile, updateProfile]);
  return <DemoExperienceContext.Provider value={value}>{children}</DemoExperienceContext.Provider>;
}
