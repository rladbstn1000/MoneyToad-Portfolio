# Demo architecture and boundaries

The explicit demo profile runs Spring Boot, MySQL and Redis without SSAFY provider configuration. It retains the real JWT filter, session guard and ownership checks. Login installs a visitor-specific synthetic User, one financial-data-free Card, 240 transactions and 72 reference budgets atomically before returning authentication success.

The code-authored scenario has 9,990,000 total spending across twelve anchored months. The current scenario month has 908,000 spending and 18,000 leakage; moving its category-practice transaction from cafe to grocery preserves spending and removes that leakage. These are synthetic reference budgets, not AI forecasts.

Demo access tokens remain in frontend memory; refresh is an HttpOnly cookie. Redis sessions expire absolutely within one hour. Rotation never extends that lifetime. Reuse revokes the session. Frontend coordination covers one tab; multi-tab coordination remains future work.

## External reference classification

|Class|Existing reference|Meaning|
|---|---|---|
|A — legacy OAuth only|OAuth2SuccessHandler and the OAuth landing button retain the team login/redirect host|Preserved legacy functionality, not used by demo. It is not a portable OAuth deployment configuration.|
|A — legacy policy|SecurityConfig retains that host in the original-mode CORS list|An allowlist entry is not itself an outbound call.|
|B — demo asset debt|Three original CSS files reference the font CDN|Product CSS is preserved. Only the test build uses system fallback; font redistribution is unverified.|
|B — required configuration|AI_BASE_URL|Required to construct CsvClient. Demo Chart does not call AI. Use an owned loopback placeholder during verification.|
|C — verification inputs|Reserved invalid domains in security tests|Negative test inputs, not services to contact.|
|C — tool/provenance URLs|Gradle/npm/documentation URLs|Build distribution or source documentation, not demo application requests.|

The original AI directory, MinIO configuration, porting PDF and unverified CSV files are outside this snapshot's allowlist. Demo seed uses none of them. No font file has been added. Original product code still has legacy OAuth and authentication logging debt; source publication is not a claim of public-service deployment readiness.

Data remains in SQL after session expiry/logout. Cleanup, rate limiting, AI adapters, multi-tab coordination and hosting are outside this snapshot preparation.
