import { useCallback, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { applyDemoProfile, createDemoProfile } from './demoProfile';
import type { DemoProfileUpdate } from './demoProfile';
import { DemoExperienceContext } from './useDemoExperience';

export default function DemoExperienceProvider({ children }: { children: ReactNode }) {
  const [profile, setProfile] = useState(createDemoProfile);
  const updateProfile = useCallback((update: DemoProfileUpdate) => {
    setProfile(current => applyDemoProfile(current, update));
  }, []);
  const value = useMemo(() => ({ profile, updateProfile }), [profile, updateProfile]);
  return <DemoExperienceContext.Provider value={value}>{children}</DemoExperienceContext.Provider>;
}
