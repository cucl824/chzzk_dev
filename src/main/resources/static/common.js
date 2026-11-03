// 페이지 공통 유틸
async function api(path, options) {
  const res = await fetch(path, options);
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body.error || `요청 실패 (${res.status})`);
  return body;
}

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
