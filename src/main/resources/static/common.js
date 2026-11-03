// 페이지 공통 유틸

// 서버가 내려준 CSRF 토큰 쿠키. 등록·수정·삭제 요청에 X-XSRF-TOKEN 헤더로 보낸다.
function csrfToken() {
  const m = document.cookie.match(/(?:^|;\s*)XSRF-TOKEN=([^;]*)/);
  return m ? decodeURIComponent(m[1]) : '';
}

async function api(path, options = {}) {
  const method = (options.method || 'GET').toUpperCase();
  const headers = { ...(options.headers || {}) };
  if (method !== 'GET') {
    // 로그아웃 직후처럼 토큰 쿠키가 없으면 아무 GET 요청으로 새 토큰을 받아 온다.
    if (!csrfToken()) await fetch('/api/auth/me', { credentials: 'same-origin' });
    headers['X-XSRF-TOKEN'] = csrfToken();
  }
  const res = await fetch(path, { ...options, headers, credentials: 'same-origin' });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) {
    const err = new Error(body.error || `요청 실패 (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return body;
}

function jsonRequest(method, data) {
  return { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(data) };
}

// ---- 로그인 상태 ----

let mePromise = null;
// 로그인한 사용자 { id, nickname } 또는 null. 화면 표시용이며 권한 검사는 서버가 한다.
function currentUser() {
  mePromise ||= api('/api/auth/me').then((r) => r.user).catch(() => null);
  return mePromise;
}

function loginUrl() {
  return `/login.html?next=${encodeURIComponent(location.pathname + location.search)}`;
}

// 로그인이 필요한 화면: 비로그인이면 로그인 화면으로 보낸다.
async function requireLogin() {
  const user = await currentUser();
  if (!user) location.href = loginUrl();
  return user;
}

// 헤더 오른쪽에 "닉네임 · 로그아웃" 또는 "로그인" 을 붙인다.
async function renderAuthArea() {
  const actions = document.querySelector('header .header-actions') || document.querySelector('header');
  if (!actions || document.getElementById('authArea')) return;
  const area = el('div', { id: 'authArea', class: 'auth-area' });
  actions.append(area);
  const user = await currentUser();
  if (!user) {
    area.append(el('a', { class: 'btn secondary', href: loginUrl() }, '로그인'));
    return;
  }
  const logout = el('button', { type: 'button', class: 'btn secondary' }, '로그아웃');
  logout.addEventListener('click', async () => {
    try { await api('/api/auth/logout', { method: 'POST' }); } finally { location.reload(); }
  });
  area.append(el('span', { class: 'me', title: '로그인한 사용자' }, user.nickname), logout);
}
renderAuthArea();

// 사용자 입력은 항상 텍스트 노드로 넣어 XSS를 막는다.
function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs)) {
    if (key === 'class') node.className = value;
    else if (key in node) node[key] = value;
    else node.setAttribute(key, value);
  }
  for (const child of children) node.append(child);
  return node;
}

// 유튜브 주소는 영상 파일이 아니라 웹페이지라서 <video>로 재생할 수 없다.
// 영상 ID를 뽑아 유튜브 임베드 플레이어(iframe)로 재생한다. 유튜브가 아니면 null.
function youtubeId(url) {
  let u;
  try { u = new URL(url); } catch { return null; }
  const host = u.hostname.replace(/^(www\.|m\.|music\.)/, '');
  let id = null;
  if (host === 'youtu.be') {
    id = u.pathname.split('/')[1];
  } else if (host === 'youtube.com' || host === 'youtube-nocookie.com') {
    const [, first, second] = u.pathname.split('/');
    id = first === 'watch' ? u.searchParams.get('v')
      : ['embed', 'shorts', 'live', 'v'].includes(first) ? second
      : null;
  }
  return id && /^[A-Za-z0-9_-]{11}$/.test(id) ? id : null;
}

// 유튜브 주소의 t=90, t=1m30s 같은 시작 위치를 초 단위로 바꾼다.
function youtubeStart(url) {
  const t = new URL(url).searchParams.get('t') || '';
  const m = t.match(/^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$/);
  if (!m) return 0;
  return (Number(m[1] || 0) * 3600) + (Number(m[2] || 0) * 60) + Number(m[3] || 0);
}

function formatDate(iso) {
  return new Date(iso).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });
}

function formatSize(bytes) {
  if (bytes == null) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  let i = 0;
  while (bytes >= 1024 && i < units.length - 1) { bytes /= 1024; i++; }
  return `${bytes.toFixed(i ? 1 : 0)}${units[i]}`;
}

// ---- 라이브 공통 ----

// 현재 페이지가 https 면 wss, 아니면 ws 로 같은 서버에 접속한다.
function wsUrl(path) {
  return `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}${path}`;
}

// 서버가 내려주는 STUN/TURN 설정 (RTCPeerConnection 생성용)
async function rtcConfig() {
  try {
    return await api('/api/rtc-config');
  } catch {
    return { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };
  }
}

// 01:23:45 형식의 방송 경과 시간
function formatElapsed(fromIso, toIso) {
  const sec = Math.max(0, Math.floor(((toIso ? new Date(toIso) : new Date()) - new Date(fromIso)) / 1000));
  const pad = (n) => String(n).padStart(2, '0');
  return `${pad(Math.floor(sec / 3600))}:${pad(Math.floor(sec / 60) % 60)}:${pad(sec % 60)}`;
}

// 방송 상태 배지: live → 빨간 LIVE, ended → 회색 종료
function statusBadge(status) {
  return status === 'live'
    ? el('span', { class: 'badge live' }, '● LIVE')
    : el('span', { class: 'badge ended' }, '종료');
}

// ---- 댓글 ----

// 방송별 댓글 목록 + 작성 폼. 목록은 누구나 보고, 작성은 로그인 사용자만, 수정·삭제는 내 댓글에만 버튼이 나온다.
// (버튼은 편의용이고, 실제 권한은 서버가 401/403 으로 검사한다)
// basePath: '/api/broadcasts/{id}' 또는 '/api/lives/{id}', pollMs: 라이브처럼 주기적으로 새로 불러올 때
async function mountComments(container, basePath, { pollMs = 0 } = {}) {
  const user = await currentUser();
  const title = el('h2', { class: 'section-title' }, '댓글');
  const list = el('ul', { class: 'comments' });
  const error = el('p', { class: 'error', hidden: true });
  let editing = false;

  let form;
  if (user) {
    const input = el('textarea', { name: 'content', maxLength: 500, placeholder: `${user.nickname}(으)로 댓글 남기기`, rows: 2 });
    const submit = el('button', { type: 'submit', class: 'btn' }, '등록');
    form = el('form', { class: 'comment-form' }, input, el('div', {}, submit));
    form.addEventListener('submit', async (e) => {
      e.preventDefault();
      error.hidden = true;
      submit.disabled = true;
      try {
        await api(`${basePath}/comments`, jsonRequest('POST', { content: input.value }));
        input.value = '';
        await load();
      } catch (err) {
        showError(err);
      } finally {
        submit.disabled = false;
      }
    });
  } else {
    form = el('p', { class: 'muted' }, '댓글을 쓰려면 ', el('a', { href: loginUrl(), class: 'link' }, '로그인'), '하세요.');
  }
  container.replaceChildren(title, form, error, list);

  function showError(err) {
    error.textContent = err.status === 401 ? '로그인이 필요합니다. 다시 로그인해 주세요.' : err.message;
    error.hidden = false;
  }

  function item(c) {
    const mine = user && c.author?.id === user.id;
    const meta = el('div', { class: 'comment-meta' },
      el('b', {}, c.author?.nickname || '알 수 없음'),
      ` · ${formatDate(c.createdAt)}${c.updatedAt ? ' (수정됨)' : ''}`);
    const content = el('div', { class: 'comment-content' }, c.content);
    const li = el('li', { class: 'comment' }, meta, content);
    if (!mine) return li;

    const editBtn = el('button', { type: 'button', class: 'link' }, '수정');
    const deleteBtn = el('button', { type: 'button', class: 'link' }, '삭제');
    meta.append(' ', editBtn, ' ', deleteBtn);
    deleteBtn.addEventListener('click', async () => {
      if (!confirm('댓글을 삭제할까요?')) return;
      try { await api(`/api/comments/${c.id}`, { method: 'DELETE' }); await load(); } catch (err) { showError(err); }
    });
    editBtn.addEventListener('click', () => {
      editing = true;
      const box = el('textarea', { maxLength: 500, rows: 2, value: c.content });
      const save = el('button', { type: 'button', class: 'btn' }, '저장');
      const cancel = el('button', { type: 'button', class: 'btn secondary' }, '취소');
      content.replaceChildren(box, el('div', { class: 'row' }, save, cancel));
      cancel.addEventListener('click', () => { editing = false; load(); });
      save.addEventListener('click', async () => {
        try {
          await api(`/api/comments/${c.id}`, jsonRequest('PATCH', { content: box.value }));
          editing = false;
          await load();
        } catch (err) { showError(err); }
      });
    });
    return li;
  }

  async function load() {
    if (editing) return;
    try {
      const { comments } = await api(`${basePath}/comments`);
      title.textContent = `댓글 ${comments.length}`;
      list.replaceChildren(...(comments.length ? comments.map(item) : [el('li', { class: 'muted' }, '아직 댓글이 없습니다.')]));
    } catch (err) {
      if (err.status !== 404) showError(err);
    }
  }

  await load();
  if (pollMs) setInterval(load, pollMs);
}
