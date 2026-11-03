// 방송 메타데이터 저장소.
// JSON 파일 하나에 영속 저장하고, 쓰기는 임시 파일 → rename 으로 원자적으로 처리한다.
// 다음 단계에서 DB(SQLite/PostgreSQL 등)로 바꾸더라도 이 모듈의 인터페이스만 유지하면 된다.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

class BroadcastStore {
  constructor(filePath) {
    this.filePath = filePath;
    this.items = [];
    this.writeQueue = Promise.resolve();
    this.load();
  }

  load() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true });
    if (!fs.existsSync(this.filePath)) {
      this.items = [];
      this.flushSync();
      return;
    }
    const raw = fs.readFileSync(this.filePath, 'utf8');
    const parsed = raw.trim() ? JSON.parse(raw) : { broadcasts: [] };
    this.items = Array.isArray(parsed.broadcasts) ? parsed.broadcasts : [];
  }

  serialize() {
    return JSON.stringify({ broadcasts: this.items }, null, 2);
  }

  flushSync() {
    const tmp = `${this.filePath}.tmp`;
    fs.writeFileSync(tmp, this.serialize());
    fs.renameSync(tmp, this.filePath);
  }

  // 동시 요청이 와도 파일 쓰기가 겹치지 않도록 순서대로 처리한다.
  flush() {
    this.writeQueue = this.writeQueue.then(async () => {
      const tmp = `${this.filePath}.tmp`;
      await fs.promises.writeFile(tmp, this.serialize());
      await fs.promises.rename(tmp, this.filePath);
    });
    return this.writeQueue;
  }

  list() {
    return [...this.items].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  }

  get(id) {
    return this.items.find((item) => item.id === id) || null;
  }

  async create({ title, description, sourceType, videoUrl, fileName, mimeType, size }) {
    const broadcast = {
      id: crypto.randomUUID(),
      title,
      description: description || '',
      sourceType, // 'upload' | 'url'
      videoUrl, // 재생에 사용할 영상 위치 (업로드면 /media/..., 연결이면 외부 URL)
      fileName: fileName || null,
      mimeType: mimeType || null,
      size: size ?? null,
      viewCount: 0,
      createdAt: new Date().toISOString(),
    };
    this.items.push(broadcast);
    await this.flush();
    return broadcast;
  }

  async incrementView(id) {
    const item = this.get(id);
    if (!item) return null;
    item.viewCount += 1;
    await this.flush();
    return item;
  }
}

module.exports = { BroadcastStore };
