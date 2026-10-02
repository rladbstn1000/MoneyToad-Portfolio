import { createContext, useContext } from 'react';
import type { DemoProfile, DemoProfileUpdate } from './demoProfile';

export type DemoExperience = {
  profile: DemoProfile;
  updateProfile: (update: DemoProfileUpdate) => void;
};

export const DemoExperienceContext = createContext<DemoExperience | null>(null);

export function useDemoExperience(): DemoExperience {
  const value = useContext(DemoExperienceContext);
  if (value === null) throw new Error('DemoExperienceProvider is required');
  return value;
}
