# pick-your-food

오늘 뭐 먹을지 골라 주고, 근처 맛집과 데이트 코스를 찾아 주는 앱.

## 로컬 개발

```bash
./gradlew bootRun              # 백엔드 (localhost:8080, H2 파일 DB ./data)
cd frontend && npm run dev     # 프론트엔드 (localhost:5173)
```

키: `KAKAO_REST_KEY`(필수, 카카오 로그인·장소 검색), `KAKAO_CLIENT_SECRET`(클라이언트 시크릿을 켰다면), `GOOGLE_PLACES_KEY`(평점·리뷰, 선택).

## 배포 (Render + Neon)

1. **Neon**: [neon.tech](https://neon.tech)에서 프로젝트를 만든다(리전 AWS Asia Pacific (Singapore)). Connection details에서 host, database, user, password를 확인한다.
2. **Render**: [dashboard.render.com](https://dashboard.render.com) → New → Blueprint → 이 저장소를 연결한다. `render.yaml`대로 웹 서비스가 만들어진다.
3. 비밀 값을 입력한다.
   - `SPRING_DATASOURCE_URL`: `jdbc:postgresql://<host>/<database>?sslmode=require`
   - `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`: Neon의 user, password
   - `KAKAO_REST_KEY`, `KAKAO_CLIENT_SECRET`, `GOOGLE_PLACES_KEY`: 로컬과 같은 값
4. **카카오 개발자 콘솔** → 카카오 로그인 → Redirect URI에 `https://<서비스 이름>.onrender.com/login/oauth2/code/kakao`를 추가한다.
5. 이후 `main`에 푸시하면 자동으로 다시 배포된다. 스키마는 앱이 시작할 때 Flyway가 만든다.

무료 요금제는 15분 동안 요청이 없으면 잠들어서, 깨어나는 첫 요청이 30초~1분 걸릴 수 있다.
