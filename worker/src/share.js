/**
 * /api/share* —— 分享注册表的 HTTP 面（link-share.md §6）。
 *
 * 四态（available / offline / revoked / expired）只在这里合成一次，
 * 别处（客户端、房间）不再自己判过期或撤销。
 */

import {
  b32Decode,
  deviceIdFromPub,
  hexToBytes,
  normalizeB32,
  passkeyHashHex,
  randomHex,
  randomToken,
  signBytes,
  tokenHashHex,
  verifyP256,
} from "./crypto.js";
import {
  MAX_FILE_BYTES,
  MAX_FILE_NAME_BYTES,
  MAX_JSON_BODY_BYTES,
  MAX_SHARE_FILES,
  SHARE_TTL_DEFAULT_SEC,
  SHARE_TTL_MAX_SEC,
  SHARE_TTL_MIN_SEC,
  SIGNAL_CLOCK_SKEW_SEC,
} from "./limits.js";
import { HttpError, corsJson, json, nowSec, readJson } from "./util.js";

export function registryStub(env) {
  return env.REGISTRY.get(env.REGISTRY.idFromName("registry"));
}

export function roomStub(env, shareId) {
  return env.ROOM.get(env.ROOM.idFromName(shareId));
}

async function registryCall(env, path, init) {
  const reply = await registryStub(env).fetch("https://registry.internal" + path, init);
  return reply.json();
}

/* ---------------------------------------------------------------- POST /api/share */

export async function handleCreateShare(request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);

  const pubHex = String(body.pub || "");
  if (!/^[0-9a-fA-F]{128}$/.test(pubHex)) throw new HttpError(400, "pub must be 128 hex chars");
  const pub = hexToBytes(pubHex);

  const deviceId = normalizeB32(body.deviceId || "");
  if (!deviceId || deviceId.length !== 16) throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");
  const derived = await deviceIdFromPub(pub);
  if (derived !== deviceId) throw new HttpError(400, "deviceId does not match pub");

  const files = normalizeFiles(body.files);

  let token = null;
  if (body.token !== undefined && body.token !== null && body.token !== "") {
    token = normalizeToken(body.token);
    if (!token) throw new HttpError(400, "token must be 26 Crockford B32 chars (128 bit)");
  } else {
    token = randomToken();
  }

  const requestedTtl = Number(body.ttlSec);
  const ttlSec = Number.isFinite(requestedTtl) && requestedTtl > 0
    ? Math.min(Math.max(Math.floor(requestedTtl), SHARE_TTL_MIN_SEC), SHARE_TTL_MAX_SEC)
    : SHARE_TTL_DEFAULT_SEC;

  const createdAt = Date.now();
  const expiresAt = createdAt + ttlSec * 1000;
  const shareId = randomHex(16);
  const tokenHash = await tokenHashHex(token);

  const result = await registryCall(env, "/create", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ shareId, tokenHash, deviceId, pub: pubHex.toLowerCase(), files, createdAt, expiresAt }),
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

/* ---------------------------------------------------------------- GET /api/share/{token} */

export async function handleGetShare(tokenParam, env) {
  const token = normalizeToken(tokenParam);
  if (!token) return corsJson({ status: "not-found" }, 404);
  const tokenHash = await tokenHashHex(token);
  const result = await registryCall(env, "/get?tokenHash=" + tokenHash);
  const record = result.record;
  if (!record) return corsJson({ status: "not-found" }, 404);

  const status = await shareStatus(env, record);
  return corsJson({
    status,
    shareId: record.shareId,
    deviceId: record.deviceId,
    pub: record.pub,
    files: record.files,
    createdAt: record.createdAt,
    expiresAt: record.expiresAt,
    visitCount: record.visitCount || 0,
  });
}

export async function shareStatus(env, record) {
  if (record.state === "revoked") return "revoked";
  if (record.expiresAt <= Date.now()) return "expired";
  const room = await roomState(env, record.shareId);
  if (room.revoked) return "revoked";
  return room.sharer ? "available" : "offline";
}

async function roomState(env, shareId) {
  try {
    const reply = await roomStub(env, shareId).fetch("https://room.internal/status", { method: "GET" });
    const data = await reply.json();
    return { sharer: !!data.sharer, guest: !!data.guest, revoked: !!data.revoked };
  } catch {
    return { sharer: false, guest: false, revoked: false };
  }
}

/* ---------------------------------------------------------------- DELETE /api/share/{id} */

export async function handleDeleteShare(idParam, request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);

  const deviceId = normalizeB32(body.deviceId || "");
  if (!deviceId || deviceId.length !== 16) throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");

  const record = await resolveShare(env, idParam);
  if (!record) return corsJson({ ok: false, error: "not-found" }, 404);

  const token = normalizeToken(body.token);
  if (!token) throw new HttpError(400, "token required to sign the revoke request");
  if ((await tokenHashHex(token)) !== record.tokenHash) throw new HttpError(400, "token does not match this share");
  if (record.deviceId !== deviceId) return corsJson({ ok: false, error: "not-owner" }, 403);

  const ts = Number(body.ts);
  if (!Number.isFinite(ts) || Math.abs(nowSec() - ts) > SIGNAL_CLOCK_SKEW_SEC) {
    throw new HttpError(400, "ts outside allowed skew");
  }
  const nonce = String(body.nonce || "");
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(nonce)) throw new HttpError(400, "invalid nonce");
  const sig = String(body.sig || "");
  if (!/^[0-9a-fA-F]{128}$/.test(sig)) throw new HttpError(400, "invalid sig");

  const message = signBytes("huchuan/revoke1/v1", [deviceId, token, ts, nonce]);
  const pub = hexToBytes(record.pub);
  const ok = pub ? await verifyP256(pub, hexToBytes(sig), message) : false;
  if (!ok) return corsJson({ ok: false, error: "bad-signature" }, 403);

  const result = await registryCall(env, "/revoke", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ shareId: record.shareId, now: Date.now() }),
  });

  // 撤销要立刻生效：注册表负责「后来者打不开」，房间负责「踢掉已经连着的那条」。
  await kickRoom(env, record.shareId);

  console.log(JSON.stringify({ ev: "share.revoke", shareId: record.shareId, alreadyRevoked: !!result.alreadyRevoked }));
  return corsJson({
    ok: true,
    shareId: record.shareId,
    revokedAt: result.revokedAt || Date.now(),
    alreadyRevoked: !!result.alreadyRevoked,
  });
}

async function kickRoom(env, shareId) {
  try {
    await roomStub(env, shareId).fetch("https://room.internal/revoke", { method: "POST" });
  } catch {
    /* 房间不在线时无需踢人 */
  }
}

async function resolveShare(env, idParam) {
  const raw = String(idParam || "");
  if (/^[0-9a-fA-F]{32}$/.test(raw)) {
    const byId = await registryCall(env, "/get?shareId=" + raw.toLowerCase());
    if (byId.record) return byId.record;
  }
  const token = normalizeToken(raw);
  if (!token) return null;
  const byToken = await registryCall(env, "/get?tokenHash=" + (await tokenHashHex(token)));
  return byToken.record || null;
}

/* ---------------------------------------------------------------- GET /api/shares */

export async function handleListShares(request, env) {
  const url = new URL(request.url);
  const deviceId = normalizeB32(url.searchParams.get("deviceId") || "");
  if (!deviceId || deviceId.length !== 16) throw new HttpError(400, "deviceId must be 16 Crockford B32 chars");
  const result = await registryCall(env, "/list?deviceId=" + deviceId + "&now=" + Date.now());
  const shares = (result.shares || []).map((record) => ({
    shareId: record.shareId,
    files: record.files,
    createdAt: record.createdAt,
    expiresAt: record.expiresAt,
    visitCount: record.visitCount || 0,
  }));
  return corsJson({ shares });
}

/* ---------------------------------------------------------------- POST /api/tracker/register */

export async function handleTrackerRegister(request, env) {
  const body = await readJson(request, MAX_JSON_BODY_BYTES);

  const infoHash = String(body.infoHash || "").toLowerCase();
  if (!/^[0-9a-f]{40}$/.test(infoHash)) throw new HttpError(400, "infoHash must be 40 hex chars (groupHash20)");

  const gid = normalizeB32(body.gid || "");
  if (!gid || gid.length !== 16) throw new HttpError(400, "gid must be 16 Crockford B32 chars");

  const passkey = String(body.passkey || "").toLowerCase();
  if (!/^[0-9a-f]{64}$/.test(passkey)) throw new HttpError(400, "passkey must be 64 hex chars");
  const prevPasskey = body.prevPasskey ? String(body.prevPasskey).toLowerCase() : null;
  if (prevPasskey !== null && !/^[0-9a-f]{64}$/.test(prevPasskey)) throw new HttpError(400, "prevPasskey must be 64 hex chars");

  const passKeyHash = await passkeyHashHex(passkey);
  const prevPassKeyHash = prevPasskey ? await passkeyHashHex(prevPasskey) : null;

  const swarm = env.SWARM.get(env.SWARM.idFromName(infoHash));
  const reply = await swarm.fetch("https://swarm.internal/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ infoHash, gid, passKeyHash, prevPassKeyHash }),
  });
  const data = await reply.json();
  if (!data.ok && data.error === "conflict") return json(data, 409);
  return json(data, data.ok ? 200 : 400);
}

/* ---------------------------------------------------------------- 校验工具 */

export function normalizeToken(value) {
  const text = normalizeB32(value || "");
  if (!text || text.length !== 26) return null;
  const bytes = b32Decode(text);
  if (!bytes || bytes.length !== 16) return null;
  return text;
}

function normalizeFiles(files) {
  if (!Array.isArray(files) || files.length === 0) throw new HttpError(400, "files must be a non-empty array");
  if (files.length > MAX_SHARE_FILES) throw new HttpError(400, "at most " + MAX_SHARE_FILES + " files");
  const encoder = new TextEncoder();
  return files.map((file) => {
    if (!file || typeof file !== "object") throw new HttpError(400, "invalid file entry");
    const name = String(file.name || "");
    const nameBytes = encoder.encode(name).length;
    if (!name || nameBytes > MAX_FILE_NAME_BYTES) throw new HttpError(400, "invalid file name");
    const size = Number(file.size);
    if (!Number.isFinite(size) || size < 0 || size > MAX_FILE_BYTES) throw new HttpError(400, "invalid file size");
    const mime = file.mime === undefined || file.mime === null ? "" : String(file.mime).slice(0, 127);
    return { name, size, mime };
  });
}
