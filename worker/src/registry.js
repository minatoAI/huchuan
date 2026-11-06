/**
 * ShareRegistry —— 分享注册表（单实例 DO，按 link-share.md §4.3 的「注册表」）。
 *
 * 只存元信息：token 的哈希、群发者公钥、文件清单、有效期、撤销状态、被访问次数。
 * 字节永远不经过这里（link-share.md §4.3 最后一条）。
 */

import { MAX_ACTIVE_SHARES_PER_DEVICE } from "./limits.js";
import { json } from "./util.js";

const MAX_DEVICE_INDEX = 200;

export class ShareRegistry {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.storage = state.storage;
  }

  async fetch(request) {
    const url = new URL(request.url);
    switch (url.pathname) {
      case "/create":
        return this.create(await request.json());
      case "/get":
        return this.get(url);
      case "/revoke":
        return this.revoke(await request.json());
      case "/touch":
        return this.touch(await request.json());
      case "/list":
        return this.list(url);
      default:
        return json({ ok: false, error: "unknown op" }, 404);
    }
  }

  async create(payload) {
    const now = payload.createdAt || Date.now();
    if (await this.storage.get("t:" + payload.tokenHash)) {
      return json({ ok: false, error: "token-taken" });
    }
    if (await this.storage.get("s:" + payload.shareId)) {
      return json({ ok: false, error: "duplicate" });
    }

    const indexKey = "d:" + payload.deviceId;
    const ids = (await this.storage.get(indexKey)) || [];
    let active = 0;
    for (const id of ids) {
      const existing = await this.storage.get("s:" + id);
      if (existing && existing.state === "active" && existing.expiresAt > now) active += 1;
    }
    if (active >= MAX_ACTIVE_SHARES_PER_DEVICE) {
      return json({ ok: false, error: "too-many" });
    }

    const record = {
      shareId: payload.shareId,
      tokenHash: payload.tokenHash,
      deviceId: payload.deviceId,
      pub: payload.pub,
      files: payload.files,
      createdAt: now,
      expiresAt: payload.expiresAt,
      state: "active",
      revokedAt: null,
      visitCount: 0,
    };
    await this.storage.put("s:" + record.shareId, record);
    await this.storage.put("t:" + record.tokenHash, record.shareId);
    const nextIndex = [record.shareId, ...ids.filter((id) => id !== record.shareId)].slice(0, MAX_DEVICE_INDEX);
    await this.storage.put(indexKey, nextIndex);
    return json({ ok: true, record });
  }

  async get(url) {
    const shareId = url.searchParams.get("shareId");
    const tokenHash = url.searchParams.get("tokenHash");
    let record = null;
    if (shareId) {
      record = await this.storage.get("s:" + shareId);
    } else if (tokenHash) {
      const id = await this.storage.get("t:" + tokenHash);
      if (id) record = await this.storage.get("s:" + id);
    } else {
      return json({ ok: false, error: "missing key" }, 400);
    }
    return json({ ok: true, record: record || null });
  }

  async revoke(payload) {
    const record = await this.storage.get("s:" + payload.shareId);
    if (!record) return json({ ok: false, error: "not-found" }, 404);
    if (record.state === "revoked") {
      return json({ ok: true, alreadyRevoked: true, revokedAt: record.revokedAt, record });
    }
    const revokedAt = payload.now || Date.now();
    const next = { ...record, state: "revoked", revokedAt };
    await this.storage.put("s:" + next.shareId, next);
    return json({ ok: true, revokedAt, record: next });
  }

  async touch(payload) {
    const record = await this.storage.get("s:" + payload.shareId);
    if (!record) return json({ ok: false, error: "not-found" }, 404);
    const next = { ...record, visitCount: (record.visitCount || 0) + 1 };
    await this.storage.put("s:" + next.shareId, next);
    return json({ ok: true, visitCount: next.visitCount });
  }

  async list(url) {
    const deviceId = url.searchParams.get("deviceId") || "";
    const now = Number(url.searchParams.get("now")) || Date.now();
    const ids = (await this.storage.get("d:" + deviceId)) || [];
    const shares = [];
    for (const id of ids) {
      const record = await this.storage.get("s:" + id);
      if (!record) continue;
      if (record.state !== "active" || record.expiresAt <= now) continue;
      shares.push(record);
    }
    shares.sort((a, b) => b.createdAt - a.createdAt);
    return json({ ok: true, shares });
  }
}
