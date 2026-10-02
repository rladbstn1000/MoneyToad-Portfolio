import { useEffect, useRef, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { updateBudget } from '../api/services/budgets';
import { monthlyBudgetQueryKeys, transactionQueryKeys } from '../api/queryKeys';
import { useAuthStore } from '../store/authStore';
import { adaptDemoBudgets } from './demoBudgetPresentation';

type Pending = { amount: number; revision: number };

/** Each mounted instance belongs to one visit/month. Query cache contains confirmed values only. */
export function useDemoBudgetChanges(year: number, month: number, generation: number) {
  const client = useQueryClient();
  const mutation = useMutation({ mutationFn: updateBudget, retry: false });
  const [pending, setPending] = useState<Record<number, Pending>>({});
  const [saving, setSaving] = useState<Set<number>>(new Set());
  const [errors, setErrors] = useState<Record<number, string>>({});
  const timers = useRef(new Map<number, number>());
  const revisions = useRef(new Map<number, number>());
  const inFlight = useRef(new Set<number>());
  const mounted = useRef(false);
  const sameVisit = () => {
    const state = useAuthStore.getState();
    return state.generation === generation && state.status === 'authenticated' && state.operation !== 'logout';
  };

  useEffect(() => {
    mounted.current = true;
    const ownedTimers = timers.current;
    const cancel = () => { ownedTimers.forEach(window.clearTimeout); ownedTimers.clear(); };
    const unsubscribe = useAuthStore.subscribe(state => {
      if (state.generation !== generation || state.status !== 'authenticated' || state.operation === 'logout') cancel();
    });
    return () => { mounted.current = false; cancel(); unsubscribe(); };
  }, [generation, year, month]);

  const change = (id: number, amount: number) => {
    if (!mounted.current || !sameVisit() || inFlight.current.has(id)
      || !Number.isSafeInteger(id) || id <= 0 || !Number.isSafeInteger(amount) || amount < 0) return;
    const queryKey = monthlyBudgetQueryKeys.monthly(year, month);
    const query = client.getQueryState(queryKey);
    const rows = adaptDemoBudgets(query?.data);
    if (query?.status !== 'success' || !rows?.some(row => row.id === id)) return;
    const revision = (revisions.current.get(id) ?? 0) + 1;
    revisions.current.set(id, revision);
    window.clearTimeout(timers.current.get(id));
    setErrors(previous => { const next = { ...previous }; delete next[id]; return next; });
    setPending(previous => ({ ...previous, [id]: { amount, revision } }));
    timers.current.set(id, window.setTimeout(() => {
      timers.current.delete(id);
      const current = client.getQueryState(queryKey);
      if (!mounted.current || !sameVisit() || revisions.current.get(id) !== revision
        || current?.status !== 'success'
        || !adaptDemoBudgets(current.data)?.some(row => row.id === id)) {
        if (mounted.current && sameVisit()) setPending(previous => {
          if (previous[id]?.revision !== revision) return previous;
          const next = { ...previous }; delete next[id]; return next;
        });
        return;
      }
      inFlight.current.add(id);
      setSaving(new Set(inFlight.current));
      void (async () => {
        let failed = false;
        try { await mutation.mutateAsync({ budgetId: id, budget: amount }); }
        catch { failed = true; }
        // An already-sent request may finish after navigating away. Revalidate this visit only.
        if (sameVisit()) await Promise.all([
          client.invalidateQueries({ queryKey: monthlyBudgetQueryKeys.all }),
          client.invalidateQueries({ queryKey: transactionQueryKeys.all }),
        ]).catch(() => { failed = true; });
        inFlight.current.delete(id);
        if (!mounted.current || !sameVisit() || revisions.current.get(id) !== revision) return;
        setSaving(new Set(inFlight.current));
        setPending(previous => {
          if (previous[id]?.revision !== revision) return previous;
          const next = { ...previous }; delete next[id]; return next;
        });
        if (failed) setErrors(previous => ({ ...previous, [id]: '한도를 저장하지 못했습니다. 조회된 값을 확인하고 다시 조정해 주세요.' }));
      })();
    }, 500));
  };
  return { pending, saving, errors, change };
}
