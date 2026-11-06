/**
 * SignalRoom —— 一个分享一个实例的房间，负责配对与转发（不碰文件字节）。
 *
 * 生命周期按 WebSocket Hibernation 写：socket 只挂在 state.acceptWebSocket 上，
 * 角色/访客 id 放在 serializeAttachment 里，所以休眠唤醒后不需要内存状态。
 * 房间状态（tokenHash、是否已撤销）落 storage，撤销后晚到的连接一律 410 / gone。
 */

import {
  MAX_SIGNAL_FRAME_BYTES,
  SIGNAL_JOIN_WINDOW_MS,
  SIGNAL_MAX_JOINS_PER_WINDOW,
  SIGNAL_ROOM_MAX_GUESTS,
} from "./limits.js";
import { constantTimeEqualHex, normalizeB32, randomShortId, tokenHashHex } from "./crypto.js";
import { json } from "./util.js";
import { upgradeResponse } from "./ws.js";

export class SignalRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.storage = state.storage;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/join") return this.join(request);
    if (url.pathname === "/status") return this.status();
    if (url.pathname === "/revoke") return this.revoke();
    return json({ ok: false, error: "unknown op" }, 404);
  }

  /* -------------------------------------------------------------- 握手 */

  async join(request) {
    const upgrade = (request.headers.get("upgrade") || "").toLowerCase();
    if (upgrade !== "websocket") return json({ ok: false, error: "websocket upgrade required" }, 426);

    const role = (request.headers.get("x-huchuan-role") || "").toLowerCase();
    if (role !== "sharer" && role !== "guest") return json({ ok: false, error: "invalid role" }, 400);

    const meta = await this.loadMeta(request);
    if (meta.revoked) return json({ ok: false, error: "revoked" }, 410);

    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];

    if (role === "guest") {
      // 同一个页面重连（ep 不变）时接管旧 socket：否则「最多 1 个访客」会把重连挡在门外。
      this.takeover(request.headers.get("x-huchuan-ep") || "");
    }

    if (role === "guest" && this.guestsInRoom().length >= SIGNAL_ROOM_MAX_GUESTS) {
      // 房间最多 1 个访客：多余的连接当场拒绝（link-share.md §12-3）
      this.state.acceptWebSocket(server, ["guest"]);
      server.serializeAttachment({ role: "guest", joined: false, visitorId: null });
      this.send(server, { t: "err", code: "busy" });
      this.shutdown(server, 4000, "busy");
      return upgradeResponse(client);
    }

    this.state.acceptWebSocket(server, [role]);
    if (role === "sharer") {
      server.serializeAttachment({ role: "sharer", dev: request.headers.get("x-huchuan-dev") || "", joined: true });
      this.replayIncoming(server, meta);
    } else {
      server.serializeAttachment({ role: "guest", ep: request.headers.get("x-huchuan-ep") || "", joined: false, visitorId: null });
    }
    return upgradeResponse(client);
  }

  async meta() {
    return (await this.storage.get("meta")) || null;
  }

  /** 房间里的访客（不含已被同一 ep 新连接接管的旧 socket）。 */
  guestsInRoom() {
    return this.state.getWebSockets("guest").filter((ws) => !(ws.deserializeAttachment() || {}).replaced);
  }

  /**
   * 接管：同一 ep 的访客又连上来（浏览器重连），旧 socket 让位，不占名额也不吃 join 限速。
   * 旧 socket 的 attachment 先标记 replaced，getWebSockets 里就立刻不算它了——
   * 不依赖 close() 何时从 getWebSockets 消失。
   */
  takeover(ep) {
    if (!ep) return;
    for (const guest of this.state.getWebSockets("guest")) {
      const attachment = guest.deserializeAttachment() || {};
      if (attachment.ep !== ep || attachment.replaced) continue;
      guest.serializeAttachment({ ...attachment, joined: false, replaced: true });
      this.shutdown(guest, 4006, "replaced");
    }
  }

  /**
   * 分享者（重）连上时，把已经在等的访客再报一遍。
   * 房间不保存 offer，但访客的 joined 状态在 attachment 里，所以重放不需要访客重新 join
   * （spike 记的缺口：分享者断线重连后访客收不到 share-incoming）。
   */
  replayIncoming(socket, meta) {
    for (const guest of this.state.getWebSockets("guest")) {
      const attachment = guest.deserializeAttachment() || {};
      if (!attachment.joined) continue;
      this.send(socket, {
        t: "share-incoming",
        shareId: (meta && meta.shareId) || "",
        visitorId: attachment.visitorId,
        epPub: attachment.epPub || null,
        resumed: true,
      });
    }
  }

  async loadMeta(request) {
    const existing = await this.storage.get("meta");
    if (existing) return existing;
    const meta = {
      shareId: request.headers.get("x-huchuan-share") || "",
      tokenHash: request.headers.get("x-huchuan-token-hash") || "",
      revoked: false,
      createdAt: Date.now(),
    };
    await this.storage.put("meta", meta);
    return meta;
  }

  /* -------------------------------------------------------------- 消息 */

  async webSocketMessage(ws, message) {
    const attachment = ws.deserializeAttachment() || {};
    const text = typeof message === "string" ? message : new TextDecoder().decode(message);
    if (text.length > MAX_SIGNAL_FRAME_BYTES) {
      this.send(ws, { t: "err", code: "too-large" });
      return;
    }
    let msg;
    try {
      msg = JSON.parse(text);
    } catch {
      return;
    }
    if (!msg || typeof msg.t !== "string") return;
    if (attachment.role === "guest") return this.guestMessage(ws, attachment, msg);
    if (attachment.role === "sharer") return this.sharerMessage(ws, attachment, msg);
  }

  async guestMessage(ws, attachment, msg) {
    if (msg.t === "share-join") {
      if (attachment.joined) return;
      const meta = await this.meta();
      if (!meta) {
        this.fail(ws, "gone");
        return;
      }
      if (meta.revoked) {
        this.fail(ws, "gone");
        return;
      }
      const token = normalizeB32(typeof msg.token === "string" ? msg.token : "");
      if (!token || token.length !== 26) {
        this.fail(ws, "gone");
        return;
      }
      const hash = await tokenHashHex(token);
      if (!constantTimeEqualHex(hash, meta.tokenHash)) {
        this.fail(ws, "gone");
        return;
      }
      if (!(await this.consumeJoinBudget())) {
        this.fail(ws, "rate");
        return;
      }
      const sharers = this.state.getWebSockets("sharer");
      if (sharers.length === 0) {
        this.send(ws, { t: "bye", code: "offline" });
        this.shutdown(ws, 4002, "offline");
        return;
      }
      attachment.joined = true;
      attachment.visitorId = randomShortId(8);
      attachment.epPub = typeof msg.epPub === "string" ? msg.epPub : null;
      ws.serializeAttachment(attachment);

      this.send(ws, { t: "joined", shareId: meta.shareId, visitorId: attachment.visitorId });
      this.send(sharers[sharers.length - 1], {
        t: "share-incoming",
        shareId: meta.shareId,
        visitorId: attachment.visitorId,
        epPub: attachment.epPub,
      });
      return;
    }

    if (msg.t === "answer" || msg.t === "ice") {
      if (!attachment.joined) return;
      const sharers = this.state.getWebSockets("sharer");
      if (sharers.length === 0) return;
      this.send(sharers[sharers.length - 1], msg);
      return;
    }

    if (msg.t === "bye") {
      const sharers = this.state.getWebSockets("sharer");
      for (const sharer of sharers) this.send(sharer, msg);
      this.shutdown(ws, 4003, "bye");
    }
  }

  sharerMessage(ws, attachment, msg) {
    if (msg.t !== "offer" && msg.t !== "ice" && msg.t !== "bye" && msg.t !== "err") return;
    for (const guest of this.state.getWebSockets("guest")) {
      const guestAttachment = guest.deserializeAttachment() || {};
      if (!guestAttachment.joined) continue;
      this.send(guest, msg);
    }
    if (msg.t === "bye") this.shutdown(ws, 4004, "bye");
  }

  async webSocketClose(ws) {
    const attachment = ws.deserializeAttachment() || {};
    const meta = await this.meta();
    if (meta && meta.revoked) return;
    if (attachment.role === "sharer") {
      // 分享者刚重连、旧 socket 才断的情况：还有别的分享者在线就不是 peer-left
      const others = this.state.getWebSockets("sharer").filter((socket) => socket !== ws);
      if (others.length > 0) return;
      for (const guest of this.state.getWebSockets("guest")) {
        this.send(guest, { t: "bye", code: "peer-left" });
      }
    } else if (attachment.role === "guest" && attachment.joined) {
      for (const sharer of this.state.getWebSockets("sharer")) {
        this.send(sharer, { t: "bye", code: "peer-left", visitorId: attachment.visitorId });
      }
    }
  }

  /* -------------------------------------------------------------- 房间操作 */

  async status() {
    const meta = await this.meta();
    const guests = this.state.getWebSockets("guest").filter((ws) => {
      const attachment = ws.deserializeAttachment() || {};
      return !!attachment.joined;
    });
    return json({
      ok: true,
      shareId: meta ? meta.shareId : "",
      revoked: !!(meta && meta.revoked),
      sharer: this.state.getWebSockets("sharer").length > 0,
      guest: guests.length > 0,
    });
  }

  async revoke() {
    const existing = (await this.meta()) || { shareId: "", tokenHash: "", revoked: false };
    await this.storage.put("meta", { ...existing, revoked: true, revokedAt: Date.now() });
    const sockets = this.state.getWebSockets();
    for (const ws of sockets) {
      this.send(ws, { t: "bye", code: "gone" });
      this.shutdown(ws, 4001, "gone");
    }
    return json({ ok: true, revoked: true, closed: sockets.length });
  }

  /* -------------------------------------------------------------- 工具 */

  async consumeJoinBudget() {
    const now = Date.now();
    const log = ((await this.storage.get("joins")) || []).filter((stamp) => now - stamp < SIGNAL_JOIN_WINDOW_MS);
    if (log.length >= SIGNAL_MAX_JOINS_PER_WINDOW) return false;
    log.push(now);
    await this.storage.put("joins", log);
    return true;
  }

  send(ws, object) {
    try {
      ws.send(JSON.stringify(object));
    } catch {
      /* socket 已关 */
    }
  }

  shutdown(ws, code, reason) {
    try {
      ws.close(code, reason);
    } catch {
      /* 已经关了 */
    }
  }

  fail(ws, code) {
    this.send(ws, { t: "err", code });
    this.shutdown(ws, 4005, code);
  }
}
