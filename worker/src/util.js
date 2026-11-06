/** 请求/响应与地址解析的小工具。 */

const CORS_HEADERS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET,POST,DELETE,OPTIONS",
  "access-control-allow-headers": "content-type",
  "access-control-max-age": "86400",
};

export function json(body, status = 200, headers = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...headers,
    },
  });
}

export function corsJson(body, status = 200) {
  return json(body, status, CORS_HEADERS);
}

export function preflight() {
  return new Response(null, { status: 204, headers: CORS_HEADERS });
}

export function text(body, status = 200, contentType = "text/plain; charset=utf-8") {
  return new Response(body, { status, headers: { "content-type": contentType, "cache-control": "no-store" } });
}

export async function readJson(request, maxBytes) {
  const length = Number(request.headers.get("content-length") || "0");
  if (length && length > maxBytes) throw new HttpError(413, "body too large");
  const raw = await request.text();
  if (raw.length > maxBytes) throw new HttpError(413, "body too large");
  if (!raw) return {};
  try {
    const parsed = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("not an object");
    return parsed;
  } catch {
    throw new HttpError(400, "invalid json");
  }
}

export class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/* ---------------------------------------------------------------- 地址 */

export function ipv4ToBytes(text) {
  const parts = String(text).split(".");
  if (parts.length !== 4) return null;
  const out = new Uint8Array(4);
  for (let i = 0; i < 4; i += 1) {
    if (!/^\d{1,3}$/.test(parts[i])) return null;
    const value = Number(parts[i]);
    if (value > 255) return null;
    out[i] = value;
  }
  return out;
}

export function ipv6ToBytes(raw) {
  let text = String(raw).trim();
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1);
  if (text.includes(".")) {
    const cut = text.lastIndexOf(":");
    if (cut < 0) return null;
    const v4 = ipv4ToBytes(text.slice(cut + 1));
    if (!v4) return null;
    const high = ((v4[0] << 8) | v4[1]).toString(16);
    const low = ((v4[2] << 8) | v4[3]).toString(16);
    text = text.slice(0, cut + 1) + high + ":" + low;
  }
  const halves = text.split("::");
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(":") : [];
  const tail = halves.length === 2 ? (halves[1] ? halves[1].split(":") : []) : null;
  if (tail === null && head.length !== 8) return null;
  if (tail !== null && head.length + tail.length > 7) return null;
  const groups = tail === null ? head : [...head, ...new Array(8 - head.length - tail.length).fill("0"), ...tail];
  if (groups.length !== 8) return null;
  const out = new Uint8Array(16);
  for (let i = 0; i < 8; i += 1) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(groups[i])) return null;
    const value = parseInt(groups[i], 16);
    out[i * 2] = value >> 8;
    out[i * 2 + 1] = value & 0xff;
  }
  return out;
}

/** 归一化请求来源地址：返回 { ip, family, bytes }，取不到返回 null。 */
export function normalizeAddress(raw) {
  let text = String(raw || "").trim();
  if (!text) return null;
  if (text.startsWith("[") && text.endsWith("]")) text = text.slice(1, -1);
  const zone = text.indexOf("%");
  if (zone > 0) text = text.slice(0, zone);
  const v4 = ipv4ToBytes(text);
  if (v4) return { ip: text, family: 4, bytes: v4 };
  if (/^::ffff:/i.test(text)) {
    const mapped = ipv4ToBytes(text.slice(7));
    if (mapped) return { ip: text.slice(7), family: 4, bytes: mapped };
  }
  const v6 = ipv6ToBytes(text);
  if (v6) return { ip: text, family: 6, bytes: v6 };
  return null;
}

export function clientAddress(request) {
  const headers = request.headers;
  const forwarded = (headers.get("x-forwarded-for") || "").split(",")[0].trim();
  const raw = headers.get("cf-connecting-ip") || headers.get("x-real-ip") || forwarded || "";
  return normalizeAddress(raw);
}

/* ---------------------------------------------------------------- 时间与 id */

export function nowMs() {
  return Date.now();
}

export function nowSec() {
  return Math.floor(Date.now() / 1000);
}
