# Pick Your Food — A. 음식 고르기 설계

- 작성일: 2026-09-23
- 범위: 서브 프로젝트 A (모드 선택 · 랜덤 · 5단계 추론)
- 범위 밖: 서브 프로젝트 B (주변/근교 맛집, 데이트 코스, 가격·리뷰 비교) — 외부 데이터 출처 결정이 필요하므로 별도 스펙으로 진행

## 1. 목표

사용자가 두 모드 중 하나를 골라 먹을 음식을 정한다.

1. **랜덤 모드** — 음식 1개를 무작위로 보여준다.
2. **추론 모드** — 5단계 질문에 답하면 1순위 음식 1개와 대안 2개를 보여준다.

성공 기준:
- 두 흐름을 브라우저에서 처음부터 끝까지 막힘없이 진행할 수 있다.
- 같은 답변이면 기분이 맞는 음식이 우선 추천된다.
- 고른 종류(예: 한식)와 다른 종류의 음식은 추천되지 않는다 ("상관없음" 제외).

## 2. 5단계 질문

순서대로 한 화면에 하나씩 묻는다. 모두 선택지 탭 방식.

| # | 질문 | 선택지 (한글 라벨 → enum) |
|---|------|------------------------|
| 1 | 상황 | 혼밥 `ALONE` / 친구 `FRIENDS` / 연인 `DATE` / 가족·회식 `GROUP` |
| 2 | 기분 | 신남 `EXCITED` / 평범 `NORMAL` / 우울·지침 `DOWN` / 스트레스 `STRESSED` |
| 3 | 먹고 싶은 종류 | 한식 `KOREAN` / 중식 `CHINESE` / 일식 `JAPANESE` / 양식 `WESTERN` / 분식 `SNACK` / 상관없음 `ANY` |
| 4 | 배고픈 정도 | 살짝 출출 `LIGHT` / 적당히 `MODERATE` / 매우 배고픔 `STARVING` |
| 5 | 맛 성향 | 매콤 `SPICY` / 담백 `MILD` / 기름진 `RICH` / 달달·새콤 `SWEET_SOUR` |

## 3. 아키텍처

Spring Boot가 음식 데이터와 추론 로직을 가진 REST API를 제공하고, `frontend/`의 React(Vite) 앱이 화면을 담당한다. DB는 쓰지 않는다.

```
[React (Vite, :5173)] --/api 프록시--> [Spring Boot (:8080)] --> foods.json (classpath)
```

B 단계에서 외부 API 키를 서버에 숨길 수 있도록 로직은 서버에 둔다.

## 4. 백엔드

의존성: `spring-boot-starter-webmvc` 추가 (DB/JPA 없음).

패키지 `com.example.pickyourfood.food`:

| 파일 | 역할 |
|------|------|
| `Food.java` (record) | `id`, `name`, `description`, `category`, `situations`, `moods`, `hunger`, `tastes` |
| `Answers.java` (record + enum) | 5단계 답변. 질문별 선택지는 enum |
| `FoodCatalog.java` | 시작 시 `src/main/resources/foods.json` 로드, 목록 보관 |
| `Recommender.java` | 랜덤 1개 선택, 점수 계산 후 상위 3개 선택. `Random`은 생성자로 주입 |
| `FoodController.java` | REST 엔드포인트 2개 |

`Food`의 태그 필드: `category`는 단일 값(`ANY` 제외), `situations`·`moods`·`hunger`·`tastes`는 해당 음식에 어울리는 값들의 집합.

### API

- `GET /api/foods/random` → `Food`
- `POST /api/recommendations`
  - 요청: `{ "situation", "mood", "category", "hunger", "taste" }` (모두 필수, enum 이름)
  - 응답: `{ "best": Food, "alternatives": [Food, Food] }`

### 추론 규칙

1. `category`가 `ANY`가 아니면 해당 종류의 음식만 후보로 남긴다.
2. 후보마다 답변이 음식 태그에 포함되면 점수를 더한다:
   - 기분 +4
   - 맛 성향 +3
   - 배고픈 정도 +2
   - 상황 +2
3. 점수 내림차순 정렬. 동점은 주입된 `Random`으로 섞는다 (같은 답이어도 결과가 조금씩 달라짐).
4. 1위가 `best`, 2·3위가 `alternatives`. 후보가 3개 미만이면 있는 만큼만 반환한다.

가중치는 `Recommender`의 상수로 둔다.

### 데이터

`foods.json`에 약 40개. 종류별(한식·중식·일식·양식·분식) 6~8개. 이미지 없음.

### 에러 처리

- 필수 답변 누락 또는 잘못된 enum 값 → 400.
- 후보 부족 → 있는 만큼 반환 (데이터를 종류별 6개 이상으로 유지해 실제로는 발생하지 않게 함).

## 5. 프론트엔드 (`frontend/`)

스택: Vite + React + TypeScript, Tailwind v4 (`@tailwindcss/vite`), `motion`, `@phosphor-icons/react`. Vite 개발 서버가 `/api`를 `localhost:8080`으로 프록시.

### 화면 흐름

라우터 없이 `App.tsx`가 현재 단계를 상태로 관리한다.

```
모드 선택 ─┬─ 랜덤 ─────────────→ 랜덤 결과 (다시 뽑기 / 처음으로)
           └─ 추론 → Q1 … Q5 ──→ 추천 결과 (다시 하기 / 처음으로)
```

| 파일 | 역할 |
|------|------|
| `App.tsx` | 단계 전환, 화면 전환 애니메이션 |
| `ModeSelect.tsx` | 두 모드 선택 |
| `Questionnaire.tsx` | 질문 하나씩 표시, 선택 시 자동 다음, 뒤로 가기, 진행도(n/5) |
| `ResultView.tsx` | 추천 결과(1순위 크게 + 대안 2개)와 랜덤 결과 표시 |
| `questions.ts` | 질문 문구, 한글 라벨 ↔ enum 매핑 (2장 표와 동일) |
| `api.ts` | API 호출 2개 |

### 디자인

- 폰트: 한글 Pretendard, 숫자·영문 Geist.
- 색: zinc 중립 바탕 + 채도 낮춘 토마토 레드 강조색 1개. 보라/파랑 그라데이션, 이모지 금지.
- 레이아웃: `md` 이상에서 왼쪽 제목 / 오른쪽 선택지 비대칭 배치, `md` 미만은 한 열. 전체 높이는 `min-h-[100dvh]`.
- 모션: 질문 전환 스프링 슬라이드, 선택지 누름 피드백(`scale-[0.98]`), 랜덤 결과는 약 1초간 이름이 빠르게 바뀐 뒤 확정, 추천 결과는 1순위 → 대안 순차 등장.

### 상태

- 로딩: 결과 카드와 같은 크기의 스켈레톤.
- 에러: 결과 자리에 오류 문구 + "다시 시도" 버튼.
- B 연결용 가짜 버튼은 넣지 않는다. B에서 결과 카드 아래에 추가한다.

## 6. 테스트

백엔드 (`./gradlew test`):
- `RecommenderTest` (고정 seed): 종류 필터링, `ANY`면 전 종류 후보, 기분 일치 음식이 맛 성향만 일치하는 음식보다 우선, `best` + `alternatives` 2개 중복 없음.
- `FoodCatalogTest`: `foods.json` 로드 성공, 종류마다 3개 이상.
- `FoodControllerTest` (MockMvc): 두 엔드포인트 200, 잘못된 enum·누락 답변 400.

프론트엔드: 별도 테스트 프레임워크 없음. `npm run build`로 타입 검사, 브라우저에서 두 흐름 수동 확인.

## 7. 실행

- 개발: `./gradlew bootRun` (8080) + `cd frontend && npm run dev` (5173).
- 단일 배포(프론트 빌드 결과를 `static`에 복사)는 이번 범위 밖. 필요 시 Gradle 작업 하나로 추가.
