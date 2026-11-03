# VOD 플랫폼 — 1단계: 녹화 방송 등록·시청

치지직 같은 방송 플랫폼을 단계별로 만드는 프로젝트의 첫 단계입니다.
**로그인 없이** 녹화된 방송(다시보기)을 등록하고, 목록에서 골라 상세 화면에서 재생합니다.

## 기술 스택

| 구분 | 사용 |
| --- | --- |
| 서버 | Java 21+, Spring Boot 4.1 (Spring MVC) |
| 빌드 | Gradle Wrapper (`gradlew`, 별도 설치 불필요) |
| 저장소 | Spring Data JPA + H2 **파일 DB** (`data/vod.mv.db`) |
| 업로드 | Spring Multipart → 로컬 디스크 (`uploads/`) |
| 화면 | 정적 HTML + 바닐라 JS + HTML5 `<video>` (`src/main/resources/static`) |

## 실행 방법

필요 환경: **JDK 21 이상** (`java -version`으로 확인)

```bash
# (선택) 환경변수 파일 만들기. 없으면 기본값으로 실행됩니다.
cp .env.example .env          # Windows PowerShell: copy .env.example .env

# 실행
./gradlew bootRun             # Windows: gradlew.bat bootRun
```

브라우저에서 http://localhost:3000 을 엽니다. 처음 실행할 때는 Gradle과 의존성을 내려받느라 시간이 걸립니다.

그 밖의 명령:

```bash
./gradlew test                # 통합 테스트 실행
./gradlew bootJar             # 실행 가능한 JAR 생성 → java -jar build/libs/chzzk-vod-0.1.0.jar
```

## 환경변수 예시

`.env.example` 참고. 모두 선택값이며 비밀 값은 필요하지 않습니다.
실행 폴더의 `.env` 파일을 자동으로 읽고(`application.yml`의 `spring.config.import`), 같은 이름의 OS 환경변수로 지정해도 됩니다.

```dotenv
PORT=3000            # 서버 포트
DATA_DIR=./data      # 방송 메타데이터 DB(H2 파일) 저장 폴더 (상대 경로는 ./ 로 시작)
UPLOAD_DIR=uploads   # 업로드 영상 저장 폴더
MAX_UPLOAD_MB=500    # 업로드 최대 크기(MB)
```

## 이번 단계 구현 범위

**구현함**

- 녹화 방송 등록 (`/upload.html`)
  - 제목(필수, 100자 이하), 설명(선택, 2000자 이하)
  - 영상은 두 방식 중 하나: **파일 업로드**(video/*) 또는 **저장된 영상 URL 연결**(http/https)
  - 영상 URL은 mp4·webm 등 영상 파일 주소이면 HTML5 `<video>`로, **유튜브 주소**(watch, youtu.be, shorts, live, embed)이면 유튜브 임베드 플레이어로 재생 (`t=` 시작 위치 반영)
  - 업로드 진행률 표시, 서버 입력 검증 및 오류 메시지, 거부된 업로드 파일 정리
- 방송 목록 (`/`) — 최신순, 영상 첫 프레임(유튜브는 유튜브 썸네일) 미리보기, 조회수·등록일
- 방송 상세 (`/watch.html?id=...`) — HTML5 플레이어로 재생, 제목·설명·메타 정보, 첫 재생 시 조회수 증가
- 영속 저장 — 방송 정보와 영상 위치(`videoUrl`)를 H2 파일 DB에, 업로드 파일은 `uploads/`에 저장 → 서버를 재시작해도 유지
- 업로드 영상은 Range 요청(206)을 지원해 탐색(seek) 가능
- 인증 없이 모든 화면·API 이용 가능 (Spring Security 미사용)

**이번 단계에서 하지 않음**: 인증·인가, 라이브 송출, 댓글/채팅, 도네이션, 수정·삭제, 썸네일 생성·트랜스코딩

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/broadcasts` | 방송 목록 (최신순) |
| GET | `/api/broadcasts/{id}` | 방송 상세 |
| POST | `/api/broadcasts` | 등록. `multipart/form-data`: `title`, `description?`, `video`(파일) **또는** `videoUrl` |
| POST | `/api/broadcasts/{id}/views` | 조회수 +1 |
| GET | `/media/{file}` | 업로드된 영상 파일 (Range 지원) |

오류는 `{"error": "메시지"}` 형식(400/404/500)으로 응답합니다.

응답 예 (`GET /api/broadcasts/{id}`):

```json
{
  "broadcast": {
    "id": "6f1c0d0e-…",
    "title": "11월 3일 저챗 다시보기",
    "description": "",
    "sourceType": "upload",
    "videoUrl": "/media/1762140000000-ab12cd34ef56.mp4",
    "fileName": "recording.mp4",
    "mimeType": "video/mp4",
    "size": 10485760,
    "viewCount": 0,
    "createdAt": "2026-11-03T03:20:00Z"
  }
}
```

## 프로젝트 구조

```
src/main/java/dev/chzzk/vod/
  VodApplication.java
  broadcast/
    Broadcast.java             # 방송 엔티티 (JPA)
    SourceType.java            # upload | url
    BroadcastRepository.java   # Spring Data JPA
    BroadcastService.java      # 등록 검증, 조회, 조회수
    BroadcastController.java   # REST API
    BroadcastResponse.java     # 응답 DTO
  storage/VideoStorage.java    # 업로드 파일 저장 (나중에 S3 등으로 교체 지점)
  config/WebConfig.java        # /media/** 정적 제공
  common/                      # ApiException, 전역 오류 처리
src/main/resources/
  application.yml
  static/                      # index.html(목록), watch.html(상세+플레이어), upload.html(등록)
src/test/java/.../BroadcastApiTests.java   # 등록·조회·재생·검증 통합 테스트
data/      # (실행 시 생성) H2 DB 파일
uploads/   # (실행 시 생성) 업로드된 영상
```

## 직접 확인 절차

1. `./gradlew bootRun` 후 http://localhost:3000 접속 — "등록된 방송이 없습니다" 문구가 보입니다.
2. 우측 상단 **방송 등록** → 제목 입력 → **파일 업로드** 탭에서 mp4 파일 선택 → **등록하기**.
   - 진행률이 표시되고, 완료되면 상세 화면으로 이동합니다.
3. 상세 화면에서 영상이 재생되는지, 탐색 바로 이동이 되는지 확인합니다. 재생하면 조회수가 1 오릅니다.
4. 다시 **방송 등록** → **영상 URL 연결** 탭 → 공개된 mp4 주소 입력 → 등록 후 재생을 확인합니다.
   - 예: `https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4`
   - 유튜브 주소(예: `https://www.youtube.com/watch?v=aqz-KE-bpKQ`)도 등록해 유튜브 플레이어로 재생되는지 확인합니다. 업로더가 퍼가기를 막은 영상은 유튜브 정책상 재생되지 않습니다.
5. 로고(**VOD**)를 눌러 목록으로 이동 — 등록한 두 방송이 최신순으로 보이고, 클릭하면 각 상세 화면으로 이동합니다.
6. 오류 처리: 제목 없이 등록, 영상이 아닌 파일 업로드, `ftp://...` 같은 URL 입력 시 오류 메시지가 표시되는지 확인합니다.
7. **영속성 확인**: 터미널에서 서버를 `Ctrl+C`로 종료하고 다시 `./gradlew bootRun` → 목록·상세·재생·조회수가 그대로 남아 있는지 확인합니다.
   - `data/vod.mv.db`(메타데이터)와 `uploads/`(영상 파일)가 생성된 것을 볼 수 있습니다.

API로 확인하려면:

```bash
curl -F "title=테스트 방송" -F "video=@sample.mp4;type=video/mp4" http://localhost:3000/api/broadcasts
curl -F "title=URL 방송" -F "videoUrl=https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4" http://localhost:3000/api/broadcasts
curl http://localhost:3000/api/broadcasts
```

## 다음 단계를 위한 메모

- DB 교체: `application.yml`의 `spring.datasource`만 PostgreSQL 등으로 바꾸면 됩니다.
- 파일 저장소 교체: `VideoStorage`만 S3 등으로 바꾸면 됩니다.
- 인증: Spring Security를 추가하고 등록 API에만 인증을 걸 수 있습니다.
- 라이브: 미디어 서버(SRS, MediaMTX 등)의 송출 시작/종료 콜백을 받는 API를 추가하고, 종료 후 녹화본을 지금의 등록 기능으로 넣으면 다시보기로 이어집니다.
