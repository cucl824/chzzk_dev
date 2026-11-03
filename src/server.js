require('dotenv').config();

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const express = require('express');
const multer = require('multer');
const { BroadcastStore } = require('./store');

const ROOT = path.resolve(__dirname, '..');
const PORT = Number(process.env.PORT) || 3000;
const DATA_DIR = path.resolve(ROOT, process.env.DATA_DIR || 'data');
const UPLOAD_DIR = path.resolve(ROOT, process.env.UPLOAD_DIR || 'uploads');
const MAX_UPLOAD_MB = Number(process.env.MAX_UPLOAD_MB) || 500;
const TITLE_MAX = 100;
const DESCRIPTION_MAX = 2000;

fs.mkdirSync(UPLOAD_DIR, { recursive: true });
const store = new BroadcastStore(path.join(DATA_DIR, 'broadcasts.json'));

const upload = multer({
  storage: multer.diskStorage({
    destination: UPLOAD_DIR,
    filename: (req, file, cb) => {
      const ext = path.extname(file.originalname).toLowerCase().replace(/[^.a-z0-9]/g, '');
      cb(null, `${Date.now()}-${crypto.randomBytes(6).toString('hex')}${ext}`);
    },
  }),
  limits: { fileSize: MAX_UPLOAD_MB * 1024 * 1024 },
  fileFilter: (req, file, cb) => {
    if (file.mimetype.startsWith('video/')) return cb(null, true);
    cb(new HttpError(400, '영상 파일(video/*)만 업로드할 수 있습니다.'));
  },
});

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

function isHttpUrl(value) {
  try {
    const url = new URL(value);
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
}

function removeFile(file) {
  if (file) fs.promises.unlink(file.path).catch(() => {});
}

const app = express();
app.use(express.json());

// 이번 단계는 로그인 없이 누구나 등록·조회할 수 있다. (인증 미들웨어 없음)

app.get('/api/broadcasts', (req, res) => {
  res.json({ broadcasts: store.list() });
});

app.get('/api/broadcasts/:id', (req, res, next) => {
  const broadcast = store.get(req.params.id);
  if (!broadcast) return next(new HttpError(404, '방송을 찾을 수 없습니다.'));
  res.json({ broadcast });
});

// 등록: multipart/form-data
//  - title (필수), description (선택)
//  - video (파일 업로드) 또는 videoUrl (저장된 영상 URL 연결) 중 하나
app.post('/api/broadcasts', upload.single('video'), async (req, res, next) => {
  const file = req.file;
  try {
    const title = String(req.body.title || '').trim();
    const description = String(req.body.description || '').trim();
    const videoUrl = String(req.body.videoUrl || '').trim();

    if (!title) throw new HttpError(400, '제목을 입력하세요.');
    if (title.length > TITLE_MAX) throw new HttpError(400, `제목은 ${TITLE_MAX}자 이하로 입력하세요.`);
    if (description.length > DESCRIPTION_MAX) {
      throw new HttpError(400, `설명은 ${DESCRIPTION_MAX}자 이하로 입력하세요.`);
    }
    if (file && videoUrl) throw new HttpError(400, '영상 파일과 영상 URL 중 하나만 입력하세요.');
    if (!file && !videoUrl) throw new HttpError(400, '영상 파일을 올리거나 영상 URL을 입력하세요.');
    if (videoUrl && !isHttpUrl(videoUrl)) throw new HttpError(400, '영상 URL은 http(s):// 로 시작해야 합니다.');

    const broadcast = file
      ? await store.create({
          title,
          description,
          sourceType: 'upload',
          videoUrl: `/media/${file.filename}`,
          // multer 는 파일명을 latin1 로 해석하므로 한글 파일명을 UTF-8 로 되돌린다.
          fileName: Buffer.from(file.originalname, 'latin1').toString('utf8'),
          mimeType: file.mimetype,
          size: file.size,
        })
      : await store.create({ title, description, sourceType: 'url', videoUrl });

    res.status(201).json({ broadcast });
  } catch (err) {
    removeFile(file);
    next(err);
  }
});

app.post('/api/broadcasts/:id/views', async (req, res, next) => {
  try {
    const broadcast = await store.incrementView(req.params.id);
    if (!broadcast) throw new HttpError(404, '방송을 찾을 수 없습니다.');
    res.json({ viewCount: broadcast.viewCount });
  } catch (err) {
    next(err);
  }
});

app.use('/api', (req, res, next) => next(new HttpError(404, '존재하지 않는 API입니다.')));

// 업로드된 영상. express.static 이 Range 요청을 지원하므로 탐색(seek)이 가능하다.
app.use('/media', express.static(UPLOAD_DIR, { fallthrough: false }));
app.use(express.static(path.join(ROOT, 'public')));

// eslint-disable-next-line no-unused-vars
app.use((err, req, res, next) => {
  if (err instanceof multer.MulterError) {
    const message =
      err.code === 'LIMIT_FILE_SIZE' ? `파일은 ${MAX_UPLOAD_MB}MB 이하만 업로드할 수 있습니다.` : err.message;
    return res.status(400).json({ error: message });
  }
  const status = err.status || err.statusCode || 500;
  if (status >= 500) console.error(err);
  res.status(status).json({ error: status >= 500 ? '서버 오류가 발생했습니다.' : err.message });
});

app.listen(PORT, () => {
  console.log(`VOD 서버 실행 중: http://localhost:${PORT}`);
  console.log(`메타데이터: ${store.filePath}`);
  console.log(`업로드 폴더: ${UPLOAD_DIR}`);
});
