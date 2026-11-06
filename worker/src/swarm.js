/**
 * TrackerSwarm —— 一个 groupHash20 一个实例，存群注册信息与 peer 列表。
 *
 * DO 内部只用 KV 接口（SQLite 后端也支持），所以本文件能被内存 shim 直接跑起来。
 * peer 不落 token/passkey 原文：注册信息里只有 passkey 的哈希。
 */

import { constantTimeEqualHex } from "./crypto.js";
import {
  MAX_PEERS_PER_SWARM,
  PEER_TTL_SEC,
} from "./limits.js";
import { json } from "./util.js";

export class TrackerSwarm {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.storage = state.storage;
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/announce" && request.method === "POST") {
      return this.announce(await request.json());
    }
    if (url.pathname === "/register" && request.method === "POST") {
      return this.register(await request.json());
    }
    return json({ ok: false, reason: "unknown op" }, 404);
  }

  async register(payload) {
    const now = Date.now();
    const existing = await this.storage.get("group");
    if (!existing) {
      await this.storage.put("group", {
        infoHash: payload.infoHash,
        gid: payload.gid,
        passKeyHash: payload.passKeyHash,
        createdAt: now,
      });
      return json({ ok: true, created: true });
    }
    if (constantTimeEqualHex(existing.passKeyHash, payload.passKeyHash)) {
      return json({ ok: true, created: false });
    }
    if (payload.prevPassKeyHash && constantTimeEqualHex(existing.passKeyHash, payload.prevPassKeyHash)) {
      await this.storage.put("group", { ...existing, gid: payload.gid, passKeyHash: payload.passKeyHash, rotatedAt: now });
      return json({ ok: true, rotated: true });
    }
    return json({
      ok: false,
      error: "conflict",
      reason: "group already registered; pass prevPasskey to rotate",
    });
  }

  async announce(payload) {
    const group = await this.storage.get("group");
    if (!group) return json({ ok: false, reason: "info hash not registered" });
    if (!constantTimeEqualHex(group.passKeyHash, payload.passKeyHash)) {
      return json({ ok: false, reason: "bad passkey" });
    }

    const now = payload.now || Date.now();
    const ttlMs = PEER_TTL_SEC * 1000;
    let peers = (await this.storage.get("peers")) || [];
    peers = peers.filter((peer) => now - peer.last <= ttlMs);

    const selfKey = peerKey({ peerId: payload.peerId, ip: payload.peer.ip, port: payload.peer.port });
    const before = peers.filter((peer) => peerKey(peer) !== selfKey);

    if (payload.event !== "stopped") {
      before.push({
        peerId: payload.peerId || null,
        ip: payload.peer.ip,
        port: payload.peer.port,
        family: payload.peer.family,
        last: now,
      });
    }
    peers = before.length > MAX_PEERS_PER_SWARM ? before.slice(-MAX_PEERS_PER_SWARM) : before;
    await this.storage.put("peers", peers);

    const others = peers.filter((peer) => peerKey(peer) !== selfKey);
    const sorted = [...others].sort((a, b) => b.last - a.last);
    const picked = sorted.slice(0, Math.max(0, payload.numwant | 0));

    return json({
      ok: true,
      complete: peers.length,
      incomplete: 0,
      peers: picked.map((peer) => ({ ip: peer.ip, port: peer.port, family: peer.family, peerId: peer.peerId })),
    });
  }
}

function peerKey(peer) {
  if (peer.peerId) return "p:" + peer.peerId;
  return "a:" + peer.ip + ":" + peer.port;
}
