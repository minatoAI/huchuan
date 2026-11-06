/**
 * 互传 · Cloudflare Worker 入口。
 *
 * 一条部署三件事（link-share.md §4.3）：
 *   /announce        BEP 3 tracker（跨网发现）
 *   /signal | /ws    WebRTC 信令
 *   /api/share*      分享注册表
 *   /health          健康检查
 */

import { handleAnnounce } from "./announce.js";
import {
  handleCreateShare,
  handleDeleteShare,
  handleGetShare,
  handleListShares,
  handleTrackerRegister,
} from "./share.js";
import { handleSignal } from "./signal.js";
import { HttpError, corsJson, json, preflight } from "./util.js";

export { TrackerSwarm } from "./swarm.js";
export { ShareRegistry } from "./registry.js";
export { SignalRoom } from "./room.js";

const VERSION = "0.1.0";

export default {
  async fetch(request, env, ctx) {
    return route(request, env, ctx);
  },
};

export async function route(request, env, ctx) {
  const url = new URL(request.url);
  const path = normalizePath(url.pathname);

  try {
    if (request.method === "OPTIONS" && path.startsWith("/api/")) return preflight();

    if (path === "/health") {
      return json({
        ok: true,
        service: "huchuan-worker",
        version: VERSION,
        time: new Date().toISOString(),
        bindings: {
          SWARM: !!env.SWARM,
          REGISTRY: !!env.REGISTRY,
          ROOM: !!env.ROOM,
        },
      });
    }

    if (path === "/announce" || path.startsWith("/announce/")) {
      if (request.method !== "GET") return methodNotAllowed();
      return await handleAnnounce(request, env);
    }

    if (path === "/signal" || path === "/ws") {
      if (request.method !== "GET") return methodNotAllowed();
      return await handleSignal(request, env, ctx);
    }

    if (path === "/api/tracker/register" && request.method === "POST") {
      return await handleTrackerRegister(request, env);
    }

    if (path === "/api/share") {
      if (request.method !== "POST") return methodNotAllowed();
      return await handleCreateShare(request, env);
    }

    if (path.startsWith("/api/share/")) {
      const id = decodeURIComponent(path.slice("/api/share/".length));
      if (request.method === "GET") return await handleGetShare(id, env);
      if (request.method === "DELETE") return await handleDeleteShare(id, request, env);
      return methodNotAllowed();
    }

    if (path === "/api/shares" && request.method === "GET") {
      return await handleListShares(request, env);
    }

    return corsJson({ ok: false, error: "not-found" }, 404);
  } catch (error) {
    if (error instanceof HttpError) return corsJson({ ok: false, error: error.message }, error.status);
    console.error("worker error:", (error && error.stack) || error);
    return corsJson({ ok: false, error: "internal" }, 500);
  }
}

function methodNotAllowed() {
  return corsJson({ ok: false, error: "method-not-allowed" }, 405);
}

function normalizePath(pathname) {
  const trimmed = String(pathname || "/").replace(/\/+$/, "");
  return trimmed === "" ? "/" : trimmed;
}
