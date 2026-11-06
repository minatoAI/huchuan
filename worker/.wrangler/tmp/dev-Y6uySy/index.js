var __defProp = Object.defineProperty;
var __name = (target, value) => __defProp(target, "name", { value, configurable: true });

// src/bencode.js
var encoder = new TextEncoder();
function utf8(str) {
  return encoder.encode(str);
}
__name(utf8, "utf8");
function concat(chunks) {
  let total = 0;
  for (const c of chunks)
    total += c.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}
__name(concat, "concat");
function encodeInto(value, out) {
  if (typeof value === "number") {
    if (!Number.isInteger(value))
      throw new TypeError("bencode: only integers are supported");
    out.push(utf8("i" + value + "e"));
    return;
  }
  if (typeof value === "string") {
    const bytes = utf8(value);
    out.push(utf8(bytes.length + ":"), bytes);
    return;
  }
  if (value instanceof Uint8Array) {
    out.push(utf8(value.length + ":"), value);
    return;
  }
  if (Array.isArray(value)) {
    out.push(utf8("l"));
    for (const item of value)
      encodeInto(item, out);
    out.push(utf8("e"));
    return;
  }
  if (value && typeof value === "object") {
    const keys = Object.keys(value).filter((k) => value[k] !== void 0).sort();
    out.push(utf8("d"));
    for (const key of keys) {
      encodeInto(key, out);
      encodeInto(value[key], out);
    }
    out.push(utf8("e"));
    return;
  }
  throw new TypeError("bencode: unsupported value " + String(value));
}
__name(encodeInto, "encodeInto");
function bencode(value) {
  const out = [];
  encodeInto(value, out);
  return concat(out);
}
__name(bencode, "bencode");

// src/crypto.js
var encoder2 = new TextEncoder();
function utf82(str) {
  return encoder2.encode(str);
}
__name(utf82, "utf8");
function bytesToHex(bytes) {
  let out = "";
  for (const b of bytes)
    out += b.toString(16).padStart(2, "0");
  return out;
}
__name(bytesToHex, "bytesToHex");
function hexToBytes(hex) {
  const text = String(hex);
  if (text.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(text))
    return null;
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i += 1)
    out[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
  return out;
}
__name(hexToBytes, "hexToBytes");
function concatBytes(...parts) {
  let total = 0;
  for (const p of parts)
    total += p.length;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}
__name(concatBytes, "concatBytes");
async function sha256(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return new Uint8Array(digest);
}
__name(sha256, "sha256");
var B32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
function b32Encode(bytes) {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of bytes) {
    value = value << 8 | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32_ALPHABET[value >>> bits - 5 & 31];
      bits -= 5;
    }
  }
  if (bits > 0)
    out += B32_ALPHABET[value << 5 - bits & 31];
  return out;
}
__name(b32Encode, "b32Encode");
function b32Decode(text) {
  const source = String(text).toUpperCase();
  let bits = 0;
  let value = 0;
  const out = [];
  for (const ch of source) {
    const index = B32_ALPHABET.indexOf(ch);
    if (index < 0)
      return null;
    value = value << 5 | index;
    bits += 5;
    if (bits >= 8) {
      out.push(value >>> bits - 8 & 255);
      bits -= 8;
    }
  }
  if (bits > 0 && (value & (1 << bits) - 1) !== 0)
    return null;
  return new Uint8Array(out);
}
__name(b32Decode, "b32Decode");
function normalizeB32(text) {
  const cleaned = String(text).toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  if (!/^[0-9A-HJKMNP-TV-Z]*$/.test(cleaned))
    return null;
  return cleaned;
}
__name(normalizeB32, "normalizeB32");
function deviceIdFromPub(pubBytes) {
  return sha256(concatBytes(utf82("huchuan/dev1/v1"), pubBytes)).then((digest) => b32Encode(digest.subarray(0, 10)));
}
__name(deviceIdFromPub, "deviceIdFromPub");
function fieldBytes(field) {
  if (typeof field === "string")
    return utf82(field);
  if (field instanceof Uint8Array)
    return field;
  if (typeof field === "number") {
    if (!Number.isSafeInteger(field) || field < 0)
      throw new TypeError("signBytes: number fields must be non-negative safe integers");
    const out = new Uint8Array(8);
    let value = BigInt(field);
    for (let i = 7; i >= 0; i -= 1) {
      out[i] = Number(value & 0xffn);
      value >>= 8n;
    }
    return out;
  }
  throw new TypeError("signBytes: unsupported field type");
}
__name(fieldBytes, "fieldBytes");
function signBytes(domain, fields) {
  const parts = [utf82(domain), new Uint8Array([0])];
  for (const field of fields) {
    const bytes = fieldBytes(field);
    if (bytes.length > 65535)
      throw new RangeError("signBytes: field too long");
    parts.push(new Uint8Array([bytes.length >> 8, bytes.length & 255]), bytes);
  }
  return concatBytes(...parts);
}
__name(signBytes, "signBytes");
var SPKI_P256_PREFIX = new Uint8Array([
  48,
  89,
  48,
  19,
  6,
  7,
  42,
  134,
  72,
  206,
  61,
  2,
  1,
  6,
  8,
  42,
  134,
  72,
  206,
  61,
  3,
  1,
  7,
  3,
  66,
  0,
  4
]);
var P256_ORDER = BigInt("0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");
function signatureComponentsValid(sigBytes) {
  if (!(sigBytes instanceof Uint8Array) || sigBytes.length !== 64)
    return false;
  const toBig = /* @__PURE__ */ __name((slice) => BigInt("0x" + bytesToHex(slice)), "toBig");
  const r = toBig(sigBytes.subarray(0, 32));
  const s = toBig(sigBytes.subarray(32));
  return r > 0n && s > 0n && r < P256_ORDER && s < P256_ORDER;
}
__name(signatureComponentsValid, "signatureComponentsValid");
async function verifyP256(pubBytes, sigBytes, messageBytes) {
  if (!(pubBytes instanceof Uint8Array) || pubBytes.length !== 64)
    return false;
  if (!signatureComponentsValid(sigBytes))
    return false;
  try {
    const spki = concatBytes(SPKI_P256_PREFIX, pubBytes);
    const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, sigBytes, messageBytes);
  } catch {
    return false;
  }
}
__name(verifyP256, "verifyP256");
async function tokenHashHex(token) {
  const digest = await sha256(concatBytes(utf82("huchuan/tok1/v1"), utf82(String(token))));
  return bytesToHex(digest);
}
__name(tokenHashHex, "tokenHashHex");
async function passkeyHashHex(passkeyHex) {
  const digest = await sha256(concatBytes(utf82("huchuan/trk1/v1"), utf82(String(passkeyHex).toLowerCase())));
  return bytesToHex(digest);
}
__name(passkeyHashHex, "passkeyHashHex");
function constantTimeEqualHex(a, b) {
  const left = String(a || "").toLowerCase();
  const right = String(b || "").toLowerCase();
  if (left.length !== right.length || left.length === 0)
    return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1)
    diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return diff === 0;
}
__name(constantTimeEqualHex, "constantTimeEqualHex");
function randomBytes(length) {
  const out = new Uint8Array(length);
  crypto.getRandomValues(out);
  return out;
}
__name(randomBytes, "randomBytes");
function randomHex(byteLength) {
  return bytesToHex(randomBytes(byteLength));
}
__name(randomHex, "randomHex");
function randomToken() {
  return b32Encode(randomBytes(16));
}
__name(randomToken, "randomToken");
function randomShortId(chars = 8) {
  return b32Encode(randomBytes(5)).slice(0, chars);
}
__name(randomShortId, "randomShortId");

// src/limits.js
var ANNOUNCE_INTERVAL_SEC = 600;
var ANNOUNCE_MIN_INTERVAL_SEC = 300;
var ANNOUNCE_MAX_INTERVAL_SEC = 3600;
var ANNOUNCE_NUMWANT_DEFAULT = 32;
var ANNOUNCE_NUMWANT_MAX = 32;
var PEER_TTL_SEC = 1800;
var MAX_PEERS_PER_SWARM = 512;
var SHARE_TTL_DEFAULT_SEC = 7 * 24 * 3600;
var SHARE_TTL_MAX_SEC = 7 * 24 * 3600;
var SHARE_TTL_MIN_SEC = 60;
var MAX_ACTIVE_SHARES_PER_DEVICE = 20;
var MAX_SHARE_FILES = 10;
var MAX_FILE_NAME_BYTES = 255;
var MAX_FILE_BYTES = 1 << 30;
var MAX_SIGNAL_FRAME_BYTES = 64 * 1024;
var SIGNAL_CLOCK_SKEW_SEC = 300;
var SIGNAL_JOIN_WINDOW_MS = 6e4;
var SIGNAL_MAX_JOINS_PER_WINDOW = 3;
var SIGNAL_ROOM_MAX_GUESTS = 1;
var MAX_JSON_BODY_BYTES = 64 * 1024;

// src/util.js
var CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
  "access-control-allow-headers": "content-type",
  "access-control-max-age": "86400"
};
function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...headers
    }
  });
}
__name(json, "json");
function corsJson(body, status = 200) {
  return json(body, status, CORS_HEADERS);
}
__name(corsJson, "corsJson");
function preflight() {
  return new Response(null, { status: 204, headers: CORS_HEADERS });
}
__name(preflight, "preflight");
async function readJson(request, maxBytes) {
  const length = Number(request.headers.get("content-length") || "0");
  if (length && length > maxBytes)
    throw new HttpError(413, "body too large");
  const raw = await request.text();
  if (raw.length > maxBytes)
    throw new HttpError(413, "body too large");
  if (!raw)
    return {};
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed))
      throw new Error("not an object");
    return parsed;
  } catch {
    throw new HttpError(400, "invalid json");
  }
}
__name(readJson, "readJson");
var HttpError = class extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
};
__name(HttpError, "HttpError");
function ipv4ToBytes(text) {
  const parts = String(text).split(".");
  if (parts.length !== 4)
    return null;
  const out = new Uint8Array(4);
  for (let i = 0; i < 4; i += 1) {
    if (!/^\d{1,3}$/.test(parts[i]))
      return null;
    const value = Number(parts[i]);
    if (value > 255)
      return null;
    out[i] = value;
  }
  return out;
}
__name(ipv4ToBytes, "ipv4ToBytes");
function ipv6ToBytes(raw) {
  let text = String(raw).trim();
  if (text.startsWith("[") && text.endsWith("]"))
    text = text.slice(1, -1);
  if (text.includes(".")) {
    const cut = text.lastIndexOf(":");
    if (cut < 0)
      return null;
    const v4 = ipv4ToBytes(text.slice(cut + 1));
    if (!v4)
      return null;
    const high = (v4[0] << 8 | v4[1]).toString(16);
    const low = (v4[2] << 8 | v4[3]).toString(16);
    text = text.slice(0, cut + 1) + high + ":" + low;
  }
  const halves = text.split("::");
  if (halves.length > 2)
    return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 ? halves[1] ? halves[1].split(":") : [] : null;
  if (tail === null && head.length !== 8)
    return null;
  if (tail !== null && head.length + tail.length > 7)
    return null;
  const groups = tail === null ? head : [...head, ...new Array(8 - head.length - tail.length).fill("0"), ...tail];
  if (groups.length !== 8)
    return null;
  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i += 1) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(groups[i]))
      return null;
    const value = parseInt(groups[i], 16);
    out[i * 2] = value >> 8;
    out[i * 2 + 1] = value & 255;
  }
  return out;
}
__name(ipv6ToBytes, "ipv6ToBytes");
function normalizeAddress(raw) {
  let text = String(raw || "").trim();
  if (!text)
    return null;
  if (text.startsWith("[") && text.endsWith("]"))
    text = text.slice(1, -1);
  const zone = text.indexOf("%");
  if (zone > 0)
    text = text.slice(0, zone);
  const v4 = ipv4ToBytes(text);
  if (v4)
    return { ip: text, family: 4, bytes: v4 };
  if (/^::ffff:/i.test(text)) {
    const mapped = ipv4ToBytes(text.slice(7));
    if (mapped)
      return { ip: text.slice(7), family: 4, bytes: mapped };
  }
  const v6 = ipv6ToBytes(text);
  if (v6)
    return { ip: text, family: 6, bytes: v6 };
  return null;
}
__name(normalizeAddress, "normalizeAddress");
function clientAddress(request) {
  const headers = request.headers;
  const forwarded = (headers.get("x-forwarded-for") || "").split(",")[0].trim();
  const raw = headers.get("cf-connecting-ip") || headers.get("x-real-ip") || forwarded || "";
  return normalizeAddress(raw);
}
__name(clientAddress, "clientAddress");
function nowSec() {
  return Math.floor(Date.now() / 1e3);
}
__name(nowSec, "nowSec");

// src/announce.js
var ALLOWED_EVENTS = /* @__PURE__ */ new Set(["none", "started", "stopped", "completed", "paused"]);
async function handleAnnounce(request, env) {
  const url = new URL(request.url);
  const pairs = parseRawQuery(url.search);
  const infoHash = firstBytes(pairs, "info_hash");
  if (!infoHash || infoHash.length !== 20)
    return failure("invalid info_hash");
  const peerId = firstBytes(pairs, "peer_id");
  if (peerId && peerId.length !== 20)
    return failure("invalid peer_id");
  const port = firstInt(pairs, "port");
  if (!port || port < 1 || port > 65535)
    return failure("invalid port");
  const pathPasskey = passkeyFromPath(url.pathname);
  const queryPasskey = firstText(pairs, "passkey");
  const passkey = (pathPasskey || queryPasskey || "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(passkey))
    return failure("missing or malformed passkey");
  const event = (firstText(pairs, "event") || "none").toLowerCase();
  if (!ALLOWED_EVENTS.has(event))
    return failure("invalid event");
  const numericWant = firstInt(pairs, "numwant");
  const numwant = clampNumwant(numericWant);
  const compact = firstText(pairs, "compact") !== "0";
  const client = clientAddress(request);
  if (!client)
    return failure("unable to determine client address");
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
      now: Date.now()
    })
  });
  const data = await reply.json().catch(() => null);
  if (!data || data.ok !== true)
    return failure(data && data.reason || "internal error");
  console.log(JSON.stringify({ ev: "announce", ok: true, event, peers: (data.peers || []).length }));
  const body = {
    interval,
    "min interval": ANNOUNCE_MIN_INTERVAL_SEC,
    complete: data.complete,
    incomplete: data.incomplete,
    peers: compact ? compactV4(data.peers || []) : listPeers(data.peers || [], 4),
    peers6: compact ? compactV6(data.peers || []) : listPeers(data.peers || [], 6)
  };
  if (client.ip)
    body["external ip"] = client.ip;
  return new Response(bencode(body), {
    status: 200,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" }
  });
}
__name(handleAnnounce, "handleAnnounce");
function announceInterval(env) {
  const configured = Number(env && env.ANNOUNCE_INTERVAL_SEC);
  if (!Number.isFinite(configured) || configured <= 0)
    return ANNOUNCE_INTERVAL_SEC;
  return Math.min(Math.max(Math.floor(configured), ANNOUNCE_INTERVAL_SEC), ANNOUNCE_MAX_INTERVAL_SEC);
}
__name(announceInterval, "announceInterval");
function clampNumwant(value) {
  if (value === null || value === void 0 || value < 0)
    return ANNOUNCE_NUMWANT_DEFAULT;
  return Math.min(value, ANNOUNCE_NUMWANT_MAX);
}
__name(clampNumwant, "clampNumwant");
function failure(reason) {
  console.log(JSON.stringify({ ev: "announce", ok: false, reason }));
  return new Response(bencode({ "failure reason": reason }), {
    status: 200,
    headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" }
  });
}
__name(failure, "failure");
function compactV4(peers) {
  const list = peers.filter((p) => p.family === 4);
  const out = new Uint8Array(list.length * 6);
  let offset = 0;
  for (const peer of list) {
    const bytes = ipv4ToBytes(peer.ip);
    if (!bytes)
      continue;
    out.set(bytes, offset);
    out[offset + 4] = peer.port >> 8 & 255;
    out[offset + 5] = peer.port & 255;
    offset += 6;
  }
  return offset === out.length ? out : out.subarray(0, offset);
}
__name(compactV4, "compactV4");
function compactV6(peers) {
  const list = peers.filter((p) => p.family === 6);
  const out = new Uint8Array(list.length * 18);
  let offset = 0;
  for (const peer of list) {
    const bytes = ipv6ToBytes(peer.ip);
    if (!bytes)
      continue;
    out.set(bytes, offset);
    out[offset + 16] = peer.port >> 8 & 255;
    out[offset + 17] = peer.port & 255;
    offset += 18;
  }
  return offset === out.length ? out : out.subarray(0, offset);
}
__name(compactV6, "compactV6");
function listPeers(peers, family) {
  return peers.filter((p) => p.family === family).map((p) => p.peerId ? { "peer id": hexToFixed(p.peerId), ip: p.ip, port: p.port } : { ip: p.ip, port: p.port });
}
__name(listPeers, "listPeers");
function hexToFixed(hex) {
  const out = new Uint8Array(hex.length / 2);
  for (let i = 0; i < out.length; i += 1)
    out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}
__name(hexToFixed, "hexToFixed");
function passkeyFromPath(pathname) {
  const match = /^\/announce\/([0-9a-fA-F]{64})\/?$/.exec(pathname);
  return match ? match[1] : null;
}
__name(passkeyFromPath, "passkeyFromPath");
function parseRawQuery(search) {
  const out = [];
  const text = String(search || "").replace(/^\?/, "");
  if (!text)
    return out;
  for (const chunk of text.split("&")) {
    if (!chunk)
      continue;
    const cut = chunk.indexOf("=");
    const rawKey = cut < 0 ? chunk : chunk.slice(0, cut);
    const rawValue = cut < 0 ? "" : chunk.slice(cut + 1);
    const key = percentDecode(rawKey);
    const value = percentDecode(rawValue);
    if (!key || !value)
      continue;
    out.push({ key: new TextDecoder().decode(key), value });
  }
  return out;
}
__name(parseRawQuery, "parseRawQuery");
function percentDecode(text) {
  const bytes = [];
  for (let i = 0; i < text.length; ) {
    const code = text.charCodeAt(i);
    if (code === 37) {
      const hex = text.slice(i + 1, i + 3);
      if (!/^[0-9a-fA-F]{2}$/.test(hex))
        return null;
      bytes.push(parseInt(hex, 16));
      i += 3;
    } else if (code === 43) {
      bytes.push(32);
      i += 1;
    } else if (code < 128) {
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
__name(percentDecode, "percentDecode");
function firstBytes(pairs, key) {
  for (const pair of pairs)
    if (pair.key === key)
      return pair.value;
  return null;
}
__name(firstBytes, "firstBytes");
function firstText(pairs, key) {
  const bytes = firstBytes(pairs, key);
  return bytes ? new TextDecoder().decode(bytes) : null;
}
__name(firstText, "firstText");
function firstInt(pairs, key) {
  const text = firstText(pairs, key);
  if (text === null || text === "")
    return null;
  if (!/^-?\d{1,9}$/.test(text))
    return null;
  return Number(text);
}
__name(firstInt, "firstInt");

// src/share.js
function registryStub(env) {
  return env.REGISTRY.get(env.REGISTRY.idFromName("registry"));
}
__name(registryStub, "registryStub");
function roomStub(env, shareId) {
  return env.ROOM.get(env.ROOM.idFromName(shareId));
}
__name(roomStub, "roomStub");
async function registryCall(env, path, init) {
  const reply = await registryStub(env).fetch("https://registry.internal" + path, init);
  return reply.json();
}
__name(registryCall, "registryCall");
async function handleCreateShare(request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);
  const pubHex = String(body.pub || "");
  if (!/^[0-9a-fA-F]{128}$/.test(pubHex))
    throw new HttpError(400, "pub must be 128 hex chars");
  const pub = hexToBytes(pubHex);
  const deviceId = normalizeB32(body.deviceId || "");
  if (!deviceId || deviceId.length !== 16)
    throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");
  const derived = await deviceIdFromPub(pub);
  if (derived !== deviceId)
    throw new HttpError(400, "deviceId does not match pub");
  const files = normalizeFiles(body.files);
  let token = null;
  if (body.token !== void 0 && body.token !== null && body.token !== "") {
    token = normalizeToken(body.token);
    if (!token)
      throw new HttpError(400, "token must be 26 Crockford B32 chars (128 bit)");
  } else {
    token = randomToken();
  }
  const requestedTtl = Number(body.ttlSec);
  const ttlSec = Number.isFinite(requestedTtl) && requestedTtl > 0 ? Math.min(Math.max(Math.floor(requestedTtl), SHARE_TTL_MIN_SEC), SHARE_TTL_MAX_SEC) : SHARE_TTL_DEFAULT_SEC;
  const createdAt = Date.now();
  const expiresAt = createdAt + ttlSec * 1e3;
  const shareId = randomHex(16);
  const tokenHash = await tokenHashHex(token);
  const result = await registryCall(env, "/create", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ shareId, tokenHash, deviceId, pub: pubHex.toLowerCase(), files, createdAt, expiresAt })
  });
  if (!result.ok) {
    if (result.error === "too-many") {
      return corsJson({ ok: false, error: "too-many", limit: 20 }, 429);
    }
    return corsJson({ ok: false, error: result.error || "create-failed" }, 409);
  }
  console.log(JSON.stringify({ ev: "share.create", shareId, files: files.length }));
  return corsJson({ ok: true, shareId, token, createdAt, expiresAt }, 201);
}
__name(handleCreateShare, "handleCreateShare");
async function handleGetShare(tokenParam, env) {
  const token = normalizeToken(tokenParam);
  if (!token)
    return corsJson({ status: "not-found" }, 404);
  const tokenHash = await tokenHashHex(token);
  const result = await registryCall(env, "/get?tokenHash=" + tokenHash);
  const record = result.record;
  if (!record)
    return corsJson({ status: "not-found" }, 404);
  const status = await shareStatus(env, record);
  return corsJson({
    status,
    shareId: record.shareId,
    deviceId: record.deviceId,
    pub: record.pub,
    files: record.files,
    createdAt: record.createdAt,
    expiresAt: record.expiresAt,
    visitCount: record.visitCount || 0
  });
}
__name(handleGetShare, "handleGetShare");
async function shareStatus(env, record) {
  if (record.state === "revoked")
    return "revoked";
  if (record.expiresAt <= Date.now())
    return "expired";
  const room = await roomState(env, record.shareId);
  if (room.revoked)
    return "revoked";
  return room.sharer ? "available" : "offline";
}
__name(shareStatus, "shareStatus");
async function roomState(env, shareId) {
  try {
    const reply = await roomStub(env, shareId).fetch("https://room.internal/status", { method: "GET" });
    const data = await reply.json();
    return { sharer: !!data.sharer, guest: !!data.guest, revoked: !!data.revoked };
  } catch {
    return { sharer: false, guest: false, revoked: false };
  }
}
__name(roomState, "roomState");
async function handleDeleteShare(idParam, request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);
  const deviceId = normalizeB32(body.deviceId || "");
  if (!deviceId || deviceId.length !== 16)
    throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");
  const record = await resolveShare(env, idParam);
  if (!record)
    return corsJson({ ok: false, error: "not-found" }, 404);
  const token = normalizeToken(body.token);
  if (!token)
    throw new HttpError(400, "token required to sign the revoke request");
  if (await tokenHashHex(token) !== record.tokenHash)
    throw new HttpError(400, "token does not match this share");
  if (record.deviceId !== deviceId)
    return corsJson({ ok: false, error: "not-owner" }, 403);
  const ts = Number(body.ts);
  if (!Number.isFinite(ts) || Math.abs(nowSec() - ts) > SIGNAL_CLOCK_SKEW_SEC) {
    throw new HttpError(400, "ts outside allowed skew");
  }
  const nonce = String(body.nonce || "");
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(nonce))
    throw new HttpError(400, "invalid nonce");
  const sig = String(body.sig || "");
  if (!/^[0-9a-fA-F]{128}$/.test(sig))
    throw new HttpError(400, "invalid sig");
  const message = signBytes("huchuan/revoke1/v1", [deviceId, token, ts, nonce]);
  const pub = hexToBytes(record.pub);
  const ok = pub ? await verifyP256(pub, hexToBytes(sig), message) : false;
  if (!ok)
    return corsJson({ ok: false, error: "bad-signature" }, 403);
  const result = await registryCall(env, "/revoke", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ shareId: record.shareId, now: Date.now() })
  });
  await kickRoom(env, record.shareId);
  console.log(JSON.stringify({ ev: "share.revoke", shareId: record.shareId, alreadyRevoked: !!result.alreadyRevoked }));
  return corsJson({
    ok: true,
    shareId: record.shareId,
    revokedAt: result.revokedAt || Date.now(),
    alreadyRevoked: !!result.alreadyRevoked
  });
}
__name(handleDeleteShare, "handleDeleteShare");
async function kickRoom(env, shareId) {
  try {
    await roomStub(env, shareId).fetch("https://room.internal/revoke", { method: "POST" });
  } catch {
  }
}
__name(kickRoom, "kickRoom");
async function resolveShare(env, idParam) {
  const raw = String(idParam || "");
  if (/^[0-9a-fA-F]{32}$/.test(raw)) {
    const byId = await registryCall(env, "/get?shareId=" + raw.toLowerCase());
    if (byId.record)
      return byId.record;
  }
  const token = normalizeToken(raw);
  if (!token)
    return null;
  const byToken = await registryCall(env, "/get?tokenHash=" + await tokenHashHex(token));
  return byToken.record || null;
}
__name(resolveShare, "resolveShare");
async function handleListShares(request, env) {
  const url = new URL(request.url);
  const deviceId = normalizeB32(url.searchParams.get("deviceId") || "");
  if (!deviceId || deviceId.length !== 16)
    throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");
  const result = await registryCall(env, "/list?deviceId=" + deviceId + "&now=" + Date.now());
  const shares = (result.shares || []).map((record) => ({
    shareId: record.shareId,
    files: record.files,
    createdAt: record.createdAt,
    expiresAt: record.expiresAt,
    visitCount: record.visitCount || 0
  }));
  return corsJson({ shares });
}
__name(handleListShares, "handleListShares");
async function handleTrackerRegister(request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);
  const infoHash = String(body.infoHash || "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(infoHash))
    throw new HttpError(400, "infoHash must be 40 hex chars (groupHash20)");
  const gid = normalizeB32(body.gid || "");
  if (!gid || gid.length !== 16)
    throw new HttpError(400, "gid must be 16 Crockford B32 chars");
  const passkey = String(body.passkey || "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(passkey))
    throw new HttpError(400, "passkey must be 64 hex chars");
  const prevPasskey = body.prevPasskey ? String(body.prevPasskey).toLowerCase() : null;
  if (prevPasskey !== null && !/^[0-9a-f]{64}$/.test(prevPasskey))
    throw new HttpError(400, "prevPasskey must be 64 hex chars");
  const passKeyHash = await passkeyHashHex(passkey);
  const prevPassKeyHash = prevPasskey ? await passkeyHashHex(prevPasskey) : null;
  const swarm = env.SWARM.get(env.SWARM.idFromName(infoHash));
  const reply = await swarm.fetch("https://swarm.internal/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ infoHash, gid, passKeyHash, prevPassKeyHash })
  });
  const data = await reply.json();
  if (!data.ok && data.error === "conflict")
    return json(data, 409);
  return json(data, data.ok ? 200 : 400);
}
__name(handleTrackerRegister, "handleTrackerRegister");
function normalizeToken(value) {
  const text = normalizeB32(value || "");
  if (!text || text.length !== 26)
    return null;
  const bytes = b32Decode(text);
  if (!bytes || bytes.length !== 16)
    return null;
  return text;
}
__name(normalizeToken, "normalizeToken");
function normalizeFiles(files) {
  if (!Array.isArray(files) || files.length === 0)
    throw new HttpError(400, "files must be a non-empty array");
  if (files.length > MAX_SHARE_FILES)
    throw new HttpError(400, "at most " + MAX_SHARE_FILES + " files");
  const encoder3 = new TextEncoder();
  return files.map((file) => {
    if (!file || typeof file !== "object")
      throw new HttpError(400, "invalid file entry");
    const name = String(file.name || "");
    const nameBytes = encoder3.encode(name).length;
    if (!name || nameBytes > MAX_FILE_NAME_BYTES)
      throw new HttpError(400, "invalid file name");
    const size = Number(file.size);
    if (!Number.isFinite(size) || size < 0 || size > MAX_FILE_BYTES)
      throw new HttpError(400, "invalid file size");
    const mime = file.mime === void 0 || file.mime === null ? "" : String(file.mime).slice(0, 127);
    return { name, size, mime };
  });
}
__name(normalizeFiles, "normalizeFiles");

// src/signal.js
async function handleSignal(request, env, ctx) {
  const url = new URL(request.url);
  const upgrade = (request.headers.get("upgrade") || "").toLowerCase();
  if (upgrade !== "websocket")
    return json({ ok: false, error: "websocket upgrade required" }, 426);
  const shareId = (url.searchParams.get("share") || "").trim();
  if (!/^[0-9a-fA-F]{32}$/.test(shareId))
    return json({ ok: false, error: "invalid share" }, 400);
  const roleParam = (url.searchParams.get("role") || "").toLowerCase();
  const role = roleParam || (url.searchParams.has("ep") ? "guest" : "sharer");
  if (role !== "sharer" && role !== "guest")
    return json({ ok: false, error: "invalid role" }, 400);
  const record = await fetchRecord(env, shareId);
  if (!record)
    return json({ ok: false, error: "gone" }, 410);
  if (record.state === "revoked")
    return json({ ok: false, error: "revoked" }, 410);
  if (record.expiresAt <= Date.now())
    return json({ ok: false, error: "expired" }, 410);
  const headers = new Headers(request.headers);
  headers.set("x-huchuan-role", role);
  headers.set("x-huchuan-share", record.shareId);
  headers.set("x-huchuan-token-hash", record.tokenHash);
  if (role === "sharer") {
    const dev = url.searchParams.get("dev") || "";
    const ts = Number(url.searchParams.get("ts"));
    const nonce = url.searchParams.get("nonce") || "";
    const sig = url.searchParams.get("sig") || "";
    if (!dev)
      return json({ ok: false, error: "missing dev" }, 400);
    if (!Number.isFinite(ts) || Math.abs(nowSec() - ts) > SIGNAL_CLOCK_SKEW_SEC) {
      return json({ ok: false, error: "ts outside allowed skew" }, 400);
    }
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(nonce))
      return json({ ok: false, error: "invalid nonce" }, 400);
    if (!/^[0-9a-fA-F]{128}$/.test(sig))
      return json({ ok: false, error: "invalid sig" }, 400);
    if (record.deviceId !== dev.toUpperCase())
      return json({ ok: false, error: "not-owner" }, 403);
    const pub = hexToBytes(record.pub);
    const message = signBytes("huchuan/ws1/v1", [dev.toUpperCase(), nonce, ts]);
    const ok = pub ? await verifyP256(pub, hexToBytes(sig), message) : false;
    if (!ok)
      return json({ ok: false, error: "bad-signature" }, 403);
    headers.set("x-huchuan-dev", dev.toUpperCase());
    return roomStub(env, record.shareId).fetch(new Request("https://room.internal/join", { method: "GET", headers }));
  }
  const ep = (url.searchParams.get("ep") || "").trim();
  if (!/^[A-Za-z0-9_-]{8,32}$/.test(ep))
    return json({ ok: false, error: "invalid ep" }, 400);
  headers.set("x-huchuan-ep", ep);
  const response = await roomStub(env, record.shareId).fetch(new Request("https://room.internal/join", { method: "GET", headers }));
  if (response.status === 101 && ctx && typeof ctx.waitUntil === "function") {
    ctx.waitUntil(touchShare(env, record.shareId));
  }
  return response;
}
__name(handleSignal, "handleSignal");
async function touchShare(env, shareId) {
  try {
    await env.REGISTRY.get(env.REGISTRY.idFromName("registry")).fetch("https://registry.internal/touch", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ shareId })
    });
  } catch {
  }
}
__name(touchShare, "touchShare");
async function fetchRecord(env, shareId) {
  const reply = await env.REGISTRY.get(env.REGISTRY.idFromName("registry")).fetch(
    "https://registry.internal/get?shareId=" + encodeURIComponent(shareId)
  );
  const data = await reply.json();
  return data.record || null;
}
__name(fetchRecord, "fetchRecord");

// src/swarm.js
var TrackerSwarm = class {
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
        createdAt: now
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
      reason: "group already registered; pass prevPasskey to rotate"
    });
  }
  async announce(payload) {
    const group = await this.storage.get("group");
    if (!group)
      return json({ ok: false, reason: "info hash not registered" });
    if (!constantTimeEqualHex(group.passKeyHash, payload.passKeyHash)) {
      return json({ ok: false, reason: "bad passkey" });
    }
    const now = payload.now || Date.now();
    const ttlMs = PEER_TTL_SEC * 1e3;
    let peers = await this.storage.get("peers") || [];
    peers = peers.filter((peer) => now - peer.last <= ttlMs);
    const selfKey = peerKey({ peerId: payload.peerId, ip: payload.peer.ip, port: payload.peer.port });
    const before = peers.filter((peer) => peerKey(peer) !== selfKey);
    if (payload.event !== "stopped") {
      before.push({
        peerId: payload.peerId || null,
        ip: payload.peer.ip,
        port: payload.peer.port,
        family: payload.peer.family,
        last: now
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
      peers: picked.map((peer) => ({ ip: peer.ip, port: peer.port, family: peer.family, peerId: peer.peerId }))
    });
  }
};
__name(TrackerSwarm, "TrackerSwarm");
function peerKey(peer) {
  if (peer.peerId)
    return "p:" + peer.peerId;
  return "a:" + peer.ip + ":" + peer.port;
}
__name(peerKey, "peerKey");

// src/registry.js
var MAX_DEVICE_INDEX = 200;
var ShareRegistry = class {
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
    const ids = await this.storage.get(indexKey) || [];
    let active = 0;
    for (const id of ids) {
      const existing = await this.storage.get("s:" + id);
      if (existing && existing.state === "active" && existing.expiresAt > now)
        active += 1;
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
      visitCount: 0
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
      if (id)
        record = await this.storage.get("s:" + id);
    } else {
      return json({ ok: false, error: "missing key" }, 400);
    }
    return json({ ok: true, record: record || null });
  }
  async revoke(payload) {
    const record = await this.storage.get("s:" + payload.shareId);
    if (!record)
      return json({ ok: false, error: "not-found" }, 404);
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
    if (!record)
      return json({ ok: false, error: "not-found" }, 404);
    const next = { ...record, visitCount: (record.visitCount || 0) + 1 };
    await this.storage.put("s:" + next.shareId, next);
    return json({ ok: true, visitCount: next.visitCount });
  }
  async list(url) {
    const deviceId = url.searchParams.get("deviceId") || "";
    const now = Number(url.searchParams.get("now")) || Date.now();
    const ids = await this.storage.get("d:" + deviceId) || [];
    const shares = [];
    for (const id of ids) {
      const record = await this.storage.get("s:" + id);
      if (!record)
        continue;
      if (record.state !== "active" || record.expiresAt <= now)
        continue;
      shares.push(record);
    }
    shares.sort((a, b) => b.createdAt - a.createdAt);
    return json({ ok: true, shares });
  }
};
__name(ShareRegistry, "ShareRegistry");

// src/ws.js
function upgradeResponse(webSocket) {
  try {
    return new Response(null, { status: 101, webSocket });
  } catch {
    return { status: 101, webSocket, headers: new Headers(), webSocketUpgradeFallback: true };
  }
}
__name(upgradeResponse, "upgradeResponse");

// src/room.js
var SignalRoom = class {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.storage = state.storage;
  }
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/join")
      return this.join(request);
    if (url.pathname === "/status")
      return this.status();
    if (url.pathname === "/revoke")
      return this.revoke();
    return json({ ok: false, error: "unknown op" }, 404);
  }
  /* -------------------------------------------------------------- 握手 */
  async join(request) {
    const upgrade = (request.headers.get("upgrade") || "").toLowerCase();
    if (upgrade !== "websocket")
      return json({ ok: false, error: "websocket upgrade required" }, 426);
    const role = (request.headers.get("x-huchuan-role") || "").toLowerCase();
    if (role !== "sharer" && role !== "guest")
      return json({ ok: false, error: "invalid role" }, 400);
    const meta = await this.loadMeta(request);
    if (meta.revoked)
      return json({ ok: false, error: "revoked" }, 410);
    const pair = new WebSocketPair();
    const client = pair[0];
    const server = pair[1];
    if (role === "guest" && this.state.getWebSockets("guest").length >= SIGNAL_ROOM_MAX_GUESTS) {
      this.state.acceptWebSocket(server, ["guest"]);
      server.serializeAttachment({ role: "guest", joined: false, visitorId: null });
      this.send(server, { t: "err", code: "busy" });
      this.shutdown(server, 4e3, "busy");
      return upgradeResponse(client);
    }
    this.state.acceptWebSocket(server, [role]);
    if (role === "sharer") {
      server.serializeAttachment({ role: "sharer", dev: request.headers.get("x-huchuan-dev") || "", joined: true });
    } else {
      server.serializeAttachment({ role: "guest", ep: request.headers.get("x-huchuan-ep") || "", joined: false, visitorId: null });
    }
    return upgradeResponse(client);
  }
  async meta() {
    return await this.storage.get("meta") || null;
  }
  async loadMeta(request) {
    const existing = await this.storage.get("meta");
    if (existing)
      return existing;
    const meta = {
      shareId: request.headers.get("x-huchuan-share") || "",
      tokenHash: request.headers.get("x-huchuan-token-hash") || "",
      revoked: false,
      createdAt: Date.now()
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
    if (!msg || typeof msg.t !== "string")
      return;
    if (attachment.role === "guest")
      return this.guestMessage(ws, attachment, msg);
    if (attachment.role === "sharer")
      return this.sharerMessage(ws, attachment, msg);
  }
  async guestMessage(ws, attachment, msg) {
    if (msg.t === "share-join") {
      if (attachment.joined)
        return;
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
      if (!await this.consumeJoinBudget()) {
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
        epPub: attachment.epPub
      });
      return;
    }
    if (msg.t === "answer" || msg.t === "ice") {
      if (!attachment.joined)
        return;
      const sharers = this.state.getWebSockets("sharer");
      if (sharers.length === 0)
        return;
      this.send(sharers[sharers.length - 1], msg);
      return;
    }
    if (msg.t === "bye") {
      const sharers = this.state.getWebSockets("sharer");
      for (const sharer of sharers)
        this.send(sharer, msg);
      this.shutdown(ws, 4003, "bye");
    }
  }
  sharerMessage(ws, attachment, msg) {
    if (msg.t !== "offer" && msg.t !== "ice" && msg.t !== "bye" && msg.t !== "err")
      return;
    for (const guest of this.state.getWebSockets("guest")) {
      const guestAttachment = guest.deserializeAttachment() || {};
      if (!guestAttachment.joined)
        continue;
      this.send(guest, msg);
    }
    if (msg.t === "bye")
      this.shutdown(ws, 4004, "bye");
  }
  async webSocketClose(ws) {
    const attachment = ws.deserializeAttachment() || {};
    const meta = await this.meta();
    if (meta && meta.revoked)
      return;
    if (attachment.role === "sharer") {
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
      guest: guests.length > 0
    });
  }
  async revoke() {
    const existing = await this.meta() || { shareId: "", tokenHash: "", revoked: false };
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
    const log = (await this.storage.get("joins") || []).filter((stamp) => now - stamp < SIGNAL_JOIN_WINDOW_MS);
    if (log.length >= SIGNAL_MAX_JOINS_PER_WINDOW)
      return false;
    log.push(now);
    await this.storage.put("joins", log);
    return true;
  }
  send(ws, object) {
    try {
      ws.send(JSON.stringify(object));
    } catch {
    }
  }
  shutdown(ws, code, reason) {
    try {
      ws.close(code, reason);
    } catch {
    }
  }
  fail(ws, code) {
    this.send(ws, { t: "err", code });
    this.shutdown(ws, 4005, code);
  }
};
__name(SignalRoom, "SignalRoom");

// src/index.js
var VERSION = "0.1.0";
var src_default = {
  async fetch(request, env, ctx) {
    return route(request, env, ctx);
  }
};
async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = normalizePath(url.pathname);
  try {
    if (request.method === "OPTIONS" && path.startsWith("/api/"))
      return preflight();
    if (path === "/health") {
      return json({
        ok: true,
        service: "huchuan-worker",
        version: VERSION,
        time: (/* @__PURE__ */ new Date()).toISOString(),
        bindings: {
          SWARM: !!env.SWARM,
          REGISTRY: !!env.REGISTRY,
          ROOM: !!env.ROOM
        }
      });
    }
    if (path === "/announce" || path.startsWith("/announce/")) {
      if (request.method !== "GET")
        return methodNotAllowed();
      return await handleAnnounce(request, env);
    }
    if (path === "/signal" || path === "/ws") {
      if (request.method !== "GET")
        return methodNotAllowed();
      return await handleSignal(request, env, ctx);
    }
    if (path === "/api/tracker/register" && request.method === "POST") {
      return await handleTrackerRegister(request, env);
    }
    if (path === "/api/share") {
      if (request.method !== "POST")
        return methodNotAllowed();
      return await handleCreateShare(request, env);
    }
    if (path.startsWith("/api/share/")) {
      const id = decodeURIComponent(path.slice("/api/share/".length));
      if (request.method === "GET")
        return await handleGetShare(id, env);
      if (request.method === "DELETE")
        return await handleDeleteShare(id, request, env);
      return methodNotAllowed();
    }
    if (path === "/api/shares" && request.method === "GET") {
      return await handleListShares(request, env);
    }
    return corsJson({ ok: false, error: "not-found" }, 404);
  } catch (error) {
    if (error instanceof HttpError)
      return corsJson({ ok: false, error: error.message }, error.status);
    console.error("worker error:", error && error.stack || error);
    return corsJson({ ok: false, error: "internal" }, 500);
  }
}
__name(route, "route");
function methodNotAllowed() {
  return corsJson({ ok: false, error: "method-not-allowed" }, 405);
}
__name(methodNotAllowed, "methodNotAllowed");
function normalizePath(pathname) {
  const trimmed = String(pathname || "/").replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}
__name(normalizePath, "normalizePath");

// C:/Users/20506/AppData/Local/npm-cache/_npx/0eedb5afd4158ff3/node_modules/wrangler/templates/middleware/middleware-ensure-req-body-drained.ts
var drainBody = /* @__PURE__ */ __name(async (request, env, _ctx, middlewareCtx) => {
  try {
    return await middlewareCtx.next(request, env);
  } finally {
    try {
      if (request.body !== null && !request.bodyUsed) {
        const reader = request.body.getReader();
        while (!(await reader.read()).done) {
        }
      }
    } catch (e) {
      console.error("Failed to drain the unused request body.", e);
    }
  }
}, "drainBody");
var middleware_ensure_req_body_drained_default = drainBody;

// .wrangler/tmp/bundle-MrXSwX/middleware-insertion-facade.js
var __INTERNAL_WRANGLER_MIDDLEWARE__ = [
  middleware_ensure_req_body_drained_default
];
var middleware_insertion_facade_default = src_default;

// C:/Users/20506/AppData/Local/npm-cache/_npx/0eedb5afd4158ff3/node_modules/wrangler/templates/middleware/common.ts
var __facade_middleware__ = [];
function __facade_register__(...args) {
  __facade_middleware__.push(...args.flat());
}
__name(__facade_register__, "__facade_register__");
function __facade_invokeChain__(request, env, ctx, dispatch, middlewareChain) {
  const [head, ...tail] = middlewareChain;
  const middlewareCtx = {
    dispatch,
    next(newRequest, newEnv) {
      return __facade_invokeChain__(newRequest, newEnv, ctx, dispatch, tail);
    }
  };
  return head(request, env, ctx, middlewareCtx);
}
__name(__facade_invokeChain__, "__facade_invokeChain__");
function __facade_invoke__(request, env, ctx, dispatch, finalMiddleware) {
  return __facade_invokeChain__(request, env, ctx, dispatch, [
    ...__facade_middleware__,
    finalMiddleware
  ]);
}
__name(__facade_invoke__, "__facade_invoke__");

// .wrangler/tmp/bundle-MrXSwX/middleware-loader.entry.ts
var __Facade_ScheduledController__ = class {
  constructor(scheduledTime, cron, noRetry) {
    this.scheduledTime = scheduledTime;
    this.cron = cron;
    this.#noRetry = noRetry;
  }
  #noRetry;
  noRetry() {
    if (!(this instanceof __Facade_ScheduledController__)) {
      throw new TypeError("Illegal invocation");
    }
    this.#noRetry();
  }
};
__name(__Facade_ScheduledController__, "__Facade_ScheduledController__");
function wrapExportedHandler(worker) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return worker;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  const fetchDispatcher = /* @__PURE__ */ __name(function(request, env, ctx) {
    if (worker.fetch === void 0) {
      throw new Error("Handler does not export a fetch() function.");
    }
    return worker.fetch(request, env, ctx);
  }, "fetchDispatcher");
  return {
    ...worker,
    fetch(request, env, ctx) {
      const dispatcher = /* @__PURE__ */ __name(function(type, init) {
        if (type === "scheduled" && worker.scheduled !== void 0) {
          const controller = new __Facade_ScheduledController__(
            Date.now(),
            init.cron ?? "",
            () => {
            }
          );
          return worker.scheduled(controller, env, ctx);
        }
      }, "dispatcher");
      return __facade_invoke__(request, env, ctx, dispatcher, fetchDispatcher);
    }
  };
}
__name(wrapExportedHandler, "wrapExportedHandler");
function wrapWorkerEntrypoint(klass) {
  if (__INTERNAL_WRANGLER_MIDDLEWARE__ === void 0 || __INTERNAL_WRANGLER_MIDDLEWARE__.length === 0) {
    return klass;
  }
  for (const middleware of __INTERNAL_WRANGLER_MIDDLEWARE__) {
    __facade_register__(middleware);
  }
  return class extends klass {
    #fetchDispatcher = (request, env, ctx) => {
      this.env = env;
      this.ctx = ctx;
      if (super.fetch === void 0) {
        throw new Error("Entrypoint class does not define a fetch() function.");
      }
      return super.fetch(request);
    };
    #dispatcher = (type, init) => {
      if (type === "scheduled" && super.scheduled !== void 0) {
        const controller = new __Facade_ScheduledController__(
          Date.now(),
          init.cron ?? "",
          () => {
          }
        );
        return super.scheduled(controller);
      }
    };
    fetch(request) {
      return __facade_invoke__(
        request,
        this.env,
        this.ctx,
        this.#dispatcher,
        this.#fetchDispatcher
      );
    }
  };
}
__name(wrapWorkerEntrypoint, "wrapWorkerEntrypoint");
var WRAPPED_ENTRY;
if (typeof middleware_insertion_facade_default === "object") {
  WRAPPED_ENTRY = wrapExportedHandler(middleware_insertion_facade_default);
} else if (typeof middleware_insertion_facade_default === "function") {
  WRAPPED_ENTRY = wrapWorkerEntrypoint(middleware_insertion_facade_default);
}
var middleware_loader_entry_default = WRAPPED_ENTRY;
export {
  ShareRegistry,
  SignalRoom,
  TrackerSwarm,
  __INTERNAL_WRANGLER_MIDDLEWARE__,
  middleware_loader_entry_default as default,
  route
};
//# sourceMappingURL=index.js.map
