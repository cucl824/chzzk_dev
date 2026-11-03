# VOD 플랫폼 — 1단계: 녹화 방송 등록·시청

치지직 같은 방송 플랫폼을 단계별로 만드는 프로젝트의 첫 단계입니다.
**로그인 없이** 녹화된 방송(다시보기)을 등록하고, 목록에서 골라 상세 화면에서 재생합니다.

## 기술 스택

| 구분 | 사용 |
| --- | --- |
| 서버 | Node.js 18+, Express 4 |
| 업로드 | multer (디스크 저장) |
| 저장소 | JSON 파일 (`data/broadcasts.json`, 원자적 쓰기) |
| 화면 | 정적 HTML + 바닐라 JS + HTML5 `<video>` |

빌드 단계나 네이티브 모듈이 없어 `npm install` 후 바로 실행됩니다.

## 실행 방법

```bash
npm install
cp .env.example .env   # 선택. 없으면 기본값으로 실행됩니다. (Windows PowerShell: copy .env.example .env)
npm start
```

브라우저에서 http://localhost:3000 을 엽니다.

개발 중 코드 변경 시 자동 재시작: `npm run dev`

## 환경변수 예시

`.env.example` 참고. 모두 선택값이며 비밀 값은 필요하지 않습니다.

```dotenv
PORT=3000            # 서버 포트
DATA_DIR=data        # 방송 메타데이터(broadcasts.json) 저장 폴더
UPLOAD_DIR=uploads   # 업로드 영상 저장 폴더
MAX_UPLOAD_MB=500    # 업로드 최대 크기(MB)
```

## 이번 단계 구현 범위

**구현함**

- 녹화 방송 등록 (`/upload.html`)
  - 제목(필수, 100자 이하), 설명(선택, 2000자 이하)
  - 영상은 두 방식 중 하나: **파일 업로드**(video/*) 또는 **저장된 영상 URL 연결**(http/https)
  - 업로드 진행률 표시, 입력 검증 및 오류 메시지
- 방송 목록 (`/`) — 최신순, 영상 첫 프레임 미리보기, 조회수·등록일
- 방송 상세 (`/watch.html?id=...`) — HTML5 플레이어로 재생, 제목·설명·메타 정보, 첫 재생 시 조회수 증가
- 영속 저장 — 방송 정보와 영상 위치를 `data/broadcasts.json`에, 업로드 파일은 `uploads/`에 저장 → 서버를 재시작해도 유지
- 업로드 영상은 Range 요청을 지원해 탐색(seek) 가능
- 인증 없이 모든 화면·API 이용 가능

**이번 단계에서 하지 않음**: 인증·인가, 라이브 송출, 댓글/채팅, 도네이션, 수정·삭제, 썸네일 생성·트랜스코딩

## API

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | `/api/broadcasts` | 방송 목록 (최신순) |
| GET | `/api/broadcasts/:id` | 방송 상세 |
| POST | `/api/broadcasts` | 등록. `multipart/form-data`: `title`, `description?`, `video`(파일) **또는** `videoUrl` |
| POST | `/api/broadcasts/:id/views` | 조회수 +1 |
| GET | `/media/:file` | 업로드된 영상 파일 (Range 지원) |

저장되는 방송 데이터 예:

```json
{
  "id": "6f1c…",
  "title": "11월 3일 저챗 다시보기",
  "description": "",
  "sourceType": "upload",
  "videoUrl": "/media/1762140000000-ab12cd34ef56.mp4",
  "fileName": "recording.mp4",
  "mimeType": "video/mp4",
  "size": 10485760,
  "viewCount": 0,
  "createdAt": "2026-11-03T03:20:00.000Z"
}
```

## 프로젝트 구조

```
src/
  server.js    # Express 서버, API, 업로드 처리, 정적 파일 제공
  store.js     # 방송 메타데이터 저장소 (JSON 파일 영속화)
public/
  index.html   # 방송 목록
  watch.html   # 방송 상세 + 영상 플레이어
  upload.html  # 방송 등록 (파일 업로드 / URL 연결)
  common.js    # 공통 유틸
  style.css
data/          # (실행 시 생성) broadcasts.json
uploads/       # (실행 시 생성) 업로드된 영상
```

## 직접 확인 절차

1. `npm install` → `npm start` 후 http://localhost:3000 접속 — "등록된 방송이 없습니다" 문구가 보입니다.
2. 우측 상단 **방송 등록** → 제목 입력 → **파일 업로드** 탭에서 mp4 파일 선택 → **등록하기**.
   - 진행률이 표시되고, 완료되면 상세 화면으로 이동합니다.
3. 상세 화면에서 영상이 재생되는지, 탐색 바로 이동이 되는지 확인합니다. 재생하면 조회수가 1 오릅니다.
4. 다시 **방송 등록** → **영상 URL 연결** 탭 → 공개된 mp4 주소 입력 → 등록 후 재생을 확인합니다.
   - 예: `https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4`
5. 로고(**VOD**)를 눌러 목록으로 이동 — 등록한 두 방송이 최신순으로 보이고, 클릭하면 각 상세 화면으로 이동합니다.
6. 오류 처리: 제목 없이 등록, 영상 파일이 아닌 파일 업로드, `ftp://...` 같은 URL 입력 시 오류 메시지가 표시되는지 확인합니다.
7. **영속성 확인**: 터미널에서 서버를 `Ctrl+C`로 종료하고 다시 `npm start` → 목록·상세·재생·조회수가 그대로 남아 있는지 확인합니다.
   - `data/broadcasts.json` 파일에서 저장된 내용을 직접 볼 수도 있습니다.

API로 확인하려면:

```bash
curl -F "title=테스트 방송" -F "video=@./sample.mp4" http://localhost:3000/api/broadcasts
curl -F "title=URL 방송" -F "videoUrl=https://interactive-examples.mdn.mozilla.net/media/cc0-videos/flower.mp4" http://localhost:3000/api/broadcasts
curl http://localhost:3000/api/broadcasts
```

## 다음 단계를 위한 메모

- `src/store.js`는 `list / get / create / incrementView` 인터페이스만 노출하므로 DB로 교체하기 쉽습니다.
- 인증이 추가되면 `server.js`의 등록 라우트 앞에 인증 미들웨어를 붙이면 됩니다.
