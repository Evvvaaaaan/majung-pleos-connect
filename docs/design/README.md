# 디자인 시안 — 지도 중심 화면, 세 가지 방향

화면을 "지도 하나, 할 일 하나"로 단순화하면서 세 방향을 같은 화면 구성으로 시안화해 비교했습니다(Hallmark 디자인 스킬, 테마 Wayfind / Rim / Dose). 근거는 Pleos Connect v2.1.0 런처 캡처(낮: 밝은 바탕, 검은 판, 각진 모서리, 얇은 선)와 현대 공식 사이트 CSS의 브랜드 블루(#002C5F, 색상각 258)입니다.

- `design-directions.html` — 시안 페이지 원본 (브라우저로 열면 각 프레임을 크게 볼 수 있음)
- `direction-a-signboard.png` — A. 사인보드 (채택안. 이후 Pleos 런처의 결에 맞춰 라운드 12dp·얇은 선·시스템 서체로 다듬음)
- `direction-b-instrument.png` — B. 계기판
- `direction-c-plastic.png` — C. 반투명 플라스틱
- `comparison.png`, `recommendation.png` — 비교표와 추천

구현된 토큰은 `app/src/main/kotlin/io/majung/pleos/ui/MajungTheme.kt`(낮/밤)와 `web-companion/index.html`의 `:root`에 있습니다.
