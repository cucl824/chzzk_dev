# 치지직 만들기 — 2단계: 휴대폰 라이브 송출 (+ 1단계 녹화 방송)

치지직 같은 방송 플랫폼을 단계별로 만드는 프로젝트입니다.

- **1단계 (유지)**: 로그인 없이 녹화 방송(다시보기)을 등록하고 목록·상세 화면에서 재생
- **2단계 (이번)**: 로그인 없이 **휴대폰 카메라·마이크로 라이브 방송을 시작·종료**하고, 다른 화면에서 목록에서 골라 시청. 방송 중/종료 상태 표시

## 기술 스택

| 구분 | 사용 |
| --- | --- |
| 서버 | Java 21+, Spring Boot 4.1 (Spring MVC, Spring WebSocket) |
| 빌드 | Gradle Wrapper (`gradlew`, 별도 설치 불필요) |
| 저장소 | Spring Data JPA + H2 **파일 DB** (`data/vod.mv.db`) |
| 업로드 | Spring Multipart → 로컬 디스크 (`uploads/`) |
| 라이브 송출 | 브라우저 `getUserMedia`(카메라·마이크) + **WebRTC** (영상은 P2P로 전송) |
| 라이브 시그널링 | WebSocket `/ws/lives/{id}` (offer / answer / ICE 중계) |
| 화면 | 정적 HTML + 바닐라 JS (`src/main/resources/static`) |

### 라이브 구조

별도 미디어 서버 없이, 휴대폰 브라우저가 시청자마다 WebRTC 연결을 직접 맺어 영상을 보냅니다.
Spring 서버는 방송 정보·상태 저장과 연결을 맺기 위한 시그널링 메시지 중계만 합니다.

```
 휴대폰 (live-start.html)                Spring 서버                      시청자 (live.html)
 ─────────────────────────              ────────────                     ─────────────────
 getUserMedia(카메라, 마이크)
 POST /api/lives ───────────────────▶ 방송 생성(LIVE) + 송출 토큰
 WS publish(token) ─────────────────▶ 송출자 등록
                                       ◀──────────────────────────────── WS watch
 ◀── viewer-joined ─────────────────── 시청자 등록
 offer ─────────────────────────────▶ 중계 ───────────────────────────▶ offer
                                       ◀──────────────────────────────── answer / ice
 ◀── answer / ice ──────────────────── 중계
 ════════════════ 카메라·마이크 영상 (WebRTC P2P, 서버를 거치지 않음) ════════════════▶
 POST /api/lives/{id}/end ──────────▶ 상태 ENDED, 모든 시청자에게 ended
 POST /api/lives/{id}/recording ────▶ 녹화본 → 1단계 다시보기로 등록
```

## 실행 방법

필요 환경: **JDK 21 이상** (`java -version`으로 확인)

```bash
# (선택) 환경변수 파일 만들기. 없으면 기본값으로 실행됩니다.
cp .env.example .env          # Windows PowerShell: copy .env.example .env

# 실행
./gradlew bootRun             # Windows: gradlew.bat bootRun
```

PC 브라우저에서 http://localhost:3000 을 엽니다. 처음 실행할 때는 Gradle과 의존성을 내려받느라 시간이 걸립니다.

- PC에서만 확인할 때는 그대로 쓰면 됩니다. (`localhost`는 브라우저가 보안 주소로 취급해 http 에서도 카메라를 쓸 수 있음)
- **휴대폰으로 송출하려면 HTTPS 가 필요합니다** → 아래 [휴대폰 연결](#휴대폰-연결-접속-환경과-카메라마이크-권한)을 따라 하세요.

그 밖의 명령:

```bash
./gradlew test                # 통합 테스트 (녹화 방송 + 라이브 시그널링)
./gradlew bootJar             # 실행 가능한 JAR 생성 → java -jar build/libs/chzzk-vod-0.1.0.jar
```

## 환경변수 예시

`.env.example` 참고. 모두 선택값이며 실제 비밀 값은 필요하지 않습니다.
실행 폴더의 `.env` 파일을 자동으로 읽고(`application.yml`의 `spring.config.import`), 같은 이름의 OS 환경변수로 지정해도 됩니다.

```dotenv
# 1단계
PORT=3000                         # 서버 포트
DATA_DIR=./data                   # 메타데이터 DB(H2 파일) 폴더 (상대 경로는 ./ 로 시작)
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

## 휴대폰 연결 (접속 환경과 카메라·마이크 권한)

### 왜 HTTPS 가 필요한가

휴대폰 브라우저(Chrome, Safari)는 **보안 컨텍스트(HTTPS 또는 localhost)** 에서만 `getUserMedia`(카메라·마이크)를 허용합니다.
휴대폰에서 `http://192.168.x.x:3000` 으로 열면 권한 팝업조차 뜨지 않고, 송출 화면에 "HTTPS가 아니라서 카메라·마이크를 막습니다" 안내가 표시됩니다.
시청만 하는 화면은 http 에서도 됩니다.

### 방법 A — 같은 Wi-Fi + 개발용 인증서 (권장)

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

### 방법 B — HTTPS 터널 (인증서 없이)

[cloudflared](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/) 같은 터널 도구로 `http://localhost:3000` 을 공개 https 주소로 열 수도 있습니다.

```bash
cloudflared tunnel --url http://localhost:3000
```

출력된 `https://….trycloudflare.com` 주소를 휴대폰과 시청 화면에서 엽니다. (터널은 화면·시그널링만 전달하고, 영상은 WebRTC로 기기끼리 직접 연결됩니다.
휴대폰이 LTE이고 시청자가 다른 망이면 연결이 안 될 수 있으며, 이때는 `TURN_URL` 등 TURN 서버 설정이 필요합니다.)

### 카메라·마이크 권한

- 송출 화면에서 **카메라·마이크 켜기**를 누르면 브라우저가 권한을 묻습니다 → **허용**.
- 실수로 거부했다면 화면에 "권한이 거부되었습니다" 안내가 나옵니다. 다시 허용하는 방법:
  - Android Chrome: 주소창 왼쪽 아이콘 → **권한**(사이트 설정) → 카메라·마이크 **허용** → 새로고침
  - iPhone Safari: 주소창 **aA**(또는 페이지 설정) → **웹사이트 설정** → 카메라·마이크 **허용** → 새로고침 (설정 앱 → Safari → 카메라/마이크에서도 변경 가능)
- 다른 앱(카메라 앱, 영상 통화)이 카메라를 쓰고 있으면 켜지지 않습니다.

### 송출 중 주의

- 휴대폰 화면을 끄거나 다른 앱으로 전환하면 브라우저가 카메라를 멈춥니다. 송출 화면은 지원 브라우저에서 화면 꺼짐 방지(Wake Lock)를 요청합니다.
- 네트워크가 잠깐 끊기면 송출 화면이 자동으로 다시 연결합니다. **30초**(`LIVE_PUBLISHER_GRACE_SECONDS`) 안에 돌아오지 않으면 서버가 방송을 **자동 종료**합니다.
- 송출 휴대폰이 시청자마다 영상을 따로 보내므로 동시 시청자는 기본 **10명**(`LIVE_MAX_VIEWERS`)으로 제한합니다.

## 이번 단계 구현 범위

### 2단계 — 휴대폰 라이브 (이번에 추가)

- **라이브 시작** (`/live-start.html`)
  - 방송 제목 입력, 전면/후면 카메라 선택, 카메라·마이크 미리보기 (`getUserMedia`)
  - HTTPS가 아닐 때·권한 거부·카메라 없음 안내
  - **방송 시작** → 서버에 라이브 생성(`LIVE`), WebSocket으로 송출 등록, 시청자가 들어올 때마다 WebRTC 연결
  - 방송 중 화면: 상태(● 방송 중 / 재연결 중), 경과 시간, 시청자 수, 시청 주소 복사, 마이크 끄기/켜기
  - **방송 종료** → 서버 상태 `ENDED`, 시청자 화면에 즉시 종료 표시
  - (선택, 기본 켜짐) 송출 중 `MediaRecorder`로 녹화 → 종료 후 업로드해 **1단계 다시보기로 자동 등록**, 라이브와 연결
- **라이브 시청** (`/live.html?id=...`)
  - 목록에서 라이브를 골라 시청. 자동재생 정책 때문에 음소거로 시작하고 **소리 켜기** 버튼 제공
  - 상태 표시: 연결 중 / ● LIVE / 송출자 연결 대기 / 방송 종료 / 입장 불가(인원 초과)
  - 종료된 방송은 "방송이 종료되었습니다"와 방송 시간, 다시보기 링크 표시
- **방송 목록** (`/`): **지금 라이브**(● LIVE 배지, 시청자 수, 경과 시간, 5초마다 갱신) / **다시보기** / **종료된 라이브**(종료 배지)
- 방송 상태 관리
  - 라이브 정보(제목, 상태, 시작·종료 시각, 다시보기 id)를 H2 DB에 영속 저장
  - 송출 연결이 유예 시간 동안 없으면 자동 종료, 서버 재시작 시 남아 있던 "방송 중"을 종료로 정리
  - 종료·녹화본 등록은 방송을 시작한 화면이 받은 **송출 토큰**으로만 가능 (로그인이 아닌, 다른 사람이 남의 방송을 끄지 못하게 하는 장치)
- 로그인 없이 송출·시청 모두 가능 (Spring Security 미사용)

### 1단계 — 녹화 방송 (유지)

- 녹화 방송 등록 (`/upload.html`): 제목(필수, 100자 이하), 설명(선택), **파일 업로드** 또는 **영상 URL 연결**(mp4 등 영상 주소, 유튜브 주소)
- 방송 목록·상세(`/watch.html?id=...`)·재생, 조회수, Range 요청(탐색) 지원
- 방송 정보와 영상 위치를 H2 파일 DB에, 업로드 파일은 `uploads/`에 저장 → 재시작해도 유지

**이번 단계에서 하지 않음**: 로그인·권한 검사, 댓글/채팅, 도네이션, 미디어 서버(HLS) 기반 대규모 송출, 방송 수정·삭제

## API

### 라이브 (2단계)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| POST | `/api/lives` | 라이브 시작. JSON `{"title": "..."}` → `{ live, publisherToken }` |
| GET | `/api/lives?status=live\|ended` | 라이브 목록 (최신순, 상태로 거르기 선택) |
| GET | `/api/lives/{id}` | 라이브 상세 (상태, 시청자 수, 시작·종료 시각, 다시보기 id) |
| POST | `/api/lives/{id}/end` | 라이브 종료. 헤더 `X-Publisher-Token` 필요 |
| POST | `/api/lives/{id}/recording` | 종료 후 녹화본 등록. `multipart/form-data` `video`, 헤더 `X-Publisher-Token` |
| GET | `/api/rtc-config` | WebRTC ICE 서버 설정 (STUN/TURN) |
| WS | `/ws/lives/{id}` | 시그널링. 첫 메시지 `{"type":"publish","token":"..."}`(송출) 또는 `{"type":"watch"}`(시청) |

WebSocket 메시지: `published`, `welcome`, `viewer-joined`, `viewer-left`, `offer`, `answer`, `ice`, `viewers`(시청자 수), `publisher-left`, `publisher-back`, `ended`, `full`, `error`

응답 예 (`GET /api/lives/{id}`):

```json
{
  "live": {
    "id": "556197b2-…",
    "title": "동네 산책 라이브",
    "status": "live",
    "viewerCount": 2,
    "startedAt": "2026-11-03T06:05:10Z",
    "endedAt": null,
    "vodBroadcastId": null
  }
}
```

### 녹화 방송 (1단계)

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/broadcasts` | 방송 목록 (최신순) |
| GET | `/api/broadcasts/{id}` | 방송 상세 |
| POST | `/api/broadcasts` | 등록. `multipart/form-data`: `title`, `description?`, `video`(파일) **또는** `videoUrl` |
| POST | `/api/broadcasts/{id}/views` | 조회수 +1 |
| GET | `/media/{file}` | 업로드된 영상 파일 (Range 지원) |

오류는 `{"error": "메시지"}` 형식(400/403/404/409/500)으로 응답합니다.

## 프로젝트 구조

```
src/main/java/dev/chzzk/vod/
  VodApplication.java
  broadcast/                   # 1단계: 녹화 방송 (엔티티, 저장소, 서비스, REST API)
  live/                        # 2단계: 라이브
    LiveStream.java            # 라이브 엔티티 (상태 LIVE/ENDED, 시작·종료 시각, 다시보기 id)
    LiveStatus.java
    LiveStreamRepository.java
    LiveService.java           # 시작·종료·녹화본 등록, 자동 종료, 재시작 시 정리
    LiveController.java        # REST API
    LiveRooms.java             # 방송별 송출자·시청자 연결 관리, 시그널링 중계, 유예 타이머
    LiveSignalingHandler.java  # WebSocket /ws/lives/{id}
    RtcConfigController.java   # STUN/TURN 설정 제공
  storage/VideoStorage.java    # 업로드 파일 저장
  config/                      # WebConfig(/media), WebSocketConfig
  common/                      # ApiException, 전역 오류 처리
src/main/resources/
  application.yml
  static/
    index.html                 # 목록 (지금 라이브 / 다시보기 / 종료된 라이브)
    live-start.html            # 휴대폰 송출 화면
    live.html                  # 라이브 시청 화면
    watch.html, upload.html    # 1단계 다시보기 상세, 등록
src/test/java/dev/chzzk/vod/
  BroadcastApiTests.java       # 1단계 통합 테스트
  LiveApiTests.java            # 라이브 시작·시그널링 중계·종료·녹화본·자동 종료 (실제 WebSocket 접속)
```

## 직접 확인 절차

### 2단계 — 휴대폰 송출 → 다른 화면에서 시청

준비: [방법 A](#방법-a--같은-wi-fi--개발용-인증서-권장)대로 HTTPS 로 서버를 실행합니다.

1. **송출 시작 (휴대폰)**
   1. 휴대폰에서 `https://<PC IP>:3000` → 우측 상단 **라이브 시작** (또는 바로 `/live-start.html`)
   2. 제목 입력 → 카메라 선택 → **카메라·마이크 켜기** → 권한 팝업에서 **허용** → 미리보기가 보이는지 확인
   3. **방송 시작** → 상태가 **● 방송 중**으로 바뀌고 경과 시간이 올라가는지 확인
2. **시청 (PC 또는 다른 휴대폰)**
   1. `https://localhost:3000` (다른 기기면 `https://<PC IP>:3000`) 목록의 **지금 라이브**에 방송이 ● LIVE 배지와 함께 보이는지 확인
   2. 카드를 눌러 시청 화면으로 이동 → 휴대폰 카메라 영상이 나오고 상태가 **● LIVE** 인지 확인 → **소리 켜기**로 마이크 소리 확인
   3. 휴대폰 송출 화면의 **시청자 수**가 1명으로 늘었는지 확인 (송출 화면의 "시청 주소"를 복사해 열어도 됨)
3. **송출 종료 확인**
   1. 휴대폰에서 **방송 종료** → 확인
   2. 시청 화면이 즉시 **방송 종료** 상태와 "방송이 종료되었습니다"로 바뀌는지 확인
   3. 녹화 저장을 켜 두었다면 휴대폰 화면에 "녹화본이 다시보기로 저장되었습니다"와 **다시보기 보기** 버튼이 나오고, 시청 화면에도 다시보기 링크가 나타나는지 확인
   4. 목록에서 방송이 **지금 라이브**에서 빠지고 **종료된 라이브**(종료 배지)와 **다시보기**에 보이는지 확인
4. **연결 끊김·재시작**
   - 방송 중 휴대폰 송출 탭을 닫으면 시청 화면이 "송출자 연결 대기"로 바뀌고, 30초 뒤 자동으로 **방송 종료**가 되는지 확인
   - 방송 중 서버를 재시작하면 그 방송이 **종료** 상태로 정리되는지 확인
5. **문제가 생기면**
   - 송출 화면에 "HTTPS가 아니라서…" 안내 → 주소가 `https://` 인지 확인
   - 휴대폰에서 접속이 안 됨 → 같은 Wi-Fi인지, PC 방화벽에서 Java 허용, 주소의 IP·포트 확인
   - 시청 화면이 "연결 실패" → 서로 다른 망(LTE 등)인지 확인, 같은 Wi-Fi로 맞추거나 TURN 설정

### 1단계 — 녹화 방송 (유지 확인)

1. **방송 등록** → 제목 입력 → **파일 업로드** 탭에서 mp4 선택 → **등록하기** → 상세 화면에서 재생·탐색 확인
2. **영상 URL 연결** 탭 → 공개 mp4 주소(예: `https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4`) 또는 유튜브 주소 등록 → 재생 확인
3. 제목 없이 등록, 영상이 아닌 파일, `ftp://` 주소 → 오류 메시지 확인
4. 서버를 `Ctrl+C`로 종료 후 다시 실행 → 다시보기·라이브 기록·조회수가 그대로 남아 있는지 확인 (`data/vod.mv.db`, `uploads/`)

API로 확인하려면:

```bash
curl -X POST -H "Content-Type: application/json" -d '{"title":"API 라이브"}' http://localhost:3000/api/lives
curl http://localhost:3000/api/lives?status=live
curl -X POST -H "X-Publisher-Token: <시작 응답의 publisherToken>" http://localhost:3000/api/lives/<id>/end
```

## 제약 사항과 다음 단계 메모

- 송출 휴대폰이 시청자마다 직접 영상을 보내는 P2P 구조라 대규모 시청에는 맞지 않습니다. 시청자가 많아지면 미디어 서버(SFU: LiveKit, mediasoup 또는 RTMP/HLS: SRS, MediaMTX)로 바꾸고, `LiveRooms`의 시그널링 대신 미디어 서버 연동을 넣으면 됩니다. 방송 상태·목록·화면 흐름은 그대로 쓸 수 있습니다.
- 서로 다른 망끼리는 TURN 서버가 필요할 수 있습니다(`TURN_URL` 등).
- 녹화본은 송출 휴대폰 메모리에 모았다가 종료 후 업로드하므로, 긴 방송은 `MAX_UPLOAD_MB`와 휴대폰 메모리에 제한을 받습니다. webm 녹화본은 브라우저에 따라 전체 길이 표시·탐색이 정확하지 않을 수 있습니다.
- 다음 단계(인증·채팅·도네이션): Spring Security로 송출 API에 인증을 걸고, 같은 WebSocket 방에 채팅 메시지를 추가할 수 있습니다.
