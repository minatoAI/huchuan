/**
 * GET /announce —— BEP 3 tracker（protocol.md §3.2）。
 *
 * 只服务我们自己的群：info_hash 槽位是 groupHash20（群级「伪 swarm」），
 * 没注册过的 info_hash 一律拒绝，所以这个端点不会被当成公开 tracker 用。
 *
 * 参数：info_hash(20B) / peer_id(20B，可选) / port / passkey（?passkey= 或 /announce/<passkey>）
 *       event=started|stopped|completed|none|paused / compact=0|1 / numwant
 * 应答：d8:intervali600e12:min intervali300e8:completeiNe10:incompleteiNe5:peers…6:peers6…e
 * 错误一律 HTTP 200 + {"failure reason"}（tracker 惯例，客户端读 fake 字段）。
 */

import { bencode } from "./bencode.js";
import { bytesToHex, passkeyHashHex } from "./crypto.js";
import {
  ANNOUNCE_INTERVAL_SEC,
  ANNOUNCE_MAX_INTERVAL_SEC,
  ANNOUNCE_MIN_INTERVAL_SEC,
  ANNOUNCE_NUMWANT_DEFAULT,
  ANNOUNCE_NUMWANT_MAX,
} from "./limits.js";
import { clientAddress, ipv4ToBytes, ipv6ToBytes } from "./util.js";

const ALLOWED_EVENTS = new Set(["none", "started", "stopped", "completed", "paused"]);

export async function handleAnnounce(request, env) {
  const url = new URL(request.url);
  const pairs = parseRawQuery(url.search);

  const infoHash = firstBytes(pairs, "info_hash");
  if (!infoHash || infoHash.length !== 20) return failure("invalid info_hash");

  const peerId = firstBytes(pairs, "peer_id");
  if (peerId && peerId.length !== 20) return failure("invalid peer_id");

  const port = firstInt(pairs, "port");
  if (!port || port < 1 || port > 65535) return failure("invalid port");

  const pathPasskey = passkeyFromPath(url.pathname);
  const queryPasskey = firstText(pairs, "passkey");
  const passkey = (pathPasskey || queryPasskey || "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(passkey)) return failure("missing or malformed passkey");

  const event = (firstText(pairs, "event") || "none").toLowerCase();
  if (!ALLOWED_EVENTS.has(event)) return failure("invalid event");

  const numericWant = firstInt(pairs, "numwant");
  const numwant = clampNumwant(numericWant);
  const compact = firstText(pairs, "compact") !== "0";

  const client = clientAddress(request);
  if (!client) return failure("unable to determine client address");

  const interval = announceInterval(env);
  const passKeyHash = await passkeyHashHex(passkey);
  const swarm = env.SWARM.get(env.SWARM.idFromName(bytesToHex(infoHash)));
  const reply = await swarm.fetch("https://swarm.internal/announce", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      infoHash: bytesToHex(infoHash),
      peerId: peerId ? bytesToHex(peerId) : null,
      peer: { ip: client.ip, port, family: client.family },
      passKeyHash,
      event,
      numwant,
      now: Date.now(),
    }),
  });

  const data = await reply.json().catch(() => null);
  if (!data || data.ok !== true) return failure((data && data.reason) || "internal error");

  console.log(JSON.stringify({ ev: "announce", ok: true, event, peers: (data.peers || []).length }));

  const body = {
    interval,
    "min interval": ANNOUNCE_MIN_INTERVAL_SEC,
    complete: data.complete,
    incomplete: data.incomplete,
    peers: compact ? compactV4(data.peers || []) : listPeers(data.peers || [], 4),
    peers6: compact ? compactV6(data.peers || []) : listPeers(data.peers || [], 6),
  };
  if (client.ip) body["external ip"] = client.ip;

  return new Response(bencode(body), {
    status: 200,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
  });
}

function announceInterval(env) {
  const configured = Number(env && env.ANNOUNCE_INTERVAL_SEC);
  if (!Number.isFinite(configured) || configured <= 0) return ANNOUNCE_INTERVAL_SEC;
  return Math.min(Math.max(Math.floor(configured), ANNOUNCE_INTERVAL_SEC), ANNOUNCE_MAX_INTERVAL_SEC);
}

function clampNumwant(value) {
  if (value === null || value === undefined || value < 0) return ANNOUNCE_NUMWANT_DEFAULT;
  return Math.min(value, ANNOUNCE_NUMWANT_MAX);
}

function failure(reason) {
  // 结构化日志：只打结果与原因，不打 passkey / info_hash 全文 / 来源地址（link-share.md §7）
  console.log(JSON.stringify({ ev: "announce", ok: false, reason }));
  return new Response(bencode({ "failure reason": reason }), {
    status: 200,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" },
  });
}

function compactV4(peers) {
  const list = peers.filter((p) => p.family === 4);
  const out = new Uint8Array(list.length * 6);
  let offset = 0;
  for (const peer of list) {
    const bytes = ipv4ToBytes(peer.ip);
    if (!bytes) continue;
    out.set(bytes, offset);
    out[offset + 4] = (peer.port >> 8) & 0xff;
    out[offset + 5] = peer.port & 0xff;
    offset += 6;
  }
  return offset === out.length ? out : out.subarray(0, offset);
}

function compactV6(peers) {
  const list = peers.filter((p) => p.family === 6);
  const out = new Uint8Array(list.length * 18);
  let offset = 0;
  for (const peer of list) {
    const bytes = ipv6ToBytes(peer.ip);
    if (!bytes) continue;
    out.set(bytes, offset);
    out[offset + 16] = (peer.port >> 8) & 0xff;
    out[offset + 17] = peer.port & 0xff;
    offset += 18;
  }
  return offset === out.length ? out : out.subarray(0, offset);
}

function listPeers(peers, family) {
  return peers
    .filter((p) => p.family === family)
    .map((p) => (p.peerId ? { "peer id": hexToFixed(p.peerId), ip: p.ip, port: p.port } : { ip: p.ip, port: p.port }));
}

function hexToFixed(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function passkeyFromPath(pathname) {
  const match = /^\/announce\/([0-9a-fA-F]{64})\/?$/.exec(pathname);
  return match ? match[1] : null;
}

/* ---------------------------------------------------------------- 原始查询串 */

/**
 * 自己解查询串：info_hash / peer_id 是二进制，URLSearchParams 会按 UTF-8 解坏。
 * '+' 当空格（表单编码，tracker 客户端都这么发），%XX 一律按字节。
 */
export function parseRawQuery(search) {
  const out = [];
  const text = String(search || "").replace(/^\?/, "");
  if (!text) return out;
  for (const chunk of text.split("&")) {
    if (!chunk) continue;
    const cut = chunk.indexOf("=");
    const rawKey = cut < 0 ? chunk : chunk.slice(0, cut);
    const rawValue = cut < 0 ? "" : chunk.slice(cut + 1);
    const key = percentDecode(rawKey);
    const value = percentDecode(rawValue);
    if (!key || !value) continue;
    out.push({ key: new TextDecoder().decode(key), value });
  }
  return out;
}

function percentDecode(text) {
  const bytes = [];
  for (let i = 0; i < text.length; ) {
    const code = text.charCodeAt(i);
    if (code === 0x25) {
      const hex = text.slice(i + 1, i + 3);
      if (!/^[0-9a-fA-F]{2}$/.test(hex)) return null;
      bytes.push(parseInt(hex, 16));
      i += 3;
    } else if (code === 0x2b) {
      bytes.push(0x20);
      i += 1;
    } else if (code < 0x80) {
      bytes.push(code);
      i += 1;
    } else {
      const char = String.fromCodePoint(text.codePointAt(i));
      bytes.push(...new TextEncoder().encode(char));
      i += char.length;
    }
  }
  return new Uint8Array(bytes);
}

function firstBytes(pairs, key) {
  for (const pair of pairs) if (pair.key === key) return pair.value;
  return null;
}

function firstText(pairs, key) {
  const bytes = firstBytes(pairs, key);
  return bytes ? new TextDecoder().decode(bytes) : null;
}

function firstInt(pairs, key) {
  const text = firstText(pairs, key);
  if (text === null || text === "") return null;
  if (!/^-?\d{1,9}$/.test(text)) return null;
  return Number(text);
}
