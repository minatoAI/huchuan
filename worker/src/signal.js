/**
 * GET /signal（别名 /ws）—— WebRTC 信令入口（link-share.md §5、§6）。
 *
 * 两种连接：
 *   分享者 /signal?role=sharer&share=<shareId>&dev&ts&nonce&sig
 *           sig = signBytes("huchuan/ws1/v1",[dev,nonce,ts])，用该设备公钥验
 *   访客   /signal?role=guest&share=<shareId>&ep=<16 字符>
 *
 * 两种消息都在握手时验完，再把 socket 交给 shareId 对应的房间 DO：
 * token 不进任何 URL（只在 WS 帧的 share-join 里出现），URL 里只有 shareId。
 */

import { hexToBytes, signBytes, verifyP256 } from "./crypto.js";
import { SIGNAL_CLOCK_SKEW_SEC } from "./limits.js";
import { json, nowSec } from "./util.js";
import { roomStub } from "./share.js";

export async function handleSignal(request, env, ctx) {
  const url = new URL(request.url);
  const upgrade = (request.headers.get("upgrade") || "").toLowerCase();
  if (upgrade !== "websocket") return json({ ok: false, error: "websocket upgrade required" }, 426);

  const shareId = (url.searchParams.get("share") || "").trim();
  if (!/^[0-9a-fA-F]{32}$/.test(shareId)) return json({ ok: false, error: "invalid share" }, 400);

  const roleParam = (url.searchParams.get("role") || "").toLowerCase();
  const role = roleParam || (url.searchParams.has("ep") ? "guest" : "sharer");
  if (role !== "sharer" && role !== "guest") return json({ ok: false, error: "invalid role" }, 400);

  const record = await fetchRecord(env, shareId);
  if (!record) return json({ ok: false, error: "gone" }, 410);
  if (record.state === "revoked") return json({ ok: false, error: "revoked" }, 410);
  if (record.expiresAt <= Date.now()) return json({ ok: false, error: "expired" }, 410);

  const headers = new Headers(request.headers);
  headers.set("x-huchuan-role", role);
  headers.set("x-huchuan-share", record.shareId);
  headers.set("x-huchuan-token-hash", record.tokenHash);

  if (role === "sharer") {
    const dev = url.searchParams.get("dev") || "";
    const ts = Number(url.searchParams.get("ts"));
    const nonce = url.searchParams.get("nonce") || "";
    const sig = url.searchParams.get("sig") || "";
    if (!dev) return json({ ok: false, error: "missing dev" }, 400);
    if (!Number.isFinite(ts) || Math.abs(nowSec() - ts) > SIGNAL_CLOCK_SKEW_SEC) {
      return json({ ok: false, error: "ts outside allowed skew" }, 400);
    }
    if (!/^[A-Za-z0-9_-]{8,64}$/.test(nonce)) return json({ ok: false, error: "invalid nonce" }, 400);
    if (!/^[0-9a-fA-F]{128}$/.test(sig)) return json({ ok: false, error: "invalid sig" }, 400);
    if (record.deviceId !== dev.toUpperCase()) return json({ ok: false, error: "not-owner" }, 403);

    const pub = hexToBytes(record.pub);
    const message = signBytes("huchuan/ws1/v1", [dev.toUpperCase(), nonce, ts]);
    const ok = pub ? await verifyP256(pub, hexToBytes(sig), message) : false;
    if (!ok) return json({ ok: false, error: "bad-signature" }, 403);

    headers.set("x-huchuan-dev", dev.toUpperCase());
    return roomStub(env, record.shareId).fetch(new Request("https://room.internal/join", { method: "GET", headers }));
  }

  const ep = (url.searchParams.get("ep") || "").trim();
  if (!/^[A-Za-z0-9_-]{8,32}$/.test(ep)) return json({ ok: false, error: "invalid ep" }, 400);
  headers.set("x-huchuan-ep", ep);

  const response = await roomStub(env, record.shareId).fetch(new Request("https://room.internal/join", { method: "GET", headers }));
  if (response.status === 101 && ctx && typeof ctx.waitUntil === "function") {
    ctx.waitUntil(touchShare(env, record.shareId));
  }
  return response;
}

/** 访客握手成功即记一次「被访问」（link-share.md §8 的列表字段） */
async function touchShare(env, shareId) {
  try {
    await env.REGISTRY.get(env.REGISTRY.idFromName("registry")).fetch("https://registry.internal/touch", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ shareId }),
    });
  } catch {
    /* 计数失败不影响连接 */
  }
}

async function fetchRecord(env, shareId) {
  const reply = await env.REGISTRY.get(env.REGISTRY.idFromName("registry")).fetch(
    "https://registry.internal/get?shareId=" + encodeURIComponent(shareId),
  );
  const data = await reply.json();
  return data.record || null;
}
