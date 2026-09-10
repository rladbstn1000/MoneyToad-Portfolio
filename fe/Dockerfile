# Node.js 기반 이미지 사용
FROM node:20-alpine

# 작업 디렉터리 설정
WORKDIR /app

# package.json과 package-lock.json 복사 (캐싱 최적화)
COPY package*.json ./

# 의존성 설치
RUN npm ci

# 소스 코드 복사
COPY . .

# 애플리케이션 빌드
RUN npm run build

# 포트 노출 (Vite preview 기본 포트)
EXPOSE 4173

# Vite preview 서버 실행
CMD ["npm", "run", "preview", "--", "--host"]