/** Browser-only visual fixture; bundled by the local verifier, never a product route. */
import { useState } from 'react';
import { createRoot } from 'react-dom/client';
import lottie from 'lottie-web';
import { PotVisualization } from '../src/pages/LeakPotPage';
import { DEMO_BUDGET_CATEGORIES } from '../src/demo/demoBudgetPresentation';
export function PotFixture() {
  const [count, setCount] = useState(1);
  const [withoutFirst, setWithoutFirst] = useState(false);
  const [spending, setSpending] = useState<number | null>(null);
  const [budget, setBudget] = useState(0);
  const [frame, setFrame] = useState<number | null>(null);
  const rows = spending === null
    ? DEMO_BUDGET_CATEGORIES.slice(0,count).map((name,i)=>({id:i+1,name,spending:300000,threshold:0,initialBudget:0})).filter((_,i)=>!withoutFirst || i!==0)
    : [{id:1,name:spending===500000?'주거 / 통신':'카페',spending,threshold:budget,initialBudget:spending}];
  function freeze(next: number) {
    // Existing installed API controls animation time only, not product state.
    if (!('goToAndStop' in lottie) || typeof lottie.goToAndStop !== 'function') throw new Error('ANIMATION_FRAME_CONTROL_UNAVAILABLE');
    lottie.goToAndStop(next,true); setFrame(next);
  }
  return <main className="demo-pot-page pot-visual-fixture">
    <h1>항아리 시각화 합성 검사</h1>
    <nav aria-label="시각화 상태">{[0,1,3,5,12].map(n=><button key={n} onClick={()=>{setCount(n);setWithoutFirst(false);setSpending(null);}}>{n}개 누수</button>)}
      <button onClick={()=>setWithoutFirst(true)}>첫 항목 제거</button>
    </nav>
    <nav aria-label="지출 기준">{[500000,58000].map(n=><button key={n} onClick={()=>{setSpending(n);setBudget(n);setFrame(null);}}>지출 {n}원</button>)}</nav>
    {spending!==null&&<label>비교 한도<input aria-label="비교 한도" type="range" min={0} max={spending} step={spending/5} value={budget} onChange={e=>setBudget(Number(e.currentTarget.value))}/><output>{budget}</output></label>}
    <nav aria-label="비교 애니메이션 프레임">{[5,12,19,20].map(n=><button key={n} onClick={()=>freeze(n)}>프레임 {n}</button>)}<button onClick={()=>{lottie.play();setFrame(null);}}>재생 계속</button></nav>
    <output aria-label="고정 프레임">{frame??'재생 중'}</output>
    <div className="demo-pot-stage"><PotVisualization leakingCategories={rows.filter(row=>row.spending>row.threshold)} totalLeak={rows.reduce((sum,row)=>sum+Math.max(row.spending-row.threshold,0),0)} formatter={new Intl.NumberFormat('ko-KR')}/></div>
  </main>;
}
createRoot(document.getElementById('root')!).render(<PotFixture/>);
