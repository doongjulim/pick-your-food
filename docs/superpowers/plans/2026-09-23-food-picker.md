# Pick Your Food — A. 음식 고르기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 랜덤 모드와 5단계 질문 추론 모드로 먹을 음식을 골라 주는 웹앱(Spring Boot API + React 프론트엔드)을 만든다.

**Architecture:** Spring Boot가 `foods.json`(약 40개)을 읽어 두 개의 REST 엔드포인트(`GET /api/foods/random`, `POST /api/recommendations`)로 랜덤 선택과 가중치 점수 추천을 제공한다. `frontend/`의 Vite + React 앱이 모드 선택 → 질문 → 결과 화면을 그리고, 개발 중에는 Vite 프록시로 `/api`를 8080으로 넘긴다. DB는 없다.

**Tech Stack:** Spring Boot 4.1.1 (`spring-boot-starter-webmvc`, Jackson 3 = `tools.jackson`), Java 17, JUnit 5 + AssertJ + MockMvc / Vite 8, React 19, TypeScript 6, Tailwind v4 (`@tailwindcss/vite`), `motion` 13, `@phosphor-icons/react` 2.

**Spec:** `docs/superpowers/specs/2026-09-23-food-picker-design.md`

## Global Constraints

- Gradle은 JDK 17 이상으로 실행해야 한다. 이 머신의 기본 JDK는 11이므로 **모든 `./gradlew` 명령 앞에** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)`을 실행한다.
- Java 코드는 전부 패키지 `com.example.pickyourfood.food`, 들여쓰기는 탭 (기존 `PickYourFoodApplication.java`와 동일).
- Jackson은 3.x: import는 `tools.jackson.databind...` (`com.fasterxml.jackson.databind` 아님).
- DB/JPA/validation starter 추가 금지. 새 의존성은 `spring-boot-starter-webmvc`, `spring-boot-starter-webmvc-test`만.
- 가중치: 기분 +4, 맛 성향 +3, 배고픈 정도 +2, 상황 +2. `Recommender`의 상수.
- 질문 순서: 상황 → 기분 → 먹고 싶은 종류 → 배고픈 정도 → 맛 성향.
- enum 값 (스펙 2장): Situation `ALONE FRIENDS DATE GROUP` / Mood `EXCITED NORMAL DOWN STRESSED` / Category `KOREAN CHINESE JAPANESE WESTERN SNACK ANY` / Hunger `LIGHT MODERATE STARVING` / Taste `SPICY MILD RICH SWEET_SOUR`.
- 프론트엔드: 이모지 금지, 보라/파랑 그라데이션 금지, 강조색은 토마토 레드 하나(`--color-accent`), 바탕 zinc, 폰트 Pretendard(한글) + Geist(숫자·영문), 전체 높이 `min-h-[100dvh]`, `md` 미만 한 열.
- 모션은 `motion/react`의 spring(`stiffness: 100, damping: 20`)을 쓴다. `transform`/`opacity`만 애니메이션.
- 커밋 메시지 끝에 `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` 줄을 붙인다.

## Review Focus

1. **질문 전환 중 빠른 두 번 탭** — 사라지는 화면의 선택지를 한 번 더 누르면 다음 질문이 건너뛰어지면 안 된다. → Task 5의 `useIsPresent` 가드 + 수동 확인 단계.
2. **어떤 답 조합이든 결과 3개** — 1,152가지 모든 조합에서 `best` + 대안 2개, 중복 없음. → Task 2 `everyAnswerCombinationGivesThreeDistinctFoods`.
3. **이상한 요청 본문** — 빈 본문, 깨진 JSON, 소문자 enum(`"alone"`), 필드 누락은 500이 아니라 400. → Task 3 컨트롤러 테스트.
4. **`foods.json` 데이터 실수** — 중복 id, 빈 태그, `ANY` 카테고리 음식, 종류별 3개 미만. → Task 1 `FoodCatalogTest`.
5. **서버가 꺼져 있거나 응답이 늦을 때** — 스켈레톤 후 오류 문구와 "다시 시도"가 보이고, 그 사이 "처음으로"를 누르면 늦게 도착한 응답이 화면을 덮어쓰지 않는다. → Task 5 `App.tsx`의 요청 id 비교 + 수동 확인 단계.

---

## File Structure

```
build.gradle                                   (수정) webmvc 의존성
src/main/resources/foods.json                  (생성) 음식 40개
src/main/java/com/example/pickyourfood/food/
  Answers.java      5단계 답변 record + 질문별 enum
  Food.java         음식 record
  FoodCatalog.java  foods.json 로드
  Recommender.java  랜덤 / 점수 추천, Recommendation record
  FoodController.java  REST 엔드포인트 2개
src/test/java/com/example/pickyourfood/food/
  FoodCatalogTest.java
  RecommenderTest.java
  FoodControllerTest.java
frontend/                                      (생성) Vite React TS 앱
  index.html, vite.config.ts
  src/main.tsx, index.css, api.ts, questions.ts,
      App.tsx, ModeSelect.tsx, Questionnaire.tsx, ResultView.tsx
```

---

### Task 1: 도메인 모델과 음식 카탈로그

**Files:**
- Modify: `build.gradle` (dependencies 블록)
- Create: `src/main/java/com/example/pickyourfood/food/Answers.java`
- Create: `src/main/java/com/example/pickyourfood/food/Food.java`
- Create: `src/main/java/com/example/pickyourfood/food/FoodCatalog.java`
- Create: `src/main/resources/foods.json`
- Test: `src/test/java/com/example/pickyourfood/food/FoodCatalogTest.java`

**Interfaces:**
- Consumes: 없음
- Produces:
  - `record Answers(Situation situation, Mood mood, Category category, Hunger hunger, Taste taste)` — 모든 필드 null 불가(`NullPointerException`). 중첩 enum `Answers.Situation`, `Answers.Mood`, `Answers.Category`, `Answers.Hunger`, `Answers.Taste`.
  - `record Food(String id, String name, String description, Category category, Set<Situation> situations, Set<Mood> moods, Set<Hunger> hunger, Set<Taste> tastes)`
  - `@Component class FoodCatalog { FoodCatalog(JsonMapper mapper); List<Food> all(); }`

- [ ] **Step 1: 기존 Spring 뼈대를 먼저 커밋**

저장소에는 아직 `README.md`만 커밋돼 있다. 이후 커밋이 깔끔하도록 뼈대를 먼저 커밋한다. `.claude/`, `.serena/`, `graphify-out/`은 커밋하지 않는다.

```bash
git add .gitattributes .gitignore CLAUDE.md build.gradle settings.gradle gradlew gradlew.bat gradle src docs
git status --short   # .claude/ .serena/ graphify-out/ 만 ?? 로 남아야 한다
git commit -m "chore: add Spring Boot project skeleton

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 2: webmvc 의존성 추가**

`build.gradle`의 dependencies 블록을 다음으로 교체:

```groovy
dependencies {
	implementation 'org.springframework.boot:spring-boot-starter-webmvc'
	testImplementation 'org.springframework.boot:spring-boot-starter-test'
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
```

(`spring-boot-starter-webmvc`가 `spring-boot-starter`를 포함하므로 기존 줄은 대체된다.)

- [ ] **Step 3: 실패하는 테스트 작성**

`src/test/java/com/example/pickyourfood/food/FoodCatalogTest.java`:

```java
package com.example.pickyourfood.food;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.food.Answers.Category;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class FoodCatalogTest {

	private final List<Food> foods = new FoodCatalog(new JsonMapper()).all();

	@Test
	void everyCategoryHasAtLeastThreeFoods() {
		Map<Category, Long> counts = foods.stream().collect(Collectors.groupingBy(Food::category, Collectors.counting()));
		Arrays.stream(Category.values())
				.filter(category -> category != Category.ANY)
				.forEach(category -> assertThat(counts.getOrDefault(category, 0L)).as(category.name()).isGreaterThanOrEqualTo(3));
	}

	@Test
	void idsAreUnique() {
		assertThat(foods).extracting(Food::id).doesNotHaveDuplicates();
	}

	@Test
	void everyFoodIsFullyTagged() {
		assertThat(foods).allSatisfy(food -> {
			assertThat(food.name()).as(food.id()).isNotBlank();
			assertThat(food.description()).as(food.id()).isNotBlank();
			assertThat(food.category()).as(food.id()).isNotNull().isNotEqualTo(Category.ANY);
			assertThat(food.situations()).as(food.id()).isNotEmpty();
			assertThat(food.moods()).as(food.id()).isNotEmpty();
			assertThat(food.hunger()).as(food.id()).isNotEmpty();
			assertThat(food.tastes()).as(food.id()).isNotEmpty();
		});
	}
}
```

- [ ] **Step 4: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*FoodCatalogTest'`
Expected: FAIL — 컴파일 에러 (`cannot find symbol: class FoodCatalog`)

- [ ] **Step 5: `Answers.java` 작성**

```java
package com.example.pickyourfood.food;

import java.util.Objects;

public record Answers(Situation situation, Mood mood, Category category, Hunger hunger, Taste taste) {

	public Answers {
		Objects.requireNonNull(situation, "situation");
		Objects.requireNonNull(mood, "mood");
		Objects.requireNonNull(category, "category");
		Objects.requireNonNull(hunger, "hunger");
		Objects.requireNonNull(taste, "taste");
	}

	public enum Situation { ALONE, FRIENDS, DATE, GROUP }

	public enum Mood { EXCITED, NORMAL, DOWN, STRESSED }

	public enum Category { KOREAN, CHINESE, JAPANESE, WESTERN, SNACK, ANY }

	public enum Hunger { LIGHT, MODERATE, STARVING }

	public enum Taste { SPICY, MILD, RICH, SWEET_SOUR }
}
```

- [ ] **Step 6: `Food.java` 작성**

```java
package com.example.pickyourfood.food;

import com.example.pickyourfood.food.Answers.Category;
import com.example.pickyourfood.food.Answers.Hunger;
import com.example.pickyourfood.food.Answers.Mood;
import com.example.pickyourfood.food.Answers.Situation;
import com.example.pickyourfood.food.Answers.Taste;
import java.util.Set;

public record Food(
		String id,
		String name,
		String description,
		Category category,
		Set<Situation> situations,
		Set<Mood> moods,
		Set<Hunger> hunger,
		Set<Taste> tastes) {
}
```

- [ ] **Step 7: `FoodCatalog.java` 작성**

Jackson 3의 예외는 unchecked이고 스트림은 Jackson이 닫는다(`AUTO_CLOSE_SOURCE` 기본값).

```java
package com.example.pickyourfood.food;

import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class FoodCatalog {

	private final List<Food> foods;

	public FoodCatalog(JsonMapper mapper) {
		this.foods = List.of(mapper.readValue(FoodCatalog.class.getResourceAsStream("/foods.json"), Food[].class));
	}

	public List<Food> all() {
		return foods;
	}
}
```

- [ ] **Step 8: `src/main/resources/foods.json` 작성**

```json
[
  {"id": "kimchi-jjigae", "name": "김치찌개", "description": "푹 익은 김치와 돼지고기로 끓인 얼큰한 찌개.", "category": "KOREAN", "situations": ["ALONE", "FRIENDS", "GROUP"], "moods": ["DOWN", "STRESSED", "NORMAL"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SPICY"]},
  {"id": "bibimbap", "name": "비빔밥", "description": "나물과 고추장을 쓱쓱 비벼 먹는 한 그릇.", "category": "KOREAN", "situations": ["ALONE", "FRIENDS"], "moods": ["NORMAL", "EXCITED"], "hunger": ["MODERATE"], "tastes": ["MILD", "SPICY"]},
  {"id": "samgyeopsal", "name": "삼겹살", "description": "지글지글 구워 쌈 싸 먹는 국민 회식 메뉴.", "category": "KOREAN", "situations": ["FRIENDS", "GROUP", "DATE"], "moods": ["EXCITED", "STRESSED"], "hunger": ["STARVING"], "tastes": ["RICH"]},
  {"id": "doenjang-jjigae", "name": "된장찌개", "description": "구수한 된장에 두부와 애호박을 넣은 집밥 맛.", "category": "KOREAN", "situations": ["ALONE", "GROUP"], "moods": ["DOWN", "NORMAL"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["MILD"]},
  {"id": "bulgogi", "name": "불고기", "description": "달큰한 간장 양념에 재운 소고기 볶음.", "category": "KOREAN", "situations": ["DATE", "GROUP"], "moods": ["NORMAL", "EXCITED"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SWEET_SOUR", "RICH"]},
  {"id": "gamjatang", "name": "감자탕", "description": "뼈에 붙은 살을 발라 먹는 칼칼하고 진한 탕.", "category": "KOREAN", "situations": ["FRIENDS", "GROUP"], "moods": ["STRESSED", "DOWN"], "hunger": ["STARVING"], "tastes": ["SPICY", "RICH"]},
  {"id": "naengmyeon", "name": "물냉면", "description": "살얼음 동동 뜬 새콤한 육수의 시원한 면.", "category": "KOREAN", "situations": ["ALONE", "FRIENDS", "DATE"], "moods": ["NORMAL", "EXCITED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SWEET_SOUR", "MILD"]},
  {"id": "samgyetang", "name": "삼계탕", "description": "영계 한 마리를 통째로 끓인 보양식.", "category": "KOREAN", "situations": ["ALONE", "GROUP"], "moods": ["DOWN"], "hunger": ["STARVING"], "tastes": ["MILD"]},

  {"id": "jjajangmyeon", "name": "짜장면", "description": "춘장 소스를 듬뿍 얹은 이사 날의 그 맛.", "category": "CHINESE", "situations": ["ALONE", "FRIENDS", "GROUP"], "moods": ["NORMAL", "DOWN"], "hunger": ["MODERATE"], "tastes": ["RICH", "SWEET_SOUR"]},
  {"id": "jjamppong", "name": "짬뽕", "description": "해물 가득 불맛 나는 빨간 국물.", "category": "CHINESE", "situations": ["ALONE", "FRIENDS"], "moods": ["STRESSED", "DOWN"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SPICY"]},
  {"id": "tangsuyuk", "name": "탕수육", "description": "바삭한 튀김에 새콤달콤 소스를 부어 먹는 요리.", "category": "CHINESE", "situations": ["FRIENDS", "GROUP", "DATE"], "moods": ["EXCITED", "NORMAL"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SWEET_SOUR", "RICH"]},
  {"id": "malatang", "name": "마라탕", "description": "원하는 재료를 골라 담는 얼얼한 마라 국물.", "category": "CHINESE", "situations": ["ALONE", "FRIENDS"], "moods": ["STRESSED", "EXCITED"], "hunger": ["MODERATE"], "tastes": ["SPICY"]},
  {"id": "mapo-tofu", "name": "마파두부", "description": "매콤한 두반장 소스에 부드러운 두부를 볶은 덮밥.", "category": "CHINESE", "situations": ["ALONE"], "moods": ["NORMAL", "DOWN"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SPICY", "MILD"]},
  {"id": "xiaolongbao", "name": "샤오롱바오", "description": "한 입 베어 물면 육즙이 터지는 딤섬.", "category": "CHINESE", "situations": ["DATE", "FRIENDS"], "moods": ["EXCITED", "NORMAL"], "hunger": ["LIGHT"], "tastes": ["MILD", "RICH"]},
  {"id": "yangjangpi", "name": "양장피", "description": "해물과 채소를 겨자 소스에 버무린 냉채 요리.", "category": "CHINESE", "situations": ["GROUP", "DATE"], "moods": ["EXCITED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SWEET_SOUR", "SPICY"]},
  {"id": "fried-rice", "name": "볶음밥", "description": "센 불에 볶아 고슬고슬한 계란 볶음밥.", "category": "CHINESE", "situations": ["ALONE"], "moods": ["NORMAL"], "hunger": ["MODERATE", "STARVING"], "tastes": ["RICH", "MILD"]},

  {"id": "sushi", "name": "초밥", "description": "신선한 생선을 올린 한 입 크기의 밥.", "category": "JAPANESE", "situations": ["DATE", "FRIENDS", "ALONE"], "moods": ["EXCITED", "NORMAL"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["MILD", "SWEET_SOUR"]},
  {"id": "ramen", "name": "돈코츠 라멘", "description": "돼지뼈를 오래 우린 진한 국물의 라멘.", "category": "JAPANESE", "situations": ["ALONE", "FRIENDS"], "moods": ["DOWN", "STRESSED"], "hunger": ["MODERATE", "STARVING"], "tastes": ["RICH"]},
  {"id": "tonkatsu", "name": "돈카츠", "description": "두툼한 돼지고기를 바삭하게 튀긴 커틀릿.", "category": "JAPANESE", "situations": ["ALONE", "FRIENDS", "DATE"], "moods": ["NORMAL", "STRESSED"], "hunger": ["STARVING"], "tastes": ["RICH"]},
  {"id": "udon", "name": "우동", "description": "쫄깃한 면발에 따끈하고 맑은 국물.", "category": "JAPANESE", "situations": ["ALONE"], "moods": ["DOWN", "NORMAL"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["MILD"]},
  {"id": "gyudon", "name": "규동", "description": "달짝지근하게 조린 소고기를 올린 덮밥.", "category": "JAPANESE", "situations": ["ALONE"], "moods": ["NORMAL", "DOWN"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SWEET_SOUR", "RICH"]},
  {"id": "okonomiyaki", "name": "오코노미야키", "description": "양배추와 해물을 넣고 부친 일본식 부침개.", "category": "JAPANESE", "situations": ["FRIENDS", "DATE"], "moods": ["EXCITED"], "hunger": ["MODERATE"], "tastes": ["RICH", "SWEET_SOUR"]},
  {"id": "soba", "name": "냉소바", "description": "차가운 쯔유에 찍어 먹는 담백한 메밀면.", "category": "JAPANESE", "situations": ["ALONE", "DATE"], "moods": ["NORMAL", "STRESSED"], "hunger": ["LIGHT"], "tastes": ["MILD", "SWEET_SOUR"]},
  {"id": "tantanmen", "name": "탄탄멘", "description": "고소한 참깨와 고추기름이 어우러진 매운 라멘.", "category": "JAPANESE", "situations": ["ALONE", "FRIENDS"], "moods": ["STRESSED", "EXCITED"], "hunger": ["MODERATE", "STARVING"], "tastes": ["SPICY", "RICH"]},

  {"id": "cream-pasta", "name": "크림 파스타", "description": "베이컨과 버섯을 넣은 꾸덕한 크림 파스타.", "category": "WESTERN", "situations": ["DATE", "FRIENDS"], "moods": ["EXCITED", "NORMAL"], "hunger": ["MODERATE"], "tastes": ["RICH"]},
  {"id": "tomato-pasta", "name": "토마토 파스타", "description": "새콤한 토마토 소스에 바질 향을 더한 파스타.", "category": "WESTERN", "situations": ["DATE", "ALONE"], "moods": ["NORMAL", "EXCITED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SWEET_SOUR", "MILD"]},
  {"id": "arrabbiata", "name": "아라비아타", "description": "페퍼론치노로 매콤하게 맛을 낸 토마토 파스타.", "category": "WESTERN", "situations": ["ALONE", "DATE"], "moods": ["STRESSED"], "hunger": ["MODERATE"], "tastes": ["SPICY"]},
  {"id": "pizza", "name": "피자", "description": "치즈가 쭉 늘어나는 여럿이 나눠 먹기 좋은 한 판.", "category": "WESTERN", "situations": ["FRIENDS", "GROUP"], "moods": ["EXCITED", "STRESSED"], "hunger": ["STARVING"], "tastes": ["RICH"]},
  {"id": "burger", "name": "수제버거", "description": "두툼한 패티와 치즈를 겹겹이 쌓은 버거.", "category": "WESTERN", "situations": ["ALONE", "FRIENDS"], "moods": ["STRESSED", "EXCITED"], "hunger": ["STARVING"], "tastes": ["RICH"]},
  {"id": "steak", "name": "스테이크", "description": "겉은 바삭하고 속은 촉촉하게 구운 소고기.", "category": "WESTERN", "situations": ["DATE", "GROUP"], "moods": ["EXCITED"], "hunger": ["STARVING"], "tastes": ["RICH", "MILD"]},
  {"id": "salad", "name": "샐러드", "description": "닭가슴살과 제철 채소에 발사믹 드레싱.", "category": "WESTERN", "situations": ["ALONE"], "moods": ["NORMAL"], "hunger": ["LIGHT"], "tastes": ["MILD", "SWEET_SOUR"]},
  {"id": "risotto", "name": "리소토", "description": "버섯과 파르메산 치즈로 부드럽게 끓인 쌀 요리.", "category": "WESTERN", "situations": ["DATE"], "moods": ["DOWN", "NORMAL"], "hunger": ["MODERATE"], "tastes": ["RICH", "MILD"]},

  {"id": "tteokbokki", "name": "떡볶이", "description": "매콤달콤한 고추장 소스에 졸인 쫀득한 떡.", "category": "SNACK", "situations": ["FRIENDS", "ALONE"], "moods": ["STRESSED", "DOWN", "EXCITED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SPICY", "SWEET_SOUR"]},
  {"id": "gimbap", "name": "김밥", "description": "햄, 단무지, 시금치를 말아 든든한 한 줄.", "category": "SNACK", "situations": ["ALONE"], "moods": ["NORMAL"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["MILD"]},
  {"id": "ramyeon", "name": "라면", "description": "계란 하나 풀어 끓인 늦은 밤의 친구.", "category": "SNACK", "situations": ["ALONE"], "moods": ["DOWN", "STRESSED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SPICY"]},
  {"id": "sundae", "name": "순대", "description": "당면과 선지를 채운 쫄깃한 순대와 내장.", "category": "SNACK", "situations": ["FRIENDS", "ALONE"], "moods": ["NORMAL"], "hunger": ["MODERATE"], "tastes": ["MILD", "RICH"]},
  {"id": "twigim", "name": "모듬 튀김", "description": "오징어, 고구마, 김말이를 바삭하게 튀긴 한 접시.", "category": "SNACK", "situations": ["FRIENDS", "GROUP"], "moods": ["EXCITED", "STRESSED"], "hunger": ["MODERATE"], "tastes": ["RICH"]},
  {"id": "rabokki", "name": "라볶이", "description": "떡볶이 국물에 라면 사리를 더한 든든한 조합.", "category": "SNACK", "situations": ["FRIENDS", "DATE"], "moods": ["EXCITED", "STRESSED"], "hunger": ["STARVING"], "tastes": ["SPICY", "SWEET_SOUR"]},
  {"id": "jjolmyeon", "name": "쫄면", "description": "새콤매콤한 초고추장에 비벼 먹는 쫄깃한 면.", "category": "SNACK", "situations": ["ALONE", "FRIENDS"], "moods": ["NORMAL", "EXCITED"], "hunger": ["LIGHT", "MODERATE"], "tastes": ["SWEET_SOUR", "SPICY"]},
  {"id": "mandu", "name": "찐만두", "description": "얇은 피에 고기와 채소 소를 꽉 채운 만두.", "category": "SNACK", "situations": ["ALONE", "GROUP"], "moods": ["DOWN", "NORMAL"], "hunger": ["LIGHT"], "tastes": ["MILD"]}
]
```

- [ ] **Step 9: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL. `FoodCatalogTest` 3개 + 기존 `PickYourFoodApplicationTests` 1개 통과.

- [ ] **Step 10: 커밋**

```bash
git add build.gradle src/main/java/com/example/pickyourfood/food src/main/resources/foods.json src/test/java/com/example/pickyourfood/food/FoodCatalogTest.java
git commit -m "feat: add food model and catalog with 40 foods

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: 추천 로직 (`Recommender`)

**Files:**
- Create: `src/main/java/com/example/pickyourfood/food/Recommender.java`
- Test: `src/test/java/com/example/pickyourfood/food/RecommenderTest.java`

**Interfaces:**
- Consumes: `Answers`, `Food`, `FoodCatalog.all()` (Task 1)
- Produces:
  - `@Component class Recommender`
    - `public Recommender(FoodCatalog catalog)` — Spring이 사용 (`@Autowired`)
    - `Recommender(List<Food> foods, Random random)` — 패키지 전용, 테스트용
    - `public Food random()`
    - `public Recommendation recommend(Answers answers)`
  - `public record Recommender.Recommendation(Food best, List<Food> alternatives)`

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pickyourfood/food/RecommenderTest.java`:

```java
package com.example.pickyourfood.food;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.pickyourfood.food.Answers.Category;
import com.example.pickyourfood.food.Answers.Hunger;
import com.example.pickyourfood.food.Answers.Mood;
import com.example.pickyourfood.food.Answers.Situation;
import com.example.pickyourfood.food.Answers.Taste;
import com.example.pickyourfood.food.Recommender.Recommendation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RecommenderTest {

	// only mood and taste vary; situation/hunger never match answers() below
	private static Food food(String id, Category category, Mood mood, Taste taste) {
		return new Food(id, id, id, category, Set.of(Situation.GROUP), Set.of(mood), Set.of(Hunger.LIGHT), Set.of(taste));
	}

	private static Answers answers(Category category) {
		return new Answers(Situation.ALONE, Mood.DOWN, category, Hunger.STARVING, Taste.SPICY);
	}

	@Test
	void keepsOnlyTheChosenCategory() {
		List<Food> foods = List.of(
				food("k1", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k2", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k3", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("c1", Category.CHINESE, Mood.DOWN, Taste.SPICY));

		Recommendation result = new Recommender(foods, new Random(1)).recommend(answers(Category.KOREAN));

		assertThat(result.best().category()).isEqualTo(Category.KOREAN);
		assertThat(result.alternatives()).extracting(Food::category).containsOnly(Category.KOREAN);
	}

	@Test
	void anyCategoryConsidersEveryFood() {
		List<Food> foods = List.of(
				food("k1", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("k2", Category.KOREAN, Mood.NORMAL, Taste.MILD),
				food("c1", Category.CHINESE, Mood.DOWN, Taste.SPICY));

		Recommendation result = new Recommender(foods, new Random(1)).recommend(answers(Category.ANY));

		assertThat(result.best().id()).isEqualTo("c1");
	}

	@Test
	void moodOutweighsTaste() {
		List<Food> foods = List.of(
				food("taste-only", Category.KOREAN, Mood.NORMAL, Taste.SPICY),
				food("mood-only", Category.KOREAN, Mood.DOWN, Taste.MILD),
				food("neither", Category.KOREAN, Mood.NORMAL, Taste.MILD));

		for (int seed = 0; seed < 20; seed++) {
			Recommendation result = new Recommender(foods, new Random(seed)).recommend(answers(Category.KOREAN));
			assertThat(result.best().id()).isEqualTo("mood-only");
			assertThat(result.alternatives()).extracting(Food::id).containsExactly("taste-only", "neither");
		}
	}

	@Test
	void tiesAreBrokenRandomly() {
		List<Food> foods = List.of(
				food("a", Category.KOREAN, Mood.DOWN, Taste.SPICY),
				food("b", Category.KOREAN, Mood.DOWN, Taste.SPICY),
				food("c", Category.KOREAN, Mood.DOWN, Taste.SPICY));

		Set<String> bests = new HashSet<>();
		for (int seed = 0; seed < 30; seed++) {
			bests.add(new Recommender(foods, new Random(seed)).recommend(answers(Category.KOREAN)).best().id());
		}

		assertThat(bests).hasSizeGreaterThan(1);
	}

	@Test
	void everyAnswerCombinationGivesThreeDistinctFoods() {
		Recommender recommender = new Recommender(new FoodCatalog(new JsonMapper()).all(), new Random(7));
		List<Answers> all = new ArrayList<>();
		for (Situation s : Situation.values())
			for (Mood m : Mood.values())
				for (Category c : Category.values())
					for (Hunger h : Hunger.values())
						for (Taste t : Taste.values())
							all.add(new Answers(s, m, c, h, t));

		assertThat(all).allSatisfy(answers -> {
			Recommendation result = recommender.recommend(answers);
			List<String> ids = new ArrayList<>();
			ids.add(result.best().id());
			result.alternatives().forEach(food -> ids.add(food.id()));
			assertThat(ids).as(answers.toString()).hasSize(3).doesNotHaveDuplicates();
		});
	}

	@Test
	void randomPicksFromTheCatalog() {
		List<Food> foods = List.of(food("only", Category.KOREAN, Mood.DOWN, Taste.SPICY));

		assertThat(new Recommender(foods, new Random()).random().id()).isEqualTo("only");
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*RecommenderTest'`
Expected: FAIL — 컴파일 에러 (`cannot find symbol: class Recommender`)

- [ ] **Step 3: `Recommender.java` 작성**

```java
package com.example.pickyourfood.food;

import com.example.pickyourfood.food.Answers.Category;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class Recommender {

	static final int MOOD_WEIGHT = 4;
	static final int TASTE_WEIGHT = 3;
	static final int HUNGER_WEIGHT = 2;
	static final int SITUATION_WEIGHT = 2;

	private final List<Food> foods;
	private final Random random;

	@Autowired
	public Recommender(FoodCatalog catalog) {
		this(catalog.all(), new Random());
	}

	Recommender(List<Food> foods, Random random) {
		this.foods = foods;
		this.random = random;
	}

	public Food random() {
		return foods.get(random.nextInt(foods.size()));
	}

	public Recommendation recommend(Answers answers) {
		List<Food> candidates = new ArrayList<>(foods.stream()
				.filter(food -> answers.category() == Category.ANY || food.category() == answers.category())
				.toList());
		// shuffle first: the stable sort then keeps equal scores in random order
		Collections.shuffle(candidates, random);
		candidates.sort(Comparator.comparingInt((Food food) -> score(food, answers)).reversed());
		List<Food> top = candidates.subList(0, Math.min(3, candidates.size()));
		return new Recommendation(top.get(0), List.copyOf(top.subList(1, top.size())));
	}

	static int score(Food food, Answers answers) {
		int score = 0;
		if (food.moods().contains(answers.mood())) score += MOOD_WEIGHT;
		if (food.tastes().contains(answers.taste())) score += TASTE_WEIGHT;
		if (food.hunger().contains(answers.hunger())) score += HUNGER_WEIGHT;
		if (food.situations().contains(answers.situation())) score += SITUATION_WEIGHT;
		return score;
	}

	public record Recommendation(Food best, List<Food> alternatives) {
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL. `RecommenderTest` 6개 포함 전체 통과.

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/food/Recommender.java src/test/java/com/example/pickyourfood/food/RecommenderTest.java
git commit -m "feat: add weighted food recommender

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: REST API (`FoodController`)

**Files:**
- Create: `src/main/java/com/example/pickyourfood/food/FoodController.java`
- Test: `src/test/java/com/example/pickyourfood/food/FoodControllerTest.java`

**Interfaces:**
- Consumes: `Recommender.random()`, `Recommender.recommend(Answers)`, `Recommender.Recommendation` (Task 2)
- Produces (HTTP, 프론트엔드가 사용):
  - `GET /api/foods/random` → 200 `Food` JSON
  - `POST /api/recommendations` 본문 `{"situation","mood","category","hunger","taste"}` → 200 `{"best": Food, "alternatives": [Food, Food]}`; 누락/잘못된 값/빈 본문/깨진 JSON → 400

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/pickyourfood/food/FoodControllerTest.java`:

```java
package com.example.pickyourfood.food;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class FoodControllerTest {

	@Autowired
	MockMvc mvc;

	private ResultActions postRecommendation(String body) throws Exception {
		return mvc.perform(post("/api/recommendations").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	@Test
	void randomReturnsAFood() throws Exception {
		mvc.perform(get("/api/foods/random"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").isString());
	}

	@Test
	void recommendationReturnsBestAndTwoAlternatives() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.best.name").isString())
				.andExpect(jsonPath("$.alternatives.length()").value(2));
	}

	@Test
	void missingAnswerIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"ALONE","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void unknownOrLowercaseValueIsBadRequest() throws Exception {
		postRecommendation("""
				{"situation":"alone","mood":"DOWN","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
		postRecommendation("""
				{"situation":"ALONE","mood":"HAPPY","category":"ANY","hunger":"MODERATE","taste":"SPICY"}""")
				.andExpect(status().isBadRequest());
	}

	@Test
	void emptyOrMalformedBodyIsBadRequest() throws Exception {
		postRecommendation("").andExpect(status().isBadRequest());
		postRecommendation("{oops").andExpect(status().isBadRequest());
	}
}
```

- [ ] **Step 2: 실패 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test --tests '*FoodControllerTest'`
Expected: FAIL — 5개 모두 404로 실패 (컨트롤러 없음)

- [ ] **Step 3: `FoodController.java` 작성**

필드 누락은 `Answers` 생성자의 `requireNonNull`이, 잘못된 enum 값과 깨진 JSON은 Jackson이 막는다. 둘 다 Spring이 400으로 바꾼다.

```java
package com.example.pickyourfood.food;

import com.example.pickyourfood.food.Recommender.Recommendation;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
class FoodController {

	private final Recommender recommender;

	FoodController(Recommender recommender) {
		this.recommender = recommender;
	}

	@GetMapping("/foods/random")
	Food random() {
		return recommender.random();
	}

	@PostMapping("/recommendations")
	Recommendation recommend(@RequestBody Answers answers) {
		return recommender.recommend(answers);
	}
}
```

- [ ] **Step 4: 통과 확인**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew test`
Expected: BUILD SUCCESSFUL. 전체 15개 통과 (Catalog 3 + Recommender 6 + Controller 5 + 기존 1).

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/pickyourfood/food/FoodController.java src/test/java/com/example/pickyourfood/food/FoodControllerTest.java
git commit -m "feat: expose random and recommendation endpoints

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: 프론트엔드 기반 + 모드 선택 화면

**Files:**
- Create: `frontend/` (create-vite `react-ts` 템플릿)
- Modify: `frontend/vite.config.ts`, `frontend/index.html`, `frontend/src/main.tsx`, `frontend/src/index.css`, `frontend/src/App.tsx`
- Delete: `frontend/src/App.css`, `frontend/src/assets/`, `frontend/public/*`
- Create: `frontend/src/api.ts`, `frontend/src/questions.ts`, `frontend/src/ModeSelect.tsx`

**Interfaces:**
- Consumes: HTTP API (Task 3)
- Produces:
  - `api.ts`: `type Food = { id: string; name: string; description: string }`, `type Recommendation = { best: Food; alternatives: Food[] }`, `fetchRandom(): Promise<Food>`, `fetchRecommendation(answers: Answers): Promise<Recommendation>` — HTTP 오류 시 reject
  - `questions.ts`: `type Answers = { situation; mood; category; hunger; taste }` (모두 `string`), `type Question = { key: keyof Answers; title: string; options: { value: string; label: string }[] }`, `const QUESTIONS: Question[]` (스펙 순서)
  - `ModeSelect.tsx`: `default function ModeSelect(props: { onRandom: () => void; onSurvey: () => void })`
  - Tailwind 테마 색 `accent`, `accent-soft` (`text-accent`, `bg-accent-soft` 등으로 사용)

TS 설정 주의: 템플릿은 `verbatimModuleSyntax`와 `erasableSyntaxOnly`를 켠다 → 타입은 `import type`으로, TS `enum` 금지(문자열 유니온/상수 사용).

- [ ] **Step 1: 스캐폴딩과 의존성 설치**

```bash
npm create vite@latest frontend -- --template react-ts --no-interactive
cd frontend
npm install
npm install tailwindcss @tailwindcss/vite motion @phosphor-icons/react
rm -rf src/App.css src/assets public/*
cd ..
```

`frontend/.gitignore`가 생성돼 `node_modules`와 `dist`를 제외하는지 확인: `grep -E 'node_modules|dist' frontend/.gitignore`

- [ ] **Step 2: `frontend/vite.config.ts`**

```ts
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
})
```

- [ ] **Step 3: `frontend/index.html`**

```html
<!doctype html>
<html lang="ko">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link rel="stylesheet" href="https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/dist/web/variable/pretendardvariable-dynamic-subset.min.css" />
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Geist:wght@400..700&display=swap" />
    <title>오늘 뭐 먹지</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

- [ ] **Step 4: `frontend/src/index.css`**

Geist에는 한글 글리프가 없으므로 한글은 자동으로 Pretendard로 표시된다.

```css
@import 'tailwindcss';

@theme {
  --font-sans: 'Geist', 'Pretendard Variable', Pretendard, sans-serif;
  --color-accent: oklch(0.6 0.15 32);
  --color-accent-soft: oklch(0.95 0.03 32);
}

body {
  @apply bg-zinc-50 font-sans text-zinc-900 antialiased;
}
```

- [ ] **Step 5: `frontend/src/main.tsx`**

```tsx
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
```

- [ ] **Step 6: `frontend/src/questions.ts`**

```ts
export type Answers = {
  situation: string
  mood: string
  category: string
  hunger: string
  taste: string
}

export type Question = {
  key: keyof Answers
  title: string
  options: { value: string; label: string }[]
}

export const QUESTIONS: Question[] = [
  {
    key: 'situation',
    title: '누구와 먹나요?',
    options: [
      { value: 'ALONE', label: '혼밥' },
      { value: 'FRIENDS', label: '친구' },
      { value: 'DATE', label: '연인' },
      { value: 'GROUP', label: '가족·회식' },
    ],
  },
  {
    key: 'mood',
    title: '지금 기분은 어때요?',
    options: [
      { value: 'EXCITED', label: '신남' },
      { value: 'NORMAL', label: '평범' },
      { value: 'DOWN', label: '우울·지침' },
      { value: 'STRESSED', label: '스트레스' },
    ],
  },
  {
    key: 'category',
    title: '어떤 종류가 당기나요?',
    options: [
      { value: 'KOREAN', label: '한식' },
      { value: 'CHINESE', label: '중식' },
      { value: 'JAPANESE', label: '일식' },
      { value: 'WESTERN', label: '양식' },
      { value: 'SNACK', label: '분식' },
      { value: 'ANY', label: '상관없음' },
    ],
  },
  {
    key: 'hunger',
    title: '얼마나 배고파요?',
    options: [
      { value: 'LIGHT', label: '살짝 출출' },
      { value: 'MODERATE', label: '적당히' },
      { value: 'STARVING', label: '매우 배고픔' },
    ],
  },
  {
    key: 'taste',
    title: '어떤 맛이 좋아요?',
    options: [
      { value: 'SPICY', label: '매콤' },
      { value: 'MILD', label: '담백' },
      { value: 'RICH', label: '기름진' },
      { value: 'SWEET_SOUR', label: '달달·새콤' },
    ],
  },
]
```

- [ ] **Step 7: `frontend/src/api.ts`**

```ts
import type { Answers } from './questions.ts'

export type Food = {
  id: string
  name: string
  description: string
}

export type Recommendation = {
  best: Food
  alternatives: Food[]
}

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url, init)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json() as Promise<T>
}

export function fetchRandom(): Promise<Food> {
  return request('/api/foods/random')
}

export function fetchRecommendation(answers: Answers): Promise<Recommendation> {
  return request('/api/recommendations', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(answers),
  })
}
```

- [ ] **Step 8: `frontend/src/ModeSelect.tsx`**

```tsx
import { ArrowRight, ListChecks, Shuffle } from '@phosphor-icons/react'
import type { Icon } from '@phosphor-icons/react'

type Props = {
  onRandom: () => void
  onSurvey: () => void
}

export default function ModeSelect({ onRandom, onSurvey }: Props) {
  return (
    <div className="grid w-full gap-12 md:grid-cols-[1.1fr_1fr] md:items-end">
      <div>
        <p className="text-sm font-medium text-accent">Pick your food</p>
        <h1 className="mt-4 text-4xl font-semibold leading-none tracking-tighter md:text-6xl">
          오늘 뭐 먹지?
        </h1>
        <p className="mt-6 max-w-[40ch] text-base leading-relaxed text-zinc-600">
          고민할 시간에 먹자. 바로 하나 뽑거나, 다섯 가지 질문에 답하고 딱 맞는 메뉴를 받아보세요.
        </p>
      </div>
      <div className="grid gap-3">
        <ModeButton icon={Shuffle} title="랜덤으로 뽑기" hint="아무거나 하나, 지금 바로" onClick={onRandom} />
        <ModeButton icon={ListChecks} title="5가지 질문으로 찾기" hint="상황·기분·종류·배고픔·맛" onClick={onSurvey} />
      </div>
    </div>
  )
}

type ModeButtonProps = {
  icon: Icon
  title: string
  hint: string
  onClick: () => void
}

function ModeButton({ icon: IconComponent, title, hint, onClick }: ModeButtonProps) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="group flex items-center gap-4 rounded-3xl border border-zinc-200 bg-white p-6 text-left transition duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] hover:border-accent active:scale-[0.98]"
    >
      <IconComponent size={28} weight="duotone" className="text-accent" />
      <span className="flex-1">
        <span className="block text-lg font-semibold tracking-tight">{title}</span>
        <span className="block text-sm text-zinc-500">{hint}</span>
      </span>
      <ArrowRight size={20} className="text-zinc-400 transition group-hover:translate-x-1 group-hover:text-accent" />
    </button>
  )
}
```

- [ ] **Step 9: 임시 `frontend/src/App.tsx`**

Task 5에서 전체로 교체한다. 지금은 모드 선택 화면만 띄운다.

```tsx
import ModeSelect from './ModeSelect.tsx'

export default function App() {
  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 py-8 md:px-12">
      <ModeSelect onRandom={() => {}} onSurvey={() => {}} />
    </main>
  )
}
```

- [ ] **Step 10: 빌드 확인**

Run: `cd frontend && npm run build`
Expected: `tsc -b`와 `vite build` 모두 에러 없이 `✓ built` 출력.

- [ ] **Step 11: 화면 확인**

Run: `cd frontend && npm run dev` → 브라우저에서 `http://localhost:5173`
Expected: 왼쪽에 "오늘 뭐 먹지?" 제목, 오른쪽에 두 버튼. 창 폭을 768px 미만으로 줄이면 한 열로 쌓이고 가로 스크롤이 없다. 한글은 Pretendard, "Pick your food"는 Geist로 보인다. 확인 후 dev 서버 종료.

- [ ] **Step 12: 커밋**

```bash
git add frontend
git status --short frontend   # node_modules, dist 가 없어야 한다
git commit -m "feat: scaffold frontend with mode select screen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: 질문 화면, 결과 화면, 전체 흐름

**Files:**
- Create: `frontend/src/Questionnaire.tsx`
- Create: `frontend/src/ResultView.tsx`
- Modify: `frontend/src/App.tsx` (전체 교체)

**Interfaces:**
- Consumes: `fetchRandom`, `fetchRecommendation`, `Food`, `Recommendation` (`api.ts`), `QUESTIONS`, `Answers`, `Question` (`questions.ts`), `ModeSelect` (Task 4)
- Produces:
  - `Questionnaire.tsx`: `default function Questionnaire(props: { onComplete: (answers: Answers) => void; onExit: () => void })`
  - `ResultView.tsx`: `default function ResultView(props: { title: string; status: 'loading' | 'error' | 'done'; best: Food | undefined; alternatives: Food[]; roll: boolean; againLabel: string; onAgain: () => void; onRetry: () => void; onHome: () => void })`

- [ ] **Step 1: `frontend/src/Questionnaire.tsx`**

`useIsPresent`는 화면이 사라지는 애니메이션 중에 `false`가 된다. 이것으로 Review Focus 1번(빠른 두 번 탭으로 질문 건너뛰기)을 막는다.

```tsx
import { ArrowLeft } from '@phosphor-icons/react'
import { AnimatePresence, motion, useIsPresent } from 'motion/react'
import { useState } from 'react'
import { QUESTIONS } from './questions.ts'
import type { Answers, Question } from './questions.ts'

type Props = {
  onComplete: (answers: Answers) => void
  onExit: () => void
}

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function Questionnaire({ onComplete, onExit }: Props) {
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<Partial<Answers>>({})
  const question = QUESTIONS[step]

  function choose(value: string) {
    const next = { ...answers, [question.key]: value }
    setAnswers(next)
    if (step === QUESTIONS.length - 1) onComplete(next as Answers)
    else setStep(step + 1)
  }

  return (
    <div className="grid w-full gap-10 md:grid-cols-[1fr_1.2fr]">
      <div>
        <button
          type="button"
          onClick={() => (step === 0 ? onExit() : setStep(step - 1))}
          className="flex items-center gap-2 text-sm text-zinc-500 transition hover:text-zinc-900 active:scale-[0.98]"
        >
          <ArrowLeft size={16} /> 뒤로
        </button>
        <p className="mt-8 font-mono text-sm text-accent">
          {step + 1} / {QUESTIONS.length}
        </p>
        <div className="mt-3 h-1 w-40 overflow-hidden rounded-full bg-zinc-200">
          <motion.div
            className="h-full origin-left bg-accent"
            animate={{ scaleX: (step + 1) / QUESTIONS.length }}
            transition={spring}
          />
        </div>
      </div>
      <AnimatePresence mode="wait">
        <motion.div
          key={step}
          initial={{ opacity: 0, x: 40 }}
          animate={{ opacity: 1, x: 0 }}
          exit={{ opacity: 0, x: -40 }}
          transition={spring}
        >
          <QuestionStep question={question} selected={answers[question.key]} onChoose={choose} />
        </motion.div>
      </AnimatePresence>
    </div>
  )
}

type QuestionStepProps = {
  question: Question
  selected: string | undefined
  onChoose: (value: string) => void
}

function QuestionStep({ question, selected, onChoose }: QuestionStepProps) {
  // false while this screen animates out: a second tap here must not answer the next question
  const isPresent = useIsPresent()

  return (
    <>
      <h2 className="text-3xl font-semibold tracking-tight md:text-4xl">{question.title}</h2>
      <div className="mt-8 grid grid-cols-2 gap-3">
        {question.options.map((option) => (
          <button
            key={option.value}
            type="button"
            onClick={() => isPresent && onChoose(option.value)}
            className={`rounded-2xl border p-5 text-left text-lg font-medium transition duration-300 ease-[cubic-bezier(0.16,1,0.3,1)] hover:border-accent active:scale-[0.98] ${
              selected === option.value ? 'border-accent bg-accent-soft' : 'border-zinc-200 bg-white'
            }`}
          >
            {option.label}
          </button>
        ))}
      </div>
    </>
  )
}
```

- [ ] **Step 2: `frontend/src/ResultView.tsx`**

```tsx
import { ArrowCounterClockwise, House } from '@phosphor-icons/react'
import { motion } from 'motion/react'
import { useEffect, useState } from 'react'
import type { Food } from './api.ts'

type Props = {
  title: string
  status: 'loading' | 'error' | 'done'
  best: Food | undefined
  alternatives: Food[]
  roll: boolean
  againLabel: string
  onAgain: () => void
  onRetry: () => void
  onHome: () => void
}

// only used for the slot-machine effect before the random result settles
const ROLL_NAMES = ['김치찌개', '짬뽕', '초밥', '파스타', '떡볶이', '비빔밥', '마라탕', '돈카츠', '햄버거', '순대국']

const spring = { type: 'spring', stiffness: 100, damping: 20 } as const

export default function ResultView({ title, status, best, alternatives, roll, againLabel, onAgain, onRetry, onHome }: Props) {
  return (
    <div className="grid w-full gap-10 md:grid-cols-[1.4fr_1fr] md:items-start">
      <div>
        <p className="text-sm font-medium text-accent">{title}</p>
        {status === 'loading' && <BestSkeleton />}
        {status === 'error' && (
          <div className="mt-6 rounded-3xl border border-zinc-200 bg-white p-8">
            <p className="text-lg font-semibold">메뉴를 불러오지 못했어요.</p>
            <p className="mt-2 text-sm text-zinc-500">서버가 켜져 있는지 확인한 뒤 다시 시도해 주세요.</p>
            <button
              type="button"
              onClick={onRetry}
              className="mt-6 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98]"
            >
              다시 시도
            </button>
          </div>
        )}
        {status === 'done' && best && <BestFood food={best} roll={roll} />}
        {status !== 'error' && (
          <div className="mt-8 flex flex-wrap gap-3">
            <button
              type="button"
              onClick={onAgain}
              disabled={status === 'loading'}
              className="flex items-center gap-2 rounded-full bg-zinc-900 px-5 py-3 text-sm font-medium text-white transition active:scale-[0.98] disabled:opacity-40"
            >
              <ArrowCounterClockwise size={16} /> {againLabel}
            </button>
            <button
              type="button"
              onClick={onHome}
              className="flex items-center gap-2 rounded-full border border-zinc-300 px-5 py-3 text-sm font-medium transition hover:border-zinc-900 active:scale-[0.98]"
            >
              <House size={16} /> 처음으로
            </button>
          </div>
        )}
      </div>
      {status === 'done' && alternatives.length > 0 && (
        <div className="md:pt-24">
          <p className="text-sm text-zinc-500">이것도 괜찮아요</p>
          <ul className="mt-4 divide-y divide-zinc-200 border-y border-zinc-200">
            {alternatives.map((food, index) => (
              <motion.li
                key={food.id}
                initial={{ opacity: 0, y: 16 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ ...spring, delay: 0.4 + index * 0.15 }}
                className="py-5"
              >
                <p className="text-xl font-semibold tracking-tight">{food.name}</p>
                <p className="mt-1 text-sm leading-relaxed text-zinc-500">{food.description}</p>
              </motion.li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function BestFood({ food, roll }: { food: Food; roll: boolean }) {
  const shown = useRoll(food.name, roll)

  return (
    <motion.div initial={{ opacity: 0, y: 24 }} animate={{ opacity: 1, y: 0 }} transition={spring}>
      <h1 className="mt-4 text-5xl font-semibold leading-none tracking-tighter md:text-7xl">{shown}</h1>
      <p className="mt-6 max-w-[45ch] text-base leading-relaxed text-zinc-600">
        {shown === food.name ? food.description : ' '}
      </p>
    </motion.div>
  )
}

// cycles through ROLL_NAMES for about a second, then settles on the final name
function useRoll(finalName: string, roll: boolean) {
  const [shown, setShown] = useState(roll ? ROLL_NAMES[0] : finalName)

  useEffect(() => {
    if (!roll) return
    let tick = 0
    const timer = setInterval(() => {
      tick += 1
      if (tick >= 14) {
        setShown(finalName)
        clearInterval(timer)
      } else {
        setShown(ROLL_NAMES[tick % ROLL_NAMES.length])
      }
    }, 70)
    return () => clearInterval(timer)
  }, [finalName, roll])

  return shown
}

function BestSkeleton() {
  return (
    <div className="mt-4 animate-pulse">
      <div className="h-12 w-56 rounded-2xl bg-zinc-200 md:h-[4.5rem] md:w-80" />
      <div className="mt-6 h-4 w-72 max-w-full rounded-full bg-zinc-200" />
      <div className="mt-3 h-4 w-52 rounded-full bg-zinc-200" />
    </div>
  )
}
```

- [ ] **Step 3: `frontend/src/App.tsx` 전체 교체**

요청마다 id를 매기고 최신 id와 다른 응답은 버린다 (Review Focus 5번). 결과 화면의 key에 `attempt`를 넣어 "다시 뽑기"마다 롤 애니메이션이 새로 돈다.

```tsx
import { AnimatePresence, motion } from 'motion/react'
import { useRef, useState } from 'react'
import { fetchRandom, fetchRecommendation } from './api.ts'
import type { Food, Recommendation } from './api.ts'
import ModeSelect from './ModeSelect.tsx'
import Questionnaire from './Questionnaire.tsx'
import type { Answers } from './questions.ts'
import ResultView from './ResultView.tsx'

type Screen = 'home' | 'random' | 'survey' | 'recommend'

type Result =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'done'; best: Food; alternatives: Food[] }

export default function App() {
  const [screen, setScreen] = useState<Screen>('home')
  const [result, setResult] = useState<Result>({ status: 'loading' })
  const [attempt, setAttempt] = useState(0)
  const latest = useRef(0)
  const lastRequest = useRef<() => void>(() => {})

  function run(next: Screen, fetcher: () => Promise<Recommendation>) {
    // responses from an older request (or after going home) are ignored
    const id = ++latest.current
    lastRequest.current = () => run(next, fetcher)
    setScreen(next)
    setAttempt(id)
    setResult({ status: 'loading' })
    fetcher().then(
      (data) => id === latest.current && setResult({ status: 'done', ...data }),
      () => id === latest.current && setResult({ status: 'error' }),
    )
  }

  function drawRandom() {
    run('random', () => fetchRandom().then((food) => ({ best: food, alternatives: [] })))
  }

  function recommend(answers: Answers) {
    run('recommend', () => fetchRecommendation(answers))
  }

  function goHome() {
    latest.current++
    setScreen('home')
  }

  return (
    <main className="mx-auto flex min-h-[100dvh] w-full max-w-6xl items-center px-4 py-8 md:px-12">
      <AnimatePresence mode="wait">
        <motion.div
          key={screen === 'home' || screen === 'survey' ? screen : `${screen}-${attempt}`}
          className="w-full"
          initial={{ opacity: 0 }}
          animate={{ opacity: 1 }}
          exit={{ opacity: 0 }}
          transition={{ duration: 0.2 }}
        >
          {screen === 'home' && <ModeSelect onRandom={drawRandom} onSurvey={() => setScreen('survey')} />}
          {screen === 'survey' && <Questionnaire onComplete={recommend} onExit={goHome} />}
          {(screen === 'random' || screen === 'recommend') && (
            <ResultView
              title={screen === 'random' ? '오늘의 랜덤 메뉴' : '당신에게 딱 맞는 메뉴'}
              status={result.status}
              best={result.status === 'done' ? result.best : undefined}
              alternatives={result.status === 'done' ? result.alternatives : []}
              roll={screen === 'random'}
              againLabel={screen === 'random' ? '다시 뽑기' : '다시 하기'}
              onAgain={screen === 'random' ? drawRandom : () => setScreen('survey')}
              onRetry={() => lastRequest.current()}
              onHome={goHome}
            />
          )}
        </motion.div>
      </AnimatePresence>
    </main>
  )
}
```

- [ ] **Step 4: 빌드 확인**

Run: `cd frontend && npm run build`
Expected: 에러 없이 `✓ built`.

- [ ] **Step 5: 전체 흐름 수동 확인**

두 터미널에서:
```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 17) && ./gradlew bootRun
cd frontend && npm run dev
```
`http://localhost:5173`에서 아래를 모두 확인:

1. **랜덤:** "랜덤으로 뽑기" → 스켈레톤이 잠깐 보인 뒤 이름이 약 1초간 바뀌다 멈추고 설명이 나온다. "다시 뽑기"를 누를 때마다 롤이 다시 돈다. "처음으로" → 모드 선택.
2. **추론:** "5가지 질문으로 찾기" → 1/5 "누구와 먹나요?"부터 5/5 "어떤 맛이 좋아요?"까지 순서가 스펙과 같다. 진행 막대가 늘어난다. 마지막 답 후 1순위가 크게, 오른쪽(모바일에선 아래)에 "이것도 괜찮아요" 2개가 차례로 나타난다.
3. **뒤로:** 3/5에서 "뒤로" → 2/5로 가고 이전에 고른 답이 강조돼 있다. 1/5에서 "뒤로" → 모드 선택.
4. **빠른 두 번 탭 (Review Focus 1):** 1/5에서 선택지를 빠르게 두 번 누른다 → 2/5에 머물러야 한다 (3/5로 건너뛰면 실패).
5. **종류 필터:** 종류 "일식"으로 끝까지 → 결과 3개가 모두 일식 메뉴.
6. **서버 꺼짐 (Review Focus 5):** `bootRun`을 멈추고 "랜덤으로 뽑기" → 스켈레톤 후 "메뉴를 불러오지 못했어요." 카드. 서버를 다시 켜고 "다시 시도" → 결과가 나온다.
7. **모바일 폭:** 창 폭 375px → 모든 화면이 한 열, 가로 스크롤 없음.

- [ ] **Step 6: 커밋**

```bash
git add frontend/src
git commit -m "feat: add questionnaire, result screens and app flow

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
