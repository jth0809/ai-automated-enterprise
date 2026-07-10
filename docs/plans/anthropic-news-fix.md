# 백로그: 뉴스 피드 오류 수정 (Anthropic AI 연동 및 프런트엔드 HTML 파싱)

**작성일**: 2026-07-10
**목표**: 뉴스 탭의 원시 HTML 노출 버그를 수정하고, 누락된 Anthropic AI 요약 기능을 백엔드에 실제 구현하여 연동한다.

## 1. 현재 문제점 (Current Issues)
1. **프런트엔드 HTML 노출 (UI Bug)**: RSS 피드 데이터(`excerpt`)에 섞여 있는 `<img>`, `<b>`, `<a>` 등의 HTML 태그가 `NewsFeed.tsx` 화면에 그대로 문자열로 노출되어 가독성을 심각하게 해침.
2. **AI 요약 기능 미구현 (Backend Stub)**: 현재 `NewsConfig.java`에 `DisabledSummarizer`만 강제 주입되어 있으며, `ANTHROPIC_API_KEY`를 넣었음에도 불구하고 실제 Claude API를 호출하는 로직(`AnthropicSummarizer.java`)이 아예 존재하지 않음.
3. **네트워크 정책 차단 (Zero-Trust Block)**: 백엔드에서 `api.anthropic.com`으로 외부 요청(Egress)을 보내야 하지만, 현재 CiliumNetworkPolicy(`network-policy.yaml`)에 의해 차단되어 있어 기능 구현 시 타임아웃 발생 예정.

---

## 2. 작업 목록 (Backlog Tasks)

### 🟢 Task 1: 프런트엔드 HTML 태그 제거 로직 구현
- [ ] `apps/frontend/react-app/src/components/NewsFeed.tsx` 수정.
- [ ] `DOMParser` 등을 활용하여 안전하게 HTML 태그를 제거(strip)하고 순수 텍스트(plain text)만 추출하는 `stripHtml` 유틸리티 함수 작성.
- [ ] 추출된 텍스트를 `<p className="news-body">`에 렌더링.
- [ ] 수정된 렌더링 결과가 TDD 원칙에 맞게 동작하는지 테스트(`npm test`) 확인.

### 🔵 Task 2: 백엔드 AnthropicSummarizer 구현
- [ ] `apps/backend/springboot-app/src/main/resources/application.yml`에 `ANTHROPIC_API_KEY` 환경변수 맵핑 추가.
- [ ] `AnthropicSummarizer.java` 클래스 생성 (Spring의 `RestTemplate` 또는 `RestClient` 활용).
  - 엔드포인트: `https://api.anthropic.com/v1/messages`
  - 필수 헤더: `x-api-key`, `anthropic-version`, `content-type`
  - 원본 기사 내용을 Claude 모델에 전달하고 1~2문장 요약 결과 반환.
- [ ] `NewsConfig.java`를 수정하여 `ANTHROPIC_API_KEY`가 존재할 경우 `AnthropicSummarizer` 빈을 주입하고, 없을 경우 `DisabledSummarizer`로 폴백(Fallback)하도록 조건부 빈(`@ConditionalOnProperty` 또는 수동 로직) 등록.

### 🔴 Task 3: GitOps 망 분리 해제 (CiliumNetworkPolicy)
- [ ] `gitops/apps/backend-springboot/network-policy.yaml` 수정.
- [ ] `backend-springboot` 파드가 외부 `api.anthropic.com`의 443 포트로 통신할 수 있도록 Egress 규칙(`toFQDNs`) 추가.

---

## 3. 검증 계획 (Verification)
- 백엔드/프런트엔드 유닛 테스트 통과 여부 확인.
- 실제 화면에서 HTML 태그가 보이지 않고 깔끔한 텍스트만 출력되는지 시각적 검증.
- AI 요약 배지(AI summary badge)가 정상적으로 표시되고 Claude가 요약한 텍스트가 렌더링되는지 확인.

---

## 4. 추가 작업: AI API 호출 효율화 및 Rate Limit 방지 (2026-07-10 추가)

### 🟡 Task 4: NewsService 요약 제한 로직 추가
- [ ] `apps/backend/springboot-app/src/main/java/com/aienterprise/backend/news/NewsService.java`의 `ingest()` 메서드 수정.
- [ ] 피드당 모든 기사를 요약하지 않도록, `MAX_SUMMARIES_PER_INGEST = 3` 상수를 도입.
- [ ] 반복문 내에서 카운터를 두어, 첫 3개의 기사만 `summarizer.summarize()`를 호출하고, 나머지는 요약 없이 저장(`byLink.put(article.link(), article)`)하도록 최적화.
- [ ] 이 변경사항에 맞춰 `NewsServiceTest.java`를 업데이트하고, 전체 `mvnw test` 통과 확인.
