# pick-your-food

Spring Boot 4.1.1 · Java 17 · Gradle

## Commands
```bash
./gradlew bootRun   # run the app
./gradlew test      # run the tests (JUnit 5)
./gradlew build     # compile, test and package
```

## Layout
Code lives in the package `com.example.pickyourfood` (`src/main/java`, tests in `src/test/java`).

Always package by feature, never by layer (no `controller/`, `service/`, `components/`, `api/` folders).
A new feature gets its own folder holding its controller, logic, types and API calls; its tests mirror that path.
- Backend: `com.example.pickyourfood.<feature>` — `food` (catalog + tags), `random`, `recommendation`.
- Frontend: `frontend/src/features/<feature>/` — `mode-select`, `random`, `recommendation`.
- Only code used by two or more features goes in `food` (backend) or `frontend/src/shared/`. Features must not import from each other.


## Graphify 
원본 파일을 넓게 검색하기 전에 Graphify 그래프를 먼저 조회하세요.
이번 문제와 관련된 파일, 함수, 호출 관계를 좁힌 뒤 필요한 원본 파일만 읽어주세요.
그래프에서 찾은 연결 관계와 실제 코드가 일치하는지도 확인하세요.

