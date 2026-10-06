import './LocalDemoNotice.css';

/** This describes browser-memory data, never a server authentication session. */
export default function LocalDemoNotice() {
  return <details className="local-demo-notice">
    <summary>샘플 체험 안내</summary>
    <p>샘플 데이터로 체험합니다. 변경 내용은 이 탭에서만 유지되며 새로고침하면 초기화됩니다.</p>
  </details>;
}
