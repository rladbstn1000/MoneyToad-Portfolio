import { useNavigate } from "react-router-dom";
import "./NotFound.css";

export const notFoundAssets = [
  "/404.webp",
];

export default function NotFound() {
  const navigate = useNavigate();

  return (
    <div className="nf-wrap">
      {/* 404 배경 이미지 */}
      <img src="/404.webp" alt="404 Not Found" className="nf-bg" />

      {/* 두꺼비 옆 말풍선 */}
      <div className="nf-bubble tail-right">
        <p>마당으로 돌아가게</p>
        <button onClick={() => navigate("/")}>네, 알겠습니다</button>
      </div>
    </div>
  );
}
