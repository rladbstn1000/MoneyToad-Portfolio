import { test } from './fixtures';
import { expect, type Page } from '@playwright/test';
import { writeFileSync } from 'node:fs';
import { clickDemoMenu } from './demoNavigation';
const origin = process.env.E2E_ORIGIN!;
let attempts = 0, apiAttempts = 0, externalAttempts = 0, errors = 0;
const viewports = [{ width:390,height:844 },{ width:768,height:1024 },{ width:1440,height:1000 },
  { width:1920,height:1080 },{ width:2560,height:1440 },{ width:3840,height:2160 },{ width:1920,height:900 }];
test.beforeEach(async ({context,page})=>{
  attempts=0;apiAttempts=0;externalAttempts=0;errors=0;
  page.on('pageerror',()=>{errors++;});
  context.on('page', extra => extra.on('pageerror',()=>{errors++;}));
  await context.route('**/*',async route=>{
    const request=route.request(),url=new URL(request.url());attempts++;
    const api=url.pathname.startsWith('/api/');
    const outside=url.origin!==origin && url.origin!==process.env.E2E_AWAY_ORIGIN;
    const away=url.origin===process.env.E2E_AWAY_ORIGIN && url.pathname==='/';
    const document=url.pathname==='/__visual__/pot.html'||/^\/(?:|chart|mypage|userInfo|toadAdvice|pot(?:\/(?:\d+|invalid))?)$/.test(url.pathname);
    const asset=/\.(?:js|css|webp|png|svg|ico|json)$/.test(url.pathname);
    if(api)apiAttempts++;
    if(outside)externalAttempts++;
    if(api||outside||request.method()!=='GET'||!(away||document||asset)){ if(!api&&!outside)apiAttempts++;await route.abort();return; }
    await route.continue();
  });
  await context.routeWebSocket(/.*/,socket=>{externalAttempts++;socket.close();});
});
test.afterEach(async({page},info)=>{
  expect(page.isClosed()).toBe(false);
  writeFileSync(`${process.env.E2E_ARTIFACTS}/network-${info.testId.replace(/[^a-z0-9]/gi,'')}.json`,JSON.stringify({staticRequests:attempts,apiAttempts,externalAttempts,pageErrors:errors}));
  expect(apiAttempts).toBe(0);expect(externalAttempts).toBe(0);expect(errors).toBe(0);
});
async function noOverflow(page:Page){expect(await page.evaluate(()=>Math.max(document.body.scrollWidth,document.documentElement.scrollWidth)-innerWidth)).toBeLessThanOrEqual(1);}
async function potFixedPositions(page:Page){
 return page.evaluate(()=>Object.fromEntries(['.panel-title','.summary','.kongjwi','.toad','#pot-body image'].map(selector=>{
  const r=document.querySelector(selector)!.getBoundingClientRect();return[selector,[r.x,r.y,r.width,r.height].map(n=>Math.round(n*100)/100)];
 })));
}
async function potGroundPositions(page:Page){
 return page.evaluate(()=>Object.fromEntries(['.kongjwi','.toad','#pot-body image'].map(selector=>{
  const r=document.querySelector(selector)!.getBoundingClientRect();
  return [selector, {centerX:Math.round((r.left+r.width/2)*100)/100,bottom:Math.round(r.bottom*100)/100}];
 })));
}
async function potViewportGeometry(page:Page){
 return page.evaluate(()=>{
  const rect=(el:Element)=>{const r=el.getBoundingClientRect();return{x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom};};
  const containers=['html','body','#root','.demo-pot-page','.main-container','.demo-pot-stage'];
  const figures=['.app-header','.month-navigation','.kongjwi','.toad','.pot-svg-container','#pot-body image','.control-panel','.panel-title','.sliders-container','.summary'];
  const puddles=[...document.querySelectorAll<SVGEllipseElement>('#puddle-group ellipse')].map(e=>{
   const b=e.getBBox(),m=e.getScreenCTM()!;
   // The declared distortion filter can paint the enclosing120%region.
   const points=[[b.x-b.width*.1,b.y-b.height*.1],[b.x+b.width*1.1,b.y-b.height*.1],
    [b.x-b.width*.1,b.y+b.height*1.1],[b.x+b.width*1.1,b.y+b.height*1.1]].map(([x,y])=>new DOMPoint(x,y).matrixTransform(m));
   return {left:Math.min(...points.map(p=>p.x)),right:Math.max(...points.map(p=>p.x)),
    top:Math.min(...points.map(p=>p.y)),bottom:Math.max(...points.map(p=>p.y))};
  });
  return {viewport:{width:innerWidth,height:innerHeight,dpr:devicePixelRatio},puddles,
   extents:containers.map(selector=>{const e=document.querySelector(selector)!;return{selector,rect:rect(e),clientWidth:e.clientWidth,clientHeight:e.clientHeight,scrollWidth:e.scrollWidth,scrollHeight:e.scrollHeight,scrollTop:e.scrollTop,overflowY:getComputedStyle(e).overflowY};}),
   figures:figures.map(selector=>({selector,...rect(document.querySelector(selector)!)})),
   // Step30 checks the local675-unit frame, not its intentionally taller700-unit
   // transparent SVG. Actual painted water is separately intersected with clips.
   // For raster illustrations the allocated image box contains all painted alpha;
   // water is separately measured at fixed real animation frames below.
   listOverflow:getComputedStyle(document.querySelector('.sliders-container')!).overflowY};
 });
}
function assertPotViewport(value:Awaited<ReturnType<typeof potViewportGeometry>>){
 for(const e of value.extents){expect(e.scrollWidth-e.clientWidth,e.selector).toBeLessThanOrEqual(1);expect(e.scrollHeight-e.clientHeight,e.selector).toBeLessThanOrEqual(1);expect(e.scrollTop,e.selector).toBe(0);}
 for(const r of value.figures){expect(r.x,r.selector).toBeGreaterThanOrEqual(-1);expect(r.y,r.selector).toBeGreaterThanOrEqual(-1);expect(r.right,r.selector).toBeLessThanOrEqual(value.viewport.width+1);expect(r.bottom,r.selector).toBeLessThanOrEqual(value.viewport.height+1);expect(r.width,r.selector).toBeGreaterThan(10);expect(r.height,r.selector).toBeGreaterThan(10);}
 expect(value.figures.find(x=>x.selector==='.sliders-container')!.height).toBeGreaterThanOrEqual(100);
 expect(value.listOverflow).toBe('auto');
 for(const r of value.puddles){expect(r.left).toBeGreaterThanOrEqual(-1);expect(r.right).toBeLessThanOrEqual(value.viewport.width+1);expect(r.top).toBeGreaterThanOrEqual(-1);expect(r.bottom).toBeLessThanOrEqual(value.viewport.height+1);}

}

async function potTopReadability(page:Page){
 return page.evaluate(()=>{
  const rect=(e:Element)=>{const r=e.getBoundingClientRect();return{x:r.x,y:r.y,width:r.width,height:r.height,right:r.right,bottom:r.bottom};};
  const notice=document.querySelector('.local-pot-page .demo-pot-notice')!;
  const css=getComputedStyle(notice);
  return {notice:{...rect(notice),font:parseFloat(css.fontSize),lineHeight:parseFloat(css.lineHeight),color:css.color,background:css.backgroundColor,border:css.borderTopWidth},
   header:rect(document.querySelector('.app-header')!),navigation:rect(document.querySelector('.month-navigation')!),main:rect(document.querySelector('.main-container')!),
   months:[...document.querySelectorAll('.month-button')].map(e=>({active:e.classList.contains('active'),button:rect(e),
    image:rect(e.querySelector('.month-img')!),label:rect(e.querySelector('.month-badge')!),font:parseFloat(getComputedStyle(e.querySelector('.month-badge')!).fontSize)}))};
 });
}
function assertPotTop(value:Awaited<ReturnType<typeof potTopReadability>>,width:number){
 const {notice,header,navigation,months,main}=value;
 // Step30 gives the three top rows explicit breathing room rather than fixing
 // the old main start coordinate. Compact screens preserve usable remainder.
 expect(notice.y-header.bottom).toBeGreaterThanOrEqual(width>900?20:6);
 expect(Math.min(...months.map(m=>m.label.y))-notice.bottom).toBeGreaterThanOrEqual(width>900?16:8);
 expect(main.y-Math.max(...months.map(m=>m.image.bottom))).toBeGreaterThanOrEqual(width>900?24:8);
 expect(notice.y).toBeGreaterThanOrEqual(header.bottom-1);
 expect(notice.bottom).toBeLessThanOrEqual(navigation.y+1);
 expect(notice.font).toBeGreaterThanOrEqual(width>900?16:14);
 expect(notice.font).toBeLessThanOrEqual(18);expect(notice.lineHeight).toBeGreaterThan(notice.font);
 expect(notice.background).not.toBe('rgba(0, 0, 0, 0)');expect(parseFloat(notice.border)).toBe(1);
 expect(notice.color).not.toBe('rgb(0, 0, 0)');expect(months).toHaveLength(12);
 expect(new Set(months.map(m=>Math.round(m.button.y))).size).toBe(width>900?1:2);
 const inside=(a:{x:number;y:number;right:number;bottom:number},b:{x:number;y:number;right:number;bottom:number})=>a.x>=b.x-1&&a.right<=b.right+1&&a.y>=b.y-1&&a.bottom<=b.bottom+1;
 for(const m of months){
  expect(m.button.height).toBeGreaterThanOrEqual(44);expect(m.button.width).toBeGreaterThanOrEqual(44);
  expect(m.font).toBeGreaterThanOrEqual(width>900?16:13);
  expect(inside(m.image,m.button)).toBe(true);
  expect(m.label.bottom).toBeLessThanOrEqual(m.image.y+1);
  expect(m.label.x).toBeGreaterThanOrEqual(m.button.x-1);expect(m.label.right).toBeLessThanOrEqual(m.button.right+1);
  expect(Math.abs(m.image.width-m.image.height)).toBeLessThan(1);
  expect(m.image.height).toBeGreaterThanOrEqual(width>900?(m.active?82:59):(m.active?48:40));
 }
 for(let i=0;i<months.length;i++)for(let j=i+1;j<months.length;j++){
  const a=months[i].button,b=months[j].button;
  expect(a.right<=b.x+1||b.right<=a.x+1||a.bottom<=b.y+1||b.bottom<=a.y+1).toBe(true);
 }
}

async function openProfile(page:Page){
 await page.getByRole('button',{name:'문 열고 들어가기',exact:true}).click();
 await page.getByRole('button',{name:'콩쥐 만나기',exact:true}).click();
 await page.getByRole('button',{name:'샘플 정보 보기',exact:true}).click();
 return page.getByRole('dialog',{name:'콩쥐의 정보 수정하기',exact:true});
}
async function selectMonth(page:Page){
 const nav=page.getByRole('navigation',{name:'월별 상세 보기',exact:true});
 await nav.getByRole('button',{name:/2026년 10월 상세 보기$/}).click();
 await expect(page.locator('.jp-total')).toContainText('908,000원');
}
async function resetVisit(page:Page){
 const toggle=page.getByRole('button',{name:'체험 메뉴',exact:true});
 const reset=page.getByRole('button',{name:'처음부터 다시하기',exact:true});
 if(!await reset.isVisible() && await toggle.isVisible())await toggle.click();
 await reset.click();
}
test('local complete journey and memory reset',async({page,context})=>{
 await page.setViewportSize(viewports[0]);await page.goto('/');
 await expect(page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true})).toBeEnabled();
 await expect(page.getByText('서버 다시 확인',{exact:true})).toHaveCount(0);
 const loadingTimings=await page.evaluate(()=>{
  const nav=performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming;
  const resources=performance.getEntriesByType('resource') as PerformanceResourceTiming[];
  const summarize=(kind:string)=>{const values=resources.filter(row=>row.initiatorType===kind);return {count:values.length,maxDurationMs:Math.max(0,...values.map(row=>row.duration))};};
  return {documentResponseMs:nav.responseEnd-nav.startTime,domContentLoadedMs:nav.domContentLoadedEventEnd,
   loadEventMs:nav.loadEventEnd,scripts:summarize('script'),images:summarize('img')};
 });
 await page.getByRole('button',{name:'Go to page 4',exact:true}).click();await expect(page.locator('.dk-counter .cur')).toHaveText('04');
 const started=performance.now();await page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true}).click();
 await expect(page).toHaveURL(/\/pot\/10$/);
 const slider=page.getByRole('slider',{name:'카페 한도',exact:true});await expect(slider).toHaveValue('40000');
 const startToControlsMs=performance.now()-started;
 await expect(page.getByText('총 18,000냥이 새고 있소!',{exact:true})).toBeVisible();
 await expect(page.locator('.crack')).toHaveCount(1);await expect(page.locator('#waters foreignObject')).toHaveCount(1);
 const editStarted=performance.now();await slider.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowRight');await expect(slider).toHaveValue(String(40000+i*5000));}
 await expect(slider).toHaveValue('60000');await expect(page.getByText('완벽하오! 새는 돈이 없소!',{exact:true})).toBeVisible();
 const budgetRecalculationMs=performance.now()-editStarted;
 await clickDemoMenu(page,'콩쥐의 씀씀이');await selectMonth(page);await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');
 await page.getByRole('button',{name:'닫기',exact:true}).click();await clickDemoMenu(page,'콩쥐의 장독대');
 await slider.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowLeft');await expect(slider).toHaveValue(String(60000-i*5000));}
 await expect(slider).toHaveValue('40000');await expect(page.getByText('총 18,000냥이 새고 있소!',{exact:true})).toBeVisible();
 await clickDemoMenu(page,'콩쥐의 씀씀이');await selectMonth(page);
 const practice=page.getByRole('row').filter({hasText:'합성 장보기(분류 연습)'});
 await expect(practice.getByRole('combobox')).toContainText('카페');
 const categoryStarted=performance.now();await practice.getByRole('combobox').click();await page.getByRole('option',{name:'마트 / 편의점',exact:true}).click();
 await expect(practice.getByRole('combobox')).toContainText('마트 / 편의점');await expect(page.locator('.jp-leak')).toHaveText('누수 금액: 0원');await expect(page.locator('.jp-total')).toContainText('908,000원');
 const categoryRecalculationMs=performance.now()-categoryStarted;
 await page.getByRole('button',{name:'닫기',exact:true}).click();await clickDemoMenu(page,'콩쥐의 장독대');
 await expect(page.getByText('완벽하오! 새는 돈이 없소!',{exact:true})).toBeVisible();
 await clickDemoMenu(page,'두꺼비의 조언');await expect(page.getByText('축하하오! 과소비 항목이 없소!',{exact:true})).toBeVisible();
 await page.locator('.ta-month-button').filter({hasText:/(?:^|\s)5월$/}).click();
 const adviceCard=page.getByRole('button',{name:/문화생활/});await adviceCard.click();
 const modal=page.getByRole('dialog',{name:'문화생활',exact:true});await expect(modal).toContainText('120,000냥');await expect(modal).toContainText('180,000냥');await expect(modal).toContainText('60,000냥');
 await page.keyboard.press('Escape');await expect(modal).toHaveCount(0);await expect(adviceCard).toBeFocused();
 await clickDemoMenu(page,'콩쥐의 곳간');let profile=await openProfile(page);
 await profile.getByRole('button',{name:'남성',exact:true}).click();await profile.getByRole('combobox',{name:'샘플 나이'}).selectOption('30');await profile.getByRole('button',{name:/샘플 카드 B/}).click();
 await profile.getByRole('button',{name:'변경 취소',exact:true}).click();await expect(profile.getByRole('button',{name:'여성',exact:true})).toHaveAttribute('aria-pressed','true');
 await profile.getByRole('button',{name:'남성',exact:true}).click();await profile.getByRole('combobox',{name:'샘플 나이'}).selectOption('30');await profile.getByRole('button',{name:/샘플 카드 B/}).click();await profile.getByRole('button',{name:'샘플 설정 저장',exact:true}).click();
 await profile.getByRole('button',{name:'정보 창 닫기',exact:true}).click();
 await page.getByRole('link',{name:'정보 입력 과정 체험',exact:true}).click();
 await page.getByRole('button',{name:'샘플 설정 시작',exact:true}).click();await page.getByRole('button',{name:'남성',exact:true}).click();await page.getByRole('button',{name:'다음',exact:true}).click();await page.getByRole('button',{name:'30세',exact:true}).click();await page.getByRole('button',{name:'다음',exact:true}).click();await page.getByRole('button',{name:/샘플 카드 B/}).click();await page.getByRole('button',{name:'샘플 설정 완료',exact:true}).click();
 await expect(page).toHaveURL(/\/pot\/10$/);await expect(page.getByText('완벽하오! 새는 돈이 없소!',{exact:true})).toBeVisible();
 await clickDemoMenu(page,'콩쥐의 씀씀이');await page.goBack();await expect(page).toHaveURL(/\/pot\/10$/);await expect(page.getByText('완벽하오! 새는 돈이 없소!',{exact:true})).toBeVisible();await page.goForward();await selectMonth(page);await expect(practice.getByRole('combobox')).toContainText('마트 / 편의점');await page.getByRole('button',{name:'닫기',exact:true}).click();
 await clickDemoMenu(page,'콩쥐의 곳간');profile=await openProfile(page);await expect(profile.getByRole('combobox',{name:'샘플 나이'})).toHaveValue('30');await profile.getByRole('button',{name:'정보 창 닫기',exact:true}).click();
 await clickDemoMenu(page,'콩쥐의 장독대');await slider.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowRight');await expect(slider).toHaveValue(String(40000+i*5000));}await expect(slider).toHaveValue('60000');
 const tab=await context.newPage();await tab.goto('/pot/10');await expect(tab.getByRole('slider',{name:'카페 한도',exact:true})).toHaveValue('40000');await tab.close();await page.bringToFront();await expect(slider).toHaveValue('60000');
 await page.reload();await expect(slider).toHaveValue('40000');await expect(page.getByText('총 18,000냥이 새고 있소!',{exact:true})).toBeVisible();
 await clickDemoMenu(page,'콩쥐의 씀씀이');await selectMonth(page);await expect(practice.getByRole('combobox')).toContainText('카페');await page.getByRole('button',{name:'닫기',exact:true}).click();
 await clickDemoMenu(page,'콩쥐의 곳간');profile=await openProfile(page);await expect(profile.getByRole('combobox',{name:'샘플 나이'})).toHaveValue('20');await profile.getByRole('button',{name:'정보 창 닫기',exact:true}).click();
 await clickDemoMenu(page,'콩쥐의 장독대');await slider.focus();await page.keyboard.press('ArrowRight');await expect(slider).toHaveValue('45000');await resetVisit(page);await expect(slider).toHaveValue('40000');
 await clickDemoMenu(page,'마당');await page.getByRole('button',{name:'체험 이어가기',exact:true}).click();await expect(slider).toHaveValue('40000');
 await clickDemoMenu(page,'체험 종료');await expect(page).toHaveURL('/');await expect(page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true})).toBeEnabled();
 expect(await context.cookies()).toEqual([]);expect(await page.evaluate(()=>({local:localStorage.length,session:sessionStorage.length}))).toEqual({local:0,session:0});
 await noOverflow(page);
 writeFileSync(`${process.env.E2E_ARTIFACTS}/journey.json`,JSON.stringify({status:'PASS',loadingTimings,startToControlsMs,budgetRecalculationMs,categoryRecalculationMs,fullReloadResets:true,internalHistoryPreserves:true,newTabIndependent:true,resetAndEnd:true,staticOnly:true}));
});
for(const viewport of viewports){
 test(`local geometry ${viewport.width}x${viewport.height}`,async({page})=>{
  await page.setViewportSize(viewport);await page.goto('/');
  const landing = [];
  for (let sceneIndex=0;sceneIndex<4;sceneIndex++) {
   if(sceneIndex>0)await page.getByRole('button',{name:`Go to page ${sceneIndex+1}`,exact:true}).click();
   await expect(page.locator('.dk-counter .cur')).toHaveText(String(sceneIndex+1).padStart(2,'0'));
   await expect.poll(()=>page.locator('.dk-track').evaluate(el=>el.getAnimations().length)).toBe(0);
   const scene=page.locator('.dk-page').nth(sceneIndex);
   const picture=scene.locator('.dk-story-image');
   await expect.poll(()=>picture.evaluate(img=>img instanceof HTMLImageElement && img.complete && img.naturalWidth>0)).toBe(true);
   const measure=()=>scene.evaluate(el=>{
    const img=el.querySelector('img.dk-story-image') as HTMLImageElement;
    const a=img.getBoundingClientRect(),b=el.querySelector('.dk-content')!.getBoundingClientRect();
    const inside=(x:DOMRect,y:DOMRect)=>x.left>=y.left-1&&x.right<=y.right+1&&x.top>=y.top-1&&x.bottom<=y.bottom+1;
    return {fit:getComputedStyle(img).objectFit,naturalWidth:img.naturalWidth,naturalHeight:img.naturalHeight,
     sourceRatioPreserved:Math.abs(a.width/a.height-img.naturalWidth/img.naturalHeight)<.005,
     imageInsideViewport:a.left>=-1&&a.right<=innerWidth+1&&a.top>=-1&&a.bottom<=innerHeight+1,
     textInsideImage:inside(b,a),textInsideViewport:b.left>=-1&&b.right<=innerWidth+1&&b.top>=-1&&b.bottom<=innerHeight+1,
     contentNotClipped:[...el.querySelectorAll('.dk-title,.dk-desc')].every(text=>inside(text.getBoundingClientRect(),a)),
     width:innerWidth,height:innerHeight,dpr:devicePixelRatio};
   });
   await expect.poll(async()=>{const m=await measure();return m.imageInsideViewport&&m.textInsideImage&&m.textInsideViewport&&m.contentNotClipped;}).toBe(true);
   const measured=await measure();expect(measured.fit).toBe('contain');expect(measured.sourceRatioPreserved).toBe(true);
   landing.push(measured);await noOverflow(page);
   await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-landing-${sceneIndex+1}.png`});
  }
  await page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true}).click();
  await expect(page.getByRole('slider',{name:'카페 한도',exact:true})).toHaveValue('40000');
  const potGeometry=()=>page.locator('.pot-visualization').evaluate(el=>{
   const rect=(selector:string)=>el.querySelector(selector)!.getBoundingClientRect();
   const a=rect('.kongjwi'),b=rect('.toad'),pot=rect('.pot-svg-container');
   const separate=(x:DOMRect,y:DOMRect)=>x.right<=y.left+1||y.right<=x.left+1||x.bottom<=y.top+1||y.bottom<=x.top+1;
   const image=rect('#pot-body image'),panel=document.querySelector('.demo-paper-decoration')!.getBoundingClientRect();
   return {charactersSeparate:separate(a,b),kongjwiSeparate:separate(a,pot),toadSeparate:separate(b,pot),
    potHeight:image.height,kongjwiToPot:a.height/image.height,toadToPot:b.height/image.height,paperToPot:panel.height/image.height};
  });
  expect(await potGeometry()).toMatchObject({charactersSeparate:true,kongjwiSeparate:true,toadSeparate:true});
  const leakingProportions=await potGeometry();
  // Step28 replaces original pixel-ratio minimums with the user's stronger
  // one-viewport contract. No data/auth/aggregate expectation is removed.
  const viewportGeometry=await potViewportGeometry(page);
  assertPotViewport(viewportGeometry);
  const topReadability=await potTopReadability(page);assertPotTop(topReadability,viewport.width);
  const monthLayout=topReadability.months.map(m=>m.button);
  const may=page.locator('.month-button').filter({hasText:/^5월$/});await may.hover();await may.focus();
  expect((await potTopReadability(page)).months.map(m=>m.button)).toEqual(monthLayout);
  await may.click();await expect(page.locator('.panel-title')).toHaveText('2026년 5월 지출을 다스리시오');
  await expect.poll(()=>page.locator('.month-button.active .month-img').evaluate(e=>e.getAnimations().length)).toBe(0);
  assertPotTop(await potTopReadability(page),viewport.width);
  expect((await potTopReadability(page)).months.map(m=>m.button)).toEqual(monthLayout);
  await page.locator('.month-button').filter({hasText:/^10월$/}).click();await expect(page.locator('.panel-title')).toHaveText('2026년 10월 지출을 다스리시오');
  const fixedBefore=await potFixedPositions(page);
  await page.locator('.panel-title').hover();await page.mouse.wheel(0,400);
  await page.keyboard.press('PageDown');
  expect(await potFixedPositions(page)).toEqual(fixedBefore);
  const groundBefore=await potGroundPositions(page);
  const cafe=page.getByRole('slider',{name:'카페 한도',exact:true});
  await cafe.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowRight');await expect(cafe).toHaveValue(String(40000+i*5000));}
  await expect(page.locator('.crack')).toHaveCount(0);
  const normalProportions=await potGeometry();
  expect(await potGroundPositions(page)).toEqual(groundBefore);
  expect(await potGeometry()).toMatchObject({charactersSeparate:true,kongjwiSeparate:true,toadSeparate:true});
  await cafe.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowLeft');await expect(cafe).toHaveValue(String(60000-i*5000));}
  // Maximum actual editable-category leaks: native Home keys, no state injection.
  for(const slider of await page.getByRole('slider').all())if(await slider.isEnabled()){await slider.focus();await page.keyboard.press('Home');}
  await expect(page.locator('.crack')).toHaveCount(6);
  expect(await potGroundPositions(page)).toEqual(groundBefore);
  assertPotViewport(await potViewportGeometry(page));
  for(let i=0;i<6;i++){
   await expect(page.locator('.pot-leak-stream svg').nth(i)).toBeVisible();
   const water=await measureWater(page,i);expect(water.waterWithinStage).toBe(true);expect(water.paintInsideViewportAndClips).toBe(true);expect(water.paintSeparateFromControlsAndCharacters).toBe(true);
  }
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-pot-max.png`});
  await resetVisit(page);await expect(cafe).toHaveValue('40000');
  const menu=page.getByRole('button',{name:'체험 메뉴',exact:true});
  const notice=page.locator('.demo-header .local-demo-notice');
  if(!await notice.isVisible())await menu.click();
  await notice.locator('summary').click();
  await expect(notice).toContainText('변경 내용은 이 탭에서만 유지되며 새로고침하면 초기화됩니다.');
  expect((await notice.locator('summary').boundingBox())!.height).toBeGreaterThanOrEqual(44);await noOverflow(page);
  await notice.locator('summary').click();if(await menu.isVisible())await page.keyboard.press('Escape');
  await page.locator('.control-panel').scrollIntoViewIfNeeded();
  const paper=await page.locator('.control-panel').evaluate(el=>{
   const rect=(e:Element)=>{const r=e.getBoundingClientRect();return {left:r.left,right:r.right,top:r.top,bottom:r.bottom,width:r.width,height:r.height};};
   const art=rect(el.querySelector('.demo-paper-decoration')!),safe=rect(el.querySelector('.demo-paper-safe-area')!);
   const title=rect(el.querySelector('.panel-title')!),list=rect(el.querySelector('.sliders-container')!),sum=rect(el.querySelector('.summary')!);
   const controls=[...el.querySelectorAll('input[type=range]')].map(rect);
   const text=[...el.querySelectorAll('.slider-label,.slider-legend')].map(rect);
   return {art,safe,title,list,sum,controls,text,titleFont:parseFloat(getComputedStyle(el.querySelector('.panel-title')!).fontSize),
    listOverflow:getComputedStyle(el.querySelector('.sliders-container')!).overflowY,
    safeInsidePaper:safe.left>=art.left+art.width*.13&&safe.right<=art.left+art.width*.87,
    titleBeforeList:title.bottom<=list.top+1,listBeforeSum:list.bottom<=sum.top+1,
    rowsWithinPaper:text.every(x=>x.left>=safe.left-1&&x.right<=safe.right+1),
    thumbsWithinPaper:controls.every(x=>x.left>=safe.left+8&&x.right<=safe.right-8)};
  });
  expect(paper.safeInsidePaper).toBe(true);
  expect(paper.titleBeforeList).toBe(true);expect(paper.listBeforeSum).toBe(true);expect(paper.rowsWithinPaper).toBe(true);expect(paper.thumbsWithinPaper).toBe(true);
  expect(paper.titleFont).toBeGreaterThanOrEqual(20);expect(paper.listOverflow).toBe('auto');
  for(const c of paper.controls)expect(c.height).toBeGreaterThanOrEqual(44);
  const list=page.locator('.sliders-container');const listBefore=await potFixedPositions(page);
  await list.hover();await page.mouse.wheel(0,5000);
  await expect.poll(()=>list.evaluate(el=>el.scrollTop+el.clientHeight>=el.scrollHeight-2)).toBe(true);
  const last=page.locator('.sliders-container .slider-item').last();await expect(last).toBeInViewport();
  expect(await potFixedPositions(page)).toEqual(listBefore);
  await noOverflow(page);assertPotViewport(await potViewportGeometry(page));
  await page.getByRole('slider',{name:'카페 한도',exact:true}).scrollIntoViewIfNeeded();
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-pot.png`});
  await clickDemoMenu(page,'콩쥐의 씀씀이');await expect(page.locator('.jp-linechart-wrap')).toBeVisible();
  const chart=await page.evaluate(()=>{
   const rect=(selector:string)=>{const b=document.querySelector(selector)!.getBoundingClientRect();return {left:b.left,right:b.right,top:b.top,bottom:b.bottom,width:b.width,height:b.height};};
   const title=rect('.jp-page-title-section'),stage=rect('.jp-stage'),plot=rect('.jp-linechart-wrap');
   return {title,stage,plot,separate:title.bottom<=stage.top+1&&title.bottom<=plot.top+1,plotInsideStage:plot.left>=stage.left-1&&plot.right<=stage.right+1&&plot.top>=stage.top-1&&plot.bottom<=stage.bottom+1};
  });
  expect(chart.separate).toBe(true);expect(chart.plotInsideStage).toBe(true);expect(chart.plot.height).toBeGreaterThanOrEqual(260);await noOverflow(page);
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-chart.png`});
  await clickDemoMenu(page,'두꺼비의 조언');await expect(page.locator('[data-demo-page="advice"]')).toBeVisible();
  const detailButton=page.getByRole('button',{name:'카테고리별 소비 조언 보기 ↓',exact:true});
  await expect(detailButton).toBeVisible();
  const actionBox=(await detailButton.boundingBox())!;
  expect(actionBox.height).toBeGreaterThanOrEqual(52);
  const sections=await page.evaluate(()=>{const hero=document.querySelector('.page-hero')!.getBoundingClientRect(),details=document.querySelector('#advice-details')!,rect=details.getBoundingClientRect();return {heroHeight:hero.height,detailsTop:rect.top,detailsMounted:getComputedStyle(details).display!=='none',cards:[...details.querySelectorAll('.advice-card')].map(el=>el.getBoundingClientRect().top)};});
  expect(sections.heroHeight).toBeGreaterThanOrEqual(viewport.height);expect(sections.detailsTop).toBeGreaterThanOrEqual(viewport.height);expect(sections.detailsMounted).toBe(true);expect(sections.cards.length).toBeGreaterThan(0);for(const top of sections.cards)expect(top).toBeGreaterThanOrEqual(viewport.height);
  if(viewport.width>=1440){expect(actionBox.y).toBeGreaterThanOrEqual(0);expect(actionBox.y+actionBox.height).toBeLessThanOrEqual(viewport.height);}
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-advice.png`});
  await detailButton.click();
  const detailHeading=page.locator('#advice-details-title');await expect(detailHeading).toBeFocused();
  await expect.poll(()=>detailHeading.evaluate(el=>{const r=el.getBoundingClientRect();return r.top>=110&&r.top<=114&&r.bottom<=innerHeight;})).toBe(true);
  await noOverflow(page);
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-${viewport.width}-${viewport.height}-advice-detail.png`});
  const adviceCard=page.getByRole('button',{name:/카페/}).filter({has:page.locator('.category-title')});
  await adviceCard.click();await expect(page.getByRole('dialog',{name:'카페',exact:true})).toBeVisible();
  await page.keyboard.press('Escape');await expect(page.getByRole('dialog')).toHaveCount(0);await expect(adviceCard).toBeFocused();
  if(viewport.width<=1440){
   await clickDemoMenu(page,'콩쥐의 곳간');const profile=await openProfile(page);await noOverflow(page);await profile.getByRole('button',{name:'정보 창 닫기',exact:true}).click();
   await page.getByRole('link',{name:'정보 입력 과정 체험',exact:true}).click();await expect(page.getByRole('button',{name:'샘플 설정 시작',exact:true})).toBeVisible();await noOverflow(page);
  }
  writeFileSync(`${process.env.E2E_ARTIFACTS}/layout-${viewport.width}-${viewport.height}.json`,JSON.stringify({status:'PASS',viewport,landing,paper,chart,leakingProportions,normalProportions,viewportGeometry,topReadability,adviceSections:sections}));
 });
}
test('local direct route external-history return and no asset blocking',async({page})=>{
 await page.addInitScript(()=>{window.addEventListener('pageshow',event=>{document.documentElement.dataset.observedPageShow=event.persisted?'persisted':'new';});});
 await page.goto('/pot/10');const slider=page.getByRole('slider',{name:'카페 한도',exact:true});await expect(slider).toHaveValue('40000');
 await slider.focus();await page.keyboard.press('ArrowRight');await expect(slider).toHaveValue('45000');
 await page.goto(process.env.E2E_AWAY_ORIGIN!);await page.goBack();await expect(slider).toHaveValue('40000');
 const persisted=await page.locator('html').getAttribute('data-observed-page-show');
 const restoredFromCache=persisted==='persisted';
 writeFileSync(`${process.env.E2E_ARTIFACTS}/history.json`,JSON.stringify({stateReset:true,actualBfcache:restoredFromCache?'OBSERVED_PASS':'NOT_OBSERVED',realRenavigation:!restoredFromCache}));
 await page.goto('/pot/invalid');await expect(page).toHaveURL(/\/pot\/10$/);
 let heldAssets=0;let release:()=>void=()=>{};const pending=new Promise<void>(resolve=>{release=resolve;});
 await page.route(/\/landing\/.*\.(?:webp|png)$/,async route=>{heldAssets++;await pending;await route.continue();});
 try{
  await page.goto('/',{waitUntil:'domcontentloaded'});await expect(page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true})).toBeEnabled();
  await expect.poll(()=>heldAssets).toBeGreaterThan(0);
  await expect(page.locator('.loading-overlay')).toHaveCount(0);
  await page.getByRole('button',{name:'샘플 데이터로 체험하기',exact:true}).click();await expect(slider).toHaveValue('40000');
 }finally{release();}
});


test('local fixed crack anchors at one three five and maximum categories',async({page})=>{
 await page.setViewportSize({width:1920,height:1080});await page.goto('/__visual__/pot.html');
 const summary=[];
 for(const count of [0,1,3,5,12]){
  await page.getByRole('button',{name:`${count}개 누수`,exact:true}).click();
  await expect(page.locator('.crack')).toHaveCount(count);await expect(page.locator('.pot-leak-stream')).toHaveCount(count);
  if(count)await expect(page.locator('.pot-leak-stream svg').first()).toBeVisible();
  const points=await page.locator('.crack').evaluateAll(elements=>elements.map(el=>({category:el.getAttribute('data-category'),x:el.getAttribute('data-origin-x'),y:el.getAttribute('data-origin-y'),
   left:Number(el.getAttribute('x')),top:Number(el.getAttribute('y')),width:Number(el.getAttribute('width')),height:Number(el.getAttribute('height'))})));
  for(let i=0;i<points.length;i++){
   const a=points[i];
   const stream=page.locator('.pot-leak-stream').filter({visible:true}).nth(i);
   await expect(stream).toHaveAttribute('data-category',a.category!);await expect(stream).toHaveAttribute('x',a.x!);await expect(stream).toHaveAttribute('y',a.y!);
   // Decorative rays may overlap at the cap; actual dark hole cores must remain distinct.
   for(const b of points.slice(i+1)){const dx=Math.abs(Number(a.x)-Number(b.x)),dy=Math.abs(Number(a.y)-Number(b.y));expect(dx>(a.width+b.width)*.18||dy>(a.height+b.height)*.20).toBe(true);}
  }
  await noOverflow(page);await page.screenshot({fullPage:true,path:`${process.env.E2E_ARTIFACTS}/after-pot-${count}-anchors.png`});
  summary.push({count,originAgreement:true,darkCoresDistinct:true,decorativeRectangleOverlapPermitted:true});
  if(count===12){
   for(const frame of [5,12,19,20]){
    await page.getByRole('button',{name:`프레임 ${frame}`,exact:true}).click();
    for(let i=0;i<count;i++){const measured=await measureWater(page,i);expect(measured.waterWithinStage).toBe(true);expect(measured.paintedPixels).toBeGreaterThan(0);}
   }
   await page.getByRole('button',{name:'첫 항목 제거',exact:true}).click();await expect(page.locator('.crack')).toHaveCount(11);
   const remaining=await page.locator('.crack').evaluateAll(elements=>elements.map(el=>({category:el.getAttribute('data-category'),x:el.getAttribute('data-origin-x'),y:el.getAttribute('data-origin-y')})));
   expect(remaining).toEqual(points.slice(1).map(({category,x,y})=>({category,x,y})));
  }
 }
 writeFileSync(`${process.env.E2E_ARTIFACTS}/anchors.json`,JSON.stringify({status:'PASS',summary,removalPreservesRemainingAnchors:true,scope:'isolated rendering fixture; unchanged product scenario'}));
});

test('local advice keyboard wheel reduced motion and empty result navigation',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.emulateMedia({reducedMotion:'reduce'});await page.goto('/pot/10');
 const cafe=page.getByRole('slider',{name:'카페 한도',exact:true});await expect(cafe).toHaveValue('40000');
 await cafe.focus();for(let i=1;i<=4;i++){await page.keyboard.press('ArrowRight');await expect(cafe).toHaveValue(String(40000+i*5000));}
 await clickDemoMenu(page,'두꺼비의 조언');
 const button=page.getByRole('button',{name:'이번 달 소비 결과 보기 ↓',exact:true});await expect(button).toBeVisible();
 await button.focus();await page.keyboard.press('Enter');await expect(page.locator('#advice-details-title')).toBeFocused();await expect(page.locator('.ta-detail-empty')).toBeVisible();
 await expect(page.locator('.advice-card')).toHaveCount(0);
 expect(await page.locator('.snap-container').evaluate(el=>getComputedStyle(el).scrollBehavior)).toBe('auto');
 await page.locator('.ta-month-button').filter({hasText:/(?:^|\s)5월$/}).click();
 await page.getByRole('button',{name:'카테고리별 소비 조언 보기 ↓',exact:true}).scrollIntoViewIfNeeded();
 const region=page.locator('.snap-container');const before=await region.evaluate(el=>el.scrollTop);
 await page.locator('.ta-detail-navigation').hover();await page.mouse.wheel(0,32);await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBeGreaterThan(before);
 await expect(page.locator('#advice-details-title')).toBeInViewport();await noOverflow(page);
 writeFileSync(`${process.env.E2E_ARTIFACTS}/advice-navigation.json`,JSON.stringify({status:'PASS',keyboard:true,wheel:true,reducedMotion:true,emptyResult:true}));
});

async function measureWater(page:Page,index=0){return await page.locator('.pot-visualization').evaluate(async (el,index)=>{
    const crack=el.querySelectorAll('.crack')[index]!,water=el.querySelectorAll('.pot-leak-stream')[index]!,svg=water.querySelector('svg')!,stage=el.querySelector('.pot-svg')!.getBoundingClientRect();
    const hole=crack.getBoundingClientRect(),pot=el.querySelector('#pot-body image')!.getBoundingClientRect();
    // This asset paints solid rects through animated masks in defs. A path-only
    // DOM query sees no paint. Rasterize its real masked frame, then map alpha
    // bounds through the live SVG matrix (letterboxing/rotation/flip included).
    const view=svg.viewBox.baseVal,clone=svg.cloneNode(true) as SVGSVGElement;
    clone.setAttribute('width',String(view.width));clone.setAttribute('height',String(view.height));
    clone.style.width=`${view.width}px`;clone.style.height=`${view.height}px`;
    const picture=new Image();picture.src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(new XMLSerializer().serializeToString(clone));
    await picture.decode();
    const canvas=document.createElement('canvas');canvas.width=view.width;canvas.height=view.height;
    const context=canvas.getContext('2d')!;context.drawImage(picture,0,0);
    const pixels=context.getImageData(0,0,canvas.width,canvas.height).data;
    const matrix=svg.getScreenCTM()!;
    let left=Infinity,right=-Infinity,top=Infinity,bottom=-Infinity,paintedPixels=0;
    const halfX=(Math.abs(matrix.a)+Math.abs(matrix.c))/2,halfY=(Math.abs(matrix.b)+Math.abs(matrix.d))/2;
    // Transform painted pixels rather than the unrotated bounding rectangle:
    // its transparent corners would falsely count as spray outside the stage.
    for(let y=0;y<canvas.height;y++)for(let x=0;x<canvas.width;x++)if(pixels[(y*canvas.width+x)*4+3]>0){
     const px=matrix.a*(x+view.x+.5)+matrix.c*(y+view.y+.5)+matrix.e;
     const py=matrix.b*(x+view.x+.5)+matrix.d*(y+view.y+.5)+matrix.f;
     left=Math.min(left,px-halfX);right=Math.max(right,px+halfX);
     top=Math.min(top,py-halfY);bottom=Math.max(bottom,py+halfY);paintedPixels++;
    }
    // Check actual painted pixels against viewport and every rectangular CSS clip,
    // independently of transparent outer SVG framing.
    let clipLeft=0,clipTop=0,clipRight=innerWidth,clipBottom=innerHeight;
    for(let ancestor:Element|null=svg;ancestor;ancestor=ancestor.parentElement){
     const css=getComputedStyle(ancestor),r=ancestor.getBoundingClientRect();
     if(['hidden','clip','auto','scroll'].includes(css.overflowX)){clipLeft=Math.max(clipLeft,r.left);clipRight=Math.min(clipRight,r.right);}
     if(['hidden','clip','auto','scroll'].includes(css.overflowY)){clipTop=Math.max(clipTop,r.top);clipBottom=Math.min(clipBottom,r.bottom);}
    }
    const obstacles=[...document.querySelectorAll('.kongjwi,.toad,.control-panel')].map(e=>e.getBoundingClientRect());
    return {paint:{left,top,right,bottom},
     paintInsideViewportAndClips:left>=clipLeft-1&&right<=clipRight+1&&top>=clipTop-1&&bottom<=clipBottom+1,
     paintSeparateFromControlsAndCharacters:obstacles.every(r=>right<=r.left+1||left>=r.right-1||bottom<=r.top+1||top>=r.bottom-1),
     originX:crack.getAttribute('data-origin-x'),originY:crack.getAttribute('data-origin-y'),waterOriginX:water.getAttribute('x'),waterOriginY:water.getAttribute('y'),
     holeWidth:hole.width,holeHeight:hole.height,holeToPotWidth:hole.width/pot.width,
     waterWidth:right-left,waterHeight:bottom-top,paintedPixels,
     waterWithinStage:left>=stage.left-1&&right<=stage.right+1&&top>=stage.top-1&&bottom<=stage.bottom+1};
   },index);}

// Native range keyboard changes, same real animation frames, two spending scales.
test('local slider full range changes visible severity and preserves Lottie instance',async({page})=>{
 await page.setViewportSize({width:1920,height:1080});await page.goto('/__visual__/pot.html');
 const samples=[];
 for(const spending of [500000,58000]){
  await page.getByRole('button',{name:`지출 ${spending}원`,exact:true}).click();
  const slider=page.getByRole('slider',{name:'비교 한도',exact:true});
  await expect(slider).toHaveValue(String(spending));await expect(page.locator('.crack')).toHaveCount(0);
  let savedSvg:Awaited<ReturnType<ReturnType<Page['locator']>['elementHandle']>>|null=null;
  const ascending=[];
  for(let step=1;step<=5;step++){
   await slider.focus();await page.keyboard.press('ArrowLeft');
   const budget=spending-spending/5*step;await expect(slider).toHaveValue(String(budget));
   await expect(page.locator('.pot-leak-stream svg')).toBeVisible();
   if(savedSvg)expect(await savedSvg.evaluate(el=>el===document.querySelector('.pot-leak-stream svg'))).toBe(true);
   else savedSvg=await page.locator('.pot-leak-stream svg').elementHandle();
   const frames=[];
   for(const frame of [5,12,19,20]){
    await page.getByRole('button',{name:`프레임 ${frame}`,exact:true}).click();
    const measured=await measureWater(page);
    expect(measured.waterWithinStage).toBe(true);expect(measured.paintedPixels).toBeGreaterThan(0);
    expect(measured.originX).toBe(measured.waterOriginX);expect(measured.originY).toBe(measured.waterOriginY);
    frames.push({frame,...measured});
    if(frame===12&&spending===500000&&[1,3,5].includes(step))await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-slider-${step*20}-percent.png`});
   }
   ascending.push({spending,budget,ratio:step/5,frames});
  }
  for(let i=1;i<ascending.length;i++)for(let j=0;j<4;j++){
   const a=ascending[i-1].frames[j],b=ascending[i].frames[j];
   expect(b.originX).toBe(a.originX);expect(b.originY).toBe(a.originY);
   for(const key of ['holeWidth','holeHeight','waterWidth','waterHeight'] as const)expect(b[key]).toBeGreaterThan(a[key]);
  }
  // Normal playback keeps advancing; a size update must retain the same SVG.
  await page.getByRole('button',{name:'재생 계속',exact:true}).click();
  const frameBefore=await page.locator('.pot-leak-stream svg').innerHTML();
  await expect.poll(()=>page.locator('.pot-leak-stream svg').innerHTML()).not.toBe(frameBefore);
  const descending=[];
  for(let step=4;step>=0;step--){
   await slider.focus();await page.keyboard.press('ArrowRight');
   await expect(slider).toHaveValue(String(spending-spending/5*step));
   if(step===0){await expect(page.locator('.crack')).toHaveCount(0);await expect(page.locator('.pot-leak-stream')).toHaveCount(0);continue;}
   expect(await savedSvg!.evaluate(el=>el===document.querySelector('.pot-leak-stream svg'))).toBe(true);
   await page.getByRole('button',{name:'프레임 12',exact:true}).click();
   const measured=await measureWater(page),original=ascending[step-1].frames[1];
   for(const key of ['holeWidth','holeHeight','waterWidth','waterHeight'] as const)expect(measured[key]).toBeCloseTo(original[key],2);
   descending.push({ratio:step/5,...measured});
  }
  await slider.focus();await page.keyboard.press('ArrowLeft');await expect(page.locator('.crack')).toHaveCount(1);
  expect(await page.locator('.crack').getAttribute('data-origin-x')).toBe(ascending[0].frames[0].originX);
  samples.push({spending,ascending,descending,zeroAbsent:true,normalPlayback:true,instancePreserved:true});
 }
 writeFileSync(`${process.env.E2E_ARTIFACTS}/severity.json`,JSON.stringify({status:'PASS',samples,scope:'actual visualization; native fixture range input; actual product sliders covered separately'}));
});

test('local actual budget sliders span housing range without external scroll',async({page})=>{
 await page.setViewportSize({width:1920,height:900});await page.goto('/pot/10');
 const slider=page.getByRole('slider',{name:'주거 / 통신 한도',exact:true});await expect(slider).toHaveValue('600000');
 await slider.focus();await page.keyboard.press('Home');await expect(slider).toHaveValue('0');
 const points=[];
 for(const target of [0,100000,200000,300000,400000,500000]){
  if(target)for(let i=1;i<=20;i++){await page.keyboard.press('ArrowRight');await expect(slider).toHaveValue(String(target-100000+i*5000));}
  await expect(slider).toHaveValue(String(target));
  const crack=page.locator('.crack[data-category="주거 / 통신"]');
  if(target===500000)await expect(crack).toHaveCount(0);
  else{await expect(crack).toBeVisible();points.push({budget:target,width:(await crack.boundingBox())!.width});}
  assertPotViewport(await potViewportGeometry(page));
 }
 for(let i=1;i<points.length;i++)expect(points[i].width).toBeLessThan(points[i-1].width);
 writeFileSync(`${process.env.E2E_ARTIFACTS}/product-slider.json`,JSON.stringify({status:'PASS',points,zeroEffectAtSpending:true}));
});

for(const reducedMotion of ['no-preference','reduce'] as const){
 test(`local advice intentional gesture paging ${reducedMotion}`,async({page})=>{
  await page.setViewportSize({width:1920,height:900});await page.emulateMedia({reducedMotion});await page.goto('/toadAdvice');
  const region=page.locator('.snap-container'),heading=page.locator('#advice-details-title');
  await expect(page.locator('.page-hero h1')).toBeVisible();
  const atDetails=()=>expect.poll(()=>heading.evaluate(el=>Math.abs(el.getBoundingClientRect().top-112)<3)).toBe(true);
  await page.locator('.page-hero h1').hover();await page.mouse.wheel(0,32);await atDetails();
  // Remaining events within the declared gesture gap cannot bounce backwards.
  for(let i=0;i<4;i++)await page.mouse.wheel(0,-5);
  await atDetails();await page.waitForTimeout(250); // Deliberately start a NEW gesture (idle contract 200ms).
  await heading.hover();await page.mouse.wheel(0,-32);await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBe(0);
  await page.waitForTimeout(250);
  await page.locator('.page-hero h1').hover();for(let i=0;i<4;i++)await page.mouse.wheel(0,8);
  await atDetails();
  await page.setViewportSize({width:1440,height:1000});await atDetails();
  await page.waitForTimeout(250);await heading.focus();await page.keyboard.press('PageUp');await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBe(0);
  await page.keyboard.press('PageDown');await atDetails();
  await page.keyboard.press('PageUp');await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBe(0);
  const cta=page.getByRole('button',{name:'카테고리별 소비 조언 보기 ↓',exact:true});await cta.focus();await page.keyboard.press('Space');await atDetails();await expect(heading).toBeFocused();
  await page.screenshot({path:`${process.env.E2E_ARTIFACTS}/after-paging-${reducedMotion}.png`});
  writeFileSync(`${process.env.E2E_ARTIFACTS}/paging-${reducedMotion}.json`,JSON.stringify({status:'PASS',wheel32:true,smallSeries8x4:true,inertiaNoBounce:true,newUpGesture:true,resize:true,keyboard:true,cta:true,reducedMotion}));
 });
}

test('local advice long content and modal retain internal reading',async({page})=>{
 await page.setViewportSize({width:390,height:844});await page.emulateMedia({reducedMotion:'reduce'});await page.goto('/pot/10');
 await expect(page.getByRole('slider',{name:'카페 한도',exact:true})).toHaveValue('40000');
 for(const slider of await page.getByRole('slider').all())if(await slider.isEnabled()){await slider.focus();await page.keyboard.press('Home');}
 await clickDemoMenu(page,'두꺼비의 조언');
 const region=page.locator('.snap-container'),heading=page.locator('#advice-details-title');
 await expect(page.locator('.page-hero')).toBeVisible();
 const heroHeight=await page.locator('.page-hero').evaluate(el=>el.getBoundingClientRect().height);expect(heroHeight).toBeGreaterThan(844);
 await page.locator('.page-hero h1').hover();await page.mouse.wheel(0,32);
 await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBeGreaterThan(0);
 expect(await heading.evaluate(el=>el.getBoundingClientRect().top)).toBeGreaterThan(844-64);
 await page.mouse.wheel(0,1500);await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBeCloseTo(heroHeight-844,0);
 await page.waitForTimeout(250);await page.mouse.wheel(0,32);
 await expect.poll(()=>heading.evaluate(el=>Math.abs(el.getBoundingClientRect().top-112)<3)).toBe(true);
 await page.waitForTimeout(250);await heading.hover();await page.mouse.wheel(0,250);
 await expect.poll(()=>heading.evaluate(el=>el.getBoundingClientRect().top)).toBeLessThan(0);
 await page.waitForTimeout(250);await region.hover();await page.mouse.wheel(0,-2500);
 await expect.poll(()=>heading.evaluate(el=>Math.abs(el.getBoundingClientRect().top-112)<3)).toBe(true);
 await page.mouse.wheel(0,-10);expect(await region.evaluate(el=>el.scrollTop)).toBeGreaterThan(0);
 await page.waitForTimeout(250);await heading.hover();await page.mouse.wheel(0,-32);await expect.poll(()=>region.evaluate(el=>el.scrollTop)).toBe(0);
 await page.getByRole('button',{name:'카테고리별 소비 조언 보기 ↓',exact:true}).click();
 const card=page.getByRole('button',{name:/카페/}).filter({has:page.locator('.category-title')});await card.click();
 const dialog=page.getByRole('dialog',{name:'카페',exact:true});await expect(dialog).toBeVisible();
 const before=await region.evaluate(el=>el.scrollTop);await dialog.hover();await page.mouse.wheel(0,300);await page.keyboard.press('PageDown');
 expect(await region.evaluate(el=>el.scrollTop)).toBe(before);
 await expect.poll(()=>dialog.evaluate(el=>el.scrollTop)).toBeGreaterThan(0);
 expect(await region.evaluate(el=>el.scrollTop)).toBe(before);
 await page.keyboard.press('Escape');await expect(dialog).toHaveCount(0);await expect(card).toBeFocused();
 writeFileSync(`${process.env.E2E_ARTIFACTS}/paging-content.json`,JSON.stringify({status:'PASS',longHeroNativeReading:true,newGestureAtBoundary:true,longDetailsNativeReading:true,modalOwnScroll:true,focusReturn:true}));
});
