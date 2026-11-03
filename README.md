# 치지직 만들기 — 3단계: 인증·인가와 댓글 (+ 1단계 녹화 방송, 2단계 라이브)

치지직 같은 방송 플랫폼을 단계별로 만드는 프로젝트입니다.

- **1단계 (유지)**: 녹화 방송(다시보기)을 등록하고 목록·상세 화면에서 재생
- **2단계 (유지)**: 휴대폰 카메라·마이크(컴퓨터는 웹캠·화면 공유)로 라이브 방송을 시작·종료하고 다른 화면에서 시청. 방송 중/종료 상태 표시
- **3단계 (이번)**: **회원가입·로그인·로그아웃**, 방송 **소유자** 구분과 **서버 권한 검사**(등록·수정·삭제·송출 제어), 방송별 **댓글**(작성·목록·작성자만 수정·삭제)

## 기술 스택

| 구분 | 사용 |
| --- | --- |
| 서버 | Java 21+, Spring Boot 4.1 (Spring MVC, Spring WebSocket, **Spring Security**) |
| 인증 | 세션 로그인 (`JSESSIONID` 쿠키), 비밀번호 **BCrypt** 해시 저장, CSRF 토큰(`XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더) |
| 빌드 | Gradle Wrapper (`gradlew`, 별도 설치 불필요) |
| 저장소 | Spring Data JPA + H2 **파일 DB** (`data/vod.mv.db`) — 회원·방송·라이브·댓글 |
| 업로드 | Spring Multipart → 로컬 디스크 (`uploads/`) |
| 라이브 송출 | 브라우저 `getUserMedia`(카메라·마이크), `getDisplayMedia`(컴퓨터 화면 공유) + **WebRTC** (영상은 P2P로 전송) |
| 라이브 시그널링 | WebSocket `/ws/lives/{id}` (offer / answer / ICE 중계) |
| 화면 | 정적 HTML + 바닐라 JS (`src/main/resources/static`) |

## 인증·인가와 공개 범위

### 누가 무엇을 할 수 있나

| 기능 | 비로그인 | 로그인 사용자 | 방송 소유자 / 댓글 작성자 |
| --- | --- | --- | --- |
| 방송 목록·상세 조회, 녹화 방송 시청, 라이브 시청 | ✅ 공개 | ✅ | ✅ |
| 댓글 목록 조회 | ✅ 공개 | ✅ | ✅ |
| 녹화 방송 등록 (파일·URL) | ❌ 401 | ✅ (등록자가 소유자) | ✅ |
| 녹화 방송 제목·설명 수정, 삭제 | ❌ 401 | ❌ 403 | ✅ 소유자만 |
| 라이브 시작 | ❌ 401 | ✅ (시작한 사람이 소유자) | ✅ |
| 라이브 송출 연결(WebSocket publish), 종료, 제목 수정, 녹화본 등록, 종료된 기록 삭제 | ❌ 401 / 거절 | ❌ 403 / 거절 | ✅ 소유자만 |
| 댓글 작성 | ❌ 401 | ✅ (작성자 = 로그인 사용자) | ✅ |
| 댓글 수정·삭제 | ❌ 401 | ❌ 403 (방송 소유자여도 남의 댓글은 불가) | ✅ 작성자만 |

- **시청·목록 조회는 로그인 없이 공개**입니다. 치지직처럼 누구나 방송을 둘러보고 볼 수 있고, 참여(등록·송출·댓글)할 때만 로그인합니다.
- 1·2단계에서 로그인 없이 등록된 방송·라이브 기록은 소유자가 없으므로(**등록자 없음**으로 표시) 누구도 수정·삭제할 수 없습니다. 시청·댓글은 그대로 가능합니다.

### 서버에서 검사하는 위치 (화면 버튼 숨김에 의존하지 않음)

화면은 소유자·작성자에게만 수정·삭제·종료 버튼을 보여 주지만, 이것은 편의 기능입니다. API를 직접 호출해도 서버가 같은 규칙으로 거절합니다.

1. **로그인 여부** — `config/SecurityConfig.java`: `GET` 은 모두 허용, `/api/**` 의 `POST/PATCH/DELETE` 는 인증 필요(회원가입·로그인·로그아웃·조회수 제외) → 비로그인은 **401** `{"error":"로그인이 필요합니다."}`
2. **소유자 여부** — `BroadcastService.requireOwner`, `LiveService.requireOwner`: DB 의 소유자 id 와 세션 사용자 id 비교 → 다르면 **403**
3. **댓글 작성자 여부** — `CommentService.requireAuthor` → 다르면 **403**. 작성자는 요청 본문이 아니라 **세션의 사용자**로 정해서 다른 사람 이름으로 쓸 수 없습니다.
4. **라이브 송출 연결** — `LiveSignalingHandler`: WebSocket 핸드셰이크의 로그인 세션이 방송 소유자이고, 방송을 시작한 송출 화면의 토큰이 맞을 때만 송출자로 등록 (아니면 `error` 후 연결 종료). 시청(`watch`)은 비로그인도 가능.
5. **CSRF** — 세션 쿠키를 쓰므로 등록·수정·삭제 요청에는 `X-XSRF-TOKEN` 헤더가 있어야 합니다(없으면 403). 화면 스크립트(`common.js`)가 자동으로 붙입니다.

이 규칙은 통합 테스트로 확인합니다: `BroadcastApiTests`(소유자 수정·삭제), `LiveApiTests`(소유자 송출 제어, WebSocket 송출 거절), `AuthAndCommentTests`(가입·로그인·로그아웃, 댓글 작성·작성자 권한).

### 계정 규칙

- 아이디: 영문·숫자·밑줄 3~20자 (중복 불가) / 비밀번호: 8~64자 / 닉네임: 2~20자 (방송·댓글에 표시)
- 가입하면 바로 로그인됩니다. 로그인 시 세션 id 를 새로 발급(세션 고정 방지)하고, 로그아웃 시 세션을 무효화합니다.
- 비밀번호는 BCrypt 해시로만 DB에 저장되며 응답에 포함되지 않습니다.

## 실행 방법

필요 환경: **JDK 21 이상** (`java -version`으로 확인)

```bash
# (선택) 환경변수 파일 만들기. 없으면 기본값으로 실행됩니다.
cp .env.example .env          # Windows PowerShell: copy .env.example .env

# 실행
./gradlew bootRun             # Windows: gradlew.bat bootRun
```

PC 브라우저에서 http://localhost:3000 을 엽니다. 처음 실행할 때는 Gradle과 의존성을 내려받느라 시간이 걸립니다.

- 오른쪽 위 **로그인** → **회원가입**으로 계정을 만든 뒤 방송 등록·라이브 시작·댓글을 쓸 수 있습니다. (별도 관리자 계정이나 초기 비밀번호는 없습니다)
- **컴퓨터로 송출**: 서버를 켠 컴퓨터에서 http://localhost:3000/live-start.html 을 열면 바로 웹캠·화면 공유로 송출할 수 있습니다. (`localhost`는 브라우저가 보안 주소로 취급해 http 에서도 카메라·화면 공유가 허용됨)
- **휴대폰(또는 다른 컴퓨터)으로 송출하려면 HTTPS 가 필요합니다** → 아래 [송출 기기 연결](#송출-기기-연결)을 따라 하세요.

그 밖의 명령:

```bash
./gradlew test                # 통합 테스트 (녹화 방송, 라이브 시그널링, 인증·권한, 댓글)
./gradlew bootJar             # 실행 가능한 JAR 생성 → java -jar build/libs/chzzk-vod-0.1.0.jar
```

## 환경변수 예시

`.env.example` 참고. 모두 선택값이며 실제 비밀 값은 필요하지 않습니다. (3단계에서 새로 필요한 환경변수는 없습니다. 회원 비밀번호는 각자 가입할 때 정하고 DB에 해시로만 저장됩니다)
실행 폴더의 `.env` 파일을 자동으로 읽고(`application.yml`의 `spring.config.import`), 같은 이름의 OS 환경변수로 지정해도 됩니다.

```dotenv
# 1단계
PORT=3000                         # 서버 포트
DATA_DIR=./data                   # DB(H2 파일) 폴더 — 회원·방송·라이브·댓글 (상대 경로는 ./ 로 시작)
UPLOAD_DIR=uploads                # 업로드 영상·라이브 녹화본 폴더
MAX_UPLOAD_MB=500                 # 업로드 최대 크기(MB)

# 2단계 — HTTPS (휴대폰 카메라 사용에 필요)
SSL_ENABLED=false                 # true 로 바꾸면 https 로 실행
SSL_KEYSTORE=certs/dev.p12        # keytool 로 만든 개발용 인증서
SSL_KEYSTORE_PASSWORD=changeit    # 인증서를 만들 때 정한 비밀번호 (예시 값)

# 2단계 — 라이브
LIVE_MAX_VIEWERS=10               # 동시 시청자 수 제한
LIVE_PUBLISHER_GRACE_SECONDS=30   # 송출 연결이 끊긴 뒤 자동 종료까지 기다리는 시간(초)
STUN_URLS=stun:stun.l.google.com:19302
TURN_URL=                         # (선택) 다른 망끼리 연결이 안 될 때만
TURN_USERNAME=
TURN_CREDENTIAL=
```

## 송출 기기 연결

### 컴퓨터에서 송출 (웹캠·화면 공유)

1. 서버를 켠 컴퓨터의 Chrome/Edge/Firefox/Safari에서 **http://localhost:3000/live-start.html** 을 엽니다. (다른 컴퓨터에서 송출할 때는 아래 휴대폰과 같은 HTTPS 설정 후 `https://<서버 IP>:3000`)
2. **영상**에서 고릅니다.
   - **기본 카메라 (웹캠)** / **카메라: 장치 이름** — 웹캠이 여러 개면 권한을 허용한 뒤 목록에 장치 이름이 나타납니다.
   - **화면 공유 (모니터·창·탭)** — 미리보기를 켜면 브라우저가 공유할 화면을 묻습니다. Chrome/Edge에서 **탭** 또는 **전체 화면**을 고르고 **오디오 공유**를 켜면 컴퓨터 소리(게임·영상 소리)도 송출됩니다. 컴퓨터 소리와 마이크는 하나로 섞여 나갑니다.
3. **소리**에서 마이크를 고르거나 **마이크 사용 안 함**을 고릅니다.
4. **미리보기 켜기 (권한 허용)** → 권한을 허용하면 송출될 화면이 미리보기에 나옵니다 → **방송 시작**
5. 화면 공유 중 브라우저 상단/하단의 **공유 중지**를 누르면 방송이 자동으로 종료됩니다.

- 화면 공유는 휴대폰 브라우저에서 지원되지 않아 휴대폰에서는 선택지에 나오지 않습니다.
- 웹캠이 없는 컴퓨터는 화면 공유로 송출하면 됩니다.
- 송출 탭을 백그라운드로 두어도 송출은 계속되지만, 탭을 닫으면 30초 뒤 방송이 자동 종료됩니다.

### 휴대폰에서 송출 (접속 환경과 카메라·마이크 권한)

#### 왜 HTTPS 가 필요한가

휴대폰 브라우저(Chrome, Safari)는 **보안 컨텍스트(HTTPS 또는 localhost)** 에서만 `getUserMedia`(카메라·마이크)를 허용합니다.
휴대폰에서 `http://192.168.x.x:3000` 으로 열면 권한 팝업조차 뜨지 않고, 송출 화면에 "HTTPS가 아니라서 카메라·마이크를 막습니다" 안내가 표시됩니다.
시청만 하는 화면은 http 에서도 됩니다.

#### 방법 A — 같은 Wi-Fi + 개발용 인증서 (권장)

PC와 휴대폰을 **같은 Wi-Fi(공유기)** 에 연결합니다.

1. **PC의 IP 확인**: Windows `ipconfig` → "IPv4 주소" (예: `192.168.0.10`), macOS `ipconfig getifaddr en0`, Linux `hostname -I`
2. **개발용 인증서 만들기** (JDK에 들어 있는 `keytool` 사용. PATH에 없으면 `JDK설치폴더\bin\keytool.exe`):

   ```bash
   mkdir certs
   keytool -genkeypair -alias vod -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore certs/dev.p12 -validity 365 -storepass changeit -dname "CN=localhost" -ext "SAN=dns:localhost,ip:127.0.0.1,ip:192.168.0.10"
   ```

   - 마지막 `ip:192.168.0.10` 을 1번에서 확인한 PC IP로 바꿉니다.
   - `changeit` 대신 원하는 비밀번호를 써도 됩니다. `certs/`, `*.p12`, `.env` 는 `.gitignore` 에 있어 커밋되지 않습니다.
3. **`.env` 에서 HTTPS 켜기**:

   ```dotenv
   SSL_ENABLED=true
   SSL_KEYSTORE=certs/dev.p12
   SSL_KEYSTORE_PASSWORD=changeit
   ```

4. `./gradlew bootRun` 후 로그에 `Tomcat started on port 3000 (https)` 가 보이면 준비 완료.
   - Windows에서 처음 실행하면 "Windows 보안 경고(방화벽)"가 뜰 수 있습니다 → **개인 네트워크 허용**을 누릅니다. 이미 닫았다면 방화벽 설정에서 Java(OpenJDK Platform binary)의 개인 네트워크 허용을 켭니다.
5. 휴대폰 브라우저에서 **`https://192.168.0.10:3000/live-start.html`** 접속
   - 개발용(자체 서명) 인증서라서 경고가 뜹니다.
     - Android Chrome: "연결이 비공개로 설정되어 있지 않습니다" → **고급** → **192.168.0.10(으)로 이동(안전하지 않음)**
     - iPhone Safari: "이 연결은 비공개 연결이 아닙니다" → **세부사항 보기** → **이 웹 사이트 방문** → 확인
6. PC에서는 `https://localhost:3000` 으로 시청합니다. (같은 경고가 뜨면 "고급 → 계속")

#### 방법 B — HTTPS 터널 (인증서 없이)

[cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/) 같은 터널 도구로 `http://localhost:3000` 을 공개 https 주소로 열 수도 있습니다.

```bash
cloudflared tunnel --url http://localhost:3000
```

출력된 `https://….trycloudflare.com` 주소를 휴대폰과 시청 화면에서 엽니다. (터널은 화면·시그널링만 전달하고, 영상은 WebRTC로 기기끼리 직접 연결됩니다.
휴대폰이 LTE이고 시청자가 다른 망이면 연결이 안 될 수 있으며, 이때는 `TURN_URL` 등 TURN 서버 설정이 필요합니다.)

#### 카메라·마이크 권한

- 송출 화면에서 **미리보기 켜기 (권한 허용)** 를 누르면 브라우저가 권한을 묻습니다 → **허용**. (컴퓨터 화면 공유는 공유할 화면 선택 창이 뜹니다)
- 실수로 거부했다면 화면에 "권한이 거부되었습니다" 안내가 나옵니다. 다시 허용하는 방법:
  - Android Chrome: 주소창 왼쪽 아이콘 → **권한**(사이트 설정) → 카메라·마이크 **허용** → 새로고침
  - iPhone Safari: 주소창 **aA**(또는 페이지 설정) → **웹사이트 설정** → 카메라·마이크 **허용** → 새로고침 (설정 앱 → Safari → 카메라/마이크에서도 변경 가능)
- 다른 앱(카메라 앱, 영상 통화)이 카메라를 쓰고 있으면 켜지지 않습니다.

#### 송출 중 주의

- 휴대폰 화면을 끄거나 다른 앱으로 전환하면 브라우저가 카메라를 멈춥니다. 송출 화면은 지원 브라우저에서 화면 꺼짐 방지(Wake Lock)를 요청합니다.
- 네트워크가 잠깐 끊기면 송출 화면이 자동으로 다시 연결합니다. **30초**(`LIVE_PUBLISHER_GRACE_SECONDS`) 안에 돌아오지 않으면 서버가 방송을 **자동 종료**합니다.
- 송출 기기가 시청자마다 영상을 따로 보내므로 동시 시청자는 기본 **10명**(`LIVE_MAX_VIEWERS`)으로 제한합니다.

## 이번 단계 구현 범위

### 3단계 — 인증·인가와 댓글 (이번에 추가)

- **회원가입·로그인·로그아웃** (`/login.html`, 헤더 오른쪽 로그인/닉네임·로그아웃)
  - `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me`
  - Spring Security 세션 인증, BCrypt 비밀번호, CSRF 토큰, 로그인 필요한 화면(방송 등록·라이브 시작)은 로그인 화면으로 이동 후 원래 화면으로 복귀
- **방송 소유자와 권한 검사 (서버)**
  - 녹화 방송·라이브에 소유자(`owner_id`) 저장, 목록·상세에 소유자 닉네임 표시
  - 녹화 방송: 등록(로그인), **제목·설명 수정**(`PATCH`), **삭제**(`DELETE`, 업로드 파일·댓글 함께 삭제) — 소유자만
  - 라이브: 시작(로그인), **송출 연결·종료·제목 수정·녹화본 등록·종료된 기록 삭제** — 소유자만. 소유자는 시청 화면에서도 **방송 종료** 가능
  - 비로그인 401, 다른 사용자 403 (위 [인증·인가와 공개 범위](#인증인가와-공개-범위) 표)
- **댓글** (녹화 방송 상세, 라이브 시청 화면)
  - 방송별 댓글 목록(공개), 작성(로그인), **작성자만 수정·삭제** (수정 시 "수정됨" 표시)
  - 라이브 화면은 3초마다 새 댓글을 불러와 채팅처럼 보입니다
  - 댓글 500자 제한, 방송이 삭제되면 댓글도 삭제

### 2단계 — 휴대폰·컴퓨터 라이브 (유지)

- 라이브 시작(`/live-start.html`): 휴대폰 전면/후면 카메라, 컴퓨터 웹캠·화면 공유(컴퓨터 소리 + 마이크 믹싱), 미리보기, 방송 시작·종료, 시청자 수, 경과 시간, 마이크 끄기, 녹화본 다시보기 저장
- 라이브 시청(`/live.html?id=...`): WebRTC 시청, 상태 표시(연결 중 / ● LIVE / 송출자 연결 대기 / 방송 종료 / 입장 불가)
- 목록(`/`): 지금 라이브 / 다시보기 / 종료된 라이브
- 송출 연결이 30초 이상 끊기면 자동 종료, 서버 재시작 시 "방송 중" 정리

### 1단계 — 녹화 방송 (유지)

- 녹화 방송 등록(`/upload.html`): 파일 업로드 또는 영상 URL 연결(mp4 주소, 유튜브 주소)
- 목록·상세(`/watch.html?id=...`)·재생, 조회수, Range 요청(탐색), 재시작 후에도 유지

**이번 단계에서 하지 않음**: 도네이션, 관리자 권한(남의 방송·댓글 관리), 비밀번호 찾기·회원 탈퇴, 미디어 서버(HLS) 기반 대규모 송출

## API

🔓 = 로그인 없이 가능, 🔐 = 로그인 필요, 👑 = 소유자/작성자만

### 인증 (3단계)

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/auth/signup` | 🔓 | 회원가입 후 로그인. JSON `{"username","password","nickname"}` |
| POST | `/api/auth/login` | 🔓 | 로그인. JSON `{"username","password"}` |
| POST | `/api/auth/logout` | 🔓 | 로그아웃 (세션 무효화) |
| GET | `/api/auth/me` | 🔓 | 로그인한 사용자 `{"user":{"id","nickname"}}` 또는 `{"user":null}` |

### 녹화 방송

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/broadcasts` | 🔓 | 방송 목록 (최신순, `owner` 포함) |
| GET | `/api/broadcasts/{id}` | 🔓 | 방송 상세 |
| POST | `/api/broadcasts` | 🔐 | 등록. `multipart/form-data`: `title`, `description?`, `video`(파일) **또는** `videoUrl` |
| PATCH | `/api/broadcasts/{id}` | 👑 | 제목·설명 수정. JSON `{"title","description"}` |
| DELETE | `/api/broadcasts/{id}` | 👑 | 삭제 (업로드 파일·댓글 함께) |
| POST | `/api/broadcasts/{id}/views` | 🔓 | 조회수 +1 |
| GET | `/media/{file}` | 🔓 | 업로드된 영상 파일 (Range 지원) |

### 라이브

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| POST | `/api/lives` | 🔐 | 라이브 시작. JSON `{"title"}` → `{ live, publisherToken }` |
| GET | `/api/lives?status=live\|ended` | 🔓 | 라이브 목록 |
| GET | `/api/lives/{id}` | 🔓 | 라이브 상세 (상태, 시청자 수, 소유자, 다시보기 id) |
| PATCH | `/api/lives/{id}` | 👑 | 제목 수정 |
| POST | `/api/lives/{id}/end` | 👑 | 방송 종료 |
| POST | `/api/lives/{id}/recording` | 👑 | 종료 후 녹화본 등록 (`multipart/form-data` `video`) → 소유자의 다시보기 |
| DELETE | `/api/lives/{id}` | 👑 | 종료된 라이브 기록 삭제 (방송 중이면 409) |
| GET | `/api/rtc-config` | 🔓 | WebRTC ICE 서버 설정 |
| WS | `/ws/lives/{id}` | 🔓 시청 / 👑 송출 | 첫 메시지 `{"type":"watch"}`(시청) 또는 `{"type":"publish","token":"..."}`(송출: 로그인 세션 = 소유자 + 토큰) |

### 댓글 (3단계)

| 메서드 | 경로 | 권한 | 설명 |
| --- | --- | --- | --- |
| GET | `/api/broadcasts/{id}/comments` | 🔓 | 녹화 방송 댓글 목록 (오래된 순) |
| POST | `/api/broadcasts/{id}/comments` | 🔐 | 댓글 작성. JSON `{"content"}` |
| GET | `/api/lives/{id}/comments` | 🔓 | 라이브 댓글 목록 |
| POST | `/api/lives/{id}/comments` | 🔐 | 라이브 댓글 작성 |
| PATCH | `/api/comments/{id}` | 👑 | 댓글 수정 (작성자만) |
| DELETE | `/api/comments/{id}` | 👑 | 댓글 삭제 (작성자만) |

오류는 `{"error": "메시지"}` 형식으로 응답합니다. 400 입력 오류 / **401 로그인 필요** / **403 권한 없음·CSRF 토큰 없음** / 404 없음 / 409 상태 충돌.

## 프로젝트 구조

```
src/main/java/dev/chzzk/vod/
  VodApplication.java
  user/                        # 3단계: 회원
    User.java                  # 회원 엔티티 (아이디, BCrypt 비밀번호 해시, 닉네임)
    UserService.java           # 가입 검증, 로그인용 사용자 조회
    LoginUser.java             # 세션에 저장되는 로그인 사용자 (@AuthenticationPrincipal)
    AuthController.java        # 가입·로그인·내 정보
  comment/                     # 3단계: 댓글 (엔티티, 저장소, 서비스[작성자 권한], REST API)
  broadcast/                   # 1단계: 녹화 방송 (+ 3단계 소유자·수정·삭제)
  live/                        # 2단계: 라이브 (+ 3단계 소유자 송출 제어)
    LiveService.java           # 시작·종료·수정·삭제·녹화본, 소유자 검사, 자동 종료
    LiveRooms.java             # 방송별 송출자·시청자 연결 관리, 시그널링 중계
    LiveSignalingHandler.java  # WebSocket /ws/lives/{id} (송출은 로그인 소유자만)
  storage/VideoStorage.java    # 업로드 파일 저장
  config/
    SecurityConfig.java        # 3단계: 로그인 필요 경로, 401/403 JSON 응답, CSRF, 로그아웃
    WebConfig.java, WebSocketConfig.java
  common/                      # ApiException, 전역 오류 처리
src/main/resources/static/
  index.html                   # 목록 (지금 라이브 / 다시보기 / 종료된 라이브)
  login.html                   # 3단계: 로그인·회원가입
  live-start.html, live.html   # 라이브 송출(로그인), 시청(+ 소유자 종료, 댓글)
  watch.html, upload.html      # 다시보기 상세(+ 소유자 수정·삭제, 댓글), 등록(로그인)
  common.js                    # API 호출(CSRF 헤더), 로그인 상태 표시, 댓글 위젯
src/test/java/dev/chzzk/vod/
  AuthAndCommentTests.java     # 가입·로그인·로그아웃, 댓글 공개 조회·로그인 작성·작성자 권한
  BroadcastApiTests.java       # 녹화 방송 + 비로그인 등록 거절, 소유자 수정·삭제, CSRF
  LiveApiTests.java            # 라이브 시그널링 + 소유자 송출 제어 (실제 HTTP 세션·WebSocket)
```

## 직접 확인 절차

### 3단계 — 비로그인 / 방송 소유자 / 다른 사용자로 권한 확인

준비: 서버 실행 후 **서로 다른 브라우저 2개**(예: Chrome 일반 창 + 시크릿 창)를 엽니다. 쿠키가 분리되어 두 사용자로 동시에 확인할 수 있습니다.

1. **비로그인 (창 A, 로그인 전)**
   1. 목록·다시보기 상세·라이브 시청·댓글 목록이 보이는지 확인 → **공개**
   2. **방송 등록** 또는 **라이브 시작**을 누르면 로그인 화면으로 이동하는지 확인
   3. 다시보기 상세의 댓글란에 "댓글을 쓰려면 로그인하세요"가 나오고 작성 폼이 없는지 확인
   4. 서버 거절 확인 (화면을 거치지 않고 직접 호출):
      ```bash
      curl -i -X POST -H "Content-Type: application/json" -d '{"title":"x"}' http://localhost:3000/api/lives
      ```
      → CSRF 토큰이 없어 **403**. 토큰을 넣어도 비로그인이면 **401** 입니다. (브라우저 개발자 도구 콘솔에서 `api('/api/lives', jsonRequest('POST', {title:'x'}))` 를 실행하면 401 "로그인이 필요합니다." 확인)
2. **방송 소유자 (창 A)**
   1. **로그인 → 회원가입**으로 사용자 A 가입 → 헤더에 닉네임이 보이는지 확인
   2. **방송 등록**으로 녹화 방송 등록 → 상세 화면에 "등록 A닉네임"과 **내 방송: 수정 / 삭제** 버튼이 보이는지 확인
   3. **수정**으로 제목을 바꾸고 저장 → 반영 확인
   4. 댓글 작성 → 내 댓글에 **수정 / 삭제** 버튼이 보이는지, 수정하면 "(수정됨)" 표시되는지 확인
   5. **라이브 시작**으로 방송 시작 → 시청 화면에 **내 방송: 방송 종료** 버튼이 보이는지 확인
3. **다른 사용자 (창 B, 시크릿 창)**
   1. 사용자 B로 가입 → A의 다시보기 상세를 열었을 때 **수정·삭제 버튼이 없고**, A의 댓글에도 **수정·삭제 버튼이 없는지** 확인
   2. 댓글 작성은 되는지 확인 (작성자 B로 표시)
   3. A의 라이브 시청 화면에 **방송 종료 버튼이 없는지** 확인
   4. **버튼 숨김이 아닌 서버 검사 확인** — 창 B의 개발자 도구 콘솔에서 직접 호출:
      ```js
      await api('/api/broadcasts/<A의 방송 id>', jsonRequest('PATCH', { title: '남의 방송 수정' }))  // → 403 "이 방송의 소유자만 수정·삭제할 수 있습니다."
      await api('/api/broadcasts/<A의 방송 id>', { method: 'DELETE' })                            // → 403
      await api('/api/lives/<A의 라이브 id>/end', { method: 'POST' })                              // → 403 "이 방송의 소유자만 송출을 제어할 수 있습니다."
      await api('/api/comments/<A의 댓글 id>', { method: 'DELETE' })                               // → 403 "댓글 작성자만 수정·삭제할 수 있습니다."
      ```
      (id 는 주소창의 `?id=` 값, 댓글 id 는 `await api('/api/broadcasts/<id>/comments')` 결과에서 확인)
   5. 창 A로 돌아가 방송·라이브·댓글이 그대로인지 확인
4. **소유자 제어 마무리 (창 A)**: 시청 화면 또는 송출 화면에서 **방송 종료** → 두 창 모두 "방송 종료"로 바뀌는지 확인 → 다시보기 상세에서 **삭제** → 목록에서 사라지는지 확인
5. **로그아웃**: 헤더 **로그아웃** → 다시 비로그인 상태(로그인 버튼, 댓글 작성 폼 없음)가 되는지 확인

### 2단계 — 휴대폰·컴퓨터 송출 → 다른 화면에서 시청

준비: [방법 A](#방법-a--같은-wi-fi--개발용-인증서-권장) 또는 [방법 B(터널)](#방법-b--https-터널-인증서-없이)로 휴대폰에서 접속할 https 주소를 준비합니다. 송출하려면 **로그인**이 필요합니다.

1. **송출 시작 (휴대폰)**: `https://…/live-start.html` → (로그인) → 제목 입력 → 카메라 선택 → **미리보기 켜기** → 권한 **허용** → **방송 시작** → 상태 **● 방송 중** 확인
2. **시청 (PC 또는 다른 휴대폰)**: 목록 **지금 라이브** → 카드 선택 → 휴대폰 영상과 **● LIVE** 확인 → **소리 켜기** → 휴대폰 화면의 시청자 수가 늘었는지 확인
3. **송출 종료**: 휴대폰 **방송 종료** → 시청 화면이 즉시 **방송 종료**로 바뀌고, 녹화 저장을 켰다면 다시보기 링크가 나타나는지 확인
4. **연결 끊김**: 방송 중 송출 탭을 닫으면 시청 화면이 "송출자 연결 대기" → 30초 뒤 자동 **방송 종료**
5. **컴퓨터 송출**: 서버 컴퓨터에서 http://localhost:3000/live-start.html → **화면 공유** 또는 **웹캠** → 방송 시작 → 다른 창에서 시청 → 브라우저 **공유 중지** 시 자동 종료
6. **문제가 생기면**: "HTTPS가 아니라서…" 안내 → 주소가 `https://` 인지 / 접속 안 됨 → 같은 Wi-Fi·방화벽 / 시청 "연결 실패" → 다른 망이면 TURN 설정

### 1단계 — 녹화 방송 (유지 확인)

1. (로그인 후) **방송 등록** → **파일 업로드** 탭에서 mp4 → **등록하기** → 상세 화면에서 재생·탐색 확인
2. **영상 URL 연결** 탭 → 공개 mp4 주소(예: `https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4`) 또는 유튜브 주소 → 재생 확인
3. 제목 없이 등록, 영상이 아닌 파일, `ftp://` 주소 → 오류 메시지 확인
4. 서버를 `Ctrl+C`로 종료 후 다시 실행 → 회원·다시보기·라이브 기록·댓글·조회수가 그대로 남아 있는지 확인 (로그인 세션은 재시작하면 풀리므로 다시 로그인)

## 제약 사항과 다음 단계 메모

- 송출 기기가 시청자마다 직접 영상을 보내는 P2P 구조라 대규모 시청에는 맞지 않습니다. 시청자가 많아지면 미디어 서버(SFU: LiveKit, mediasoup 또는 RTMP/HLS: SRS, MediaMTX)로 바꾸고, 소유자 검사는 지금처럼 Spring 쪽에서 송출 키 발급·검증으로 유지하면 됩니다.
- 서로 다른 망끼리는 TURN 서버가 필요할 수 있습니다(`TURN_URL` 등).
- 녹화본은 송출 기기 메모리에 모았다가 종료 후 업로드하므로 긴 방송은 `MAX_UPLOAD_MB`와 기기 메모리에 제한을 받습니다.
- 세션은 서버 메모리에 있어 재시작하면 다시 로그인해야 합니다. 서버를 여러 대로 늘리면 Spring Session(Redis 등)으로 옮깁니다.
- 라이브 댓글은 3초 폴링입니다. 다음 단계에서 같은 WebSocket 방으로 실시간 채팅·도네이션 알림을 보낼 수 있습니다.
