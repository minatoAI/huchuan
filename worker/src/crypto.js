/**
 * 全部密码学原语：只用运行时自带的 Web Crypto（globalThis.crypto），
 * Workers 与 Node 20+ 都有，零第三方依赖。
 *
 * 用到的协议定义（protocol.md §1.1 / §1.2 / §2.2、link-share.md §6）：
 * - deviceId = B32(SHA-256("huchuan/dev1/v1" ‖ pub64)[0..10])，16 字符
 * - 公钥 = P-256 裸值 x‖y 64 B（128 hex），签名 = r‖s 64 B
 * - signBytes(domain, fields) = UTF8(domain) ‖ 0x00 ‖ 逐个 field (u16be 长度 ‖ 原始字节)
 * - token 哈希 = SHA-256("huchuan/tok1/v1" ‖ UTF8(token))
 * - tracker passkey 哈希 = SHA-256("huchuan/trk1/v1" ‖ UTF8(passkey_hex))
 */

const encoder = new TextEncoder();

export function utf8(str) {
  return encoder.encode(str);
}

export function bytesToHex(bytes) {
  let out = "";
  for (const b of bytes) out += b.toString(16).padStart(2, "0");
  return out;
}

export function hexToBytes(hex) {
  const text = String(hex);
  if (text.length % 2 !== 0 || !/^[0-9a-fA-F]*$/.test(text)) return null;
  const out = new Uint8Array(text.length / 2);
  for (let i = 0; i < out.length; i += 1) out[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
  return out;
}

export function concatBytes(...parts) {
  let total = 0;
  for (const p of parts) total += p.length;
  const out = new Uint8Array(total);
  let offset = 0;
  for (const p of parts) {
    out.set(p, offset);
    offset += p.length;
  }
  return out;
}

export async function sha256(bytes) {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return new Uint8Array(digest);
}

export async function hmacSha256(keyBytes, messageBytes) {
  const key = await crypto.subtle.importKey("raw", keyBytes, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const sig = await crypto.subtle.sign("HMAC", key, messageBytes);
  return new Uint8Array(sig);
}

/* ---------------------------------------------------------------- Crockford Base32 */

export const B32_ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

export function b32Encode(bytes) {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      out += B32_ALPHABET[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32_ALPHABET[(value << (5 - bits)) & 31];
  return out;
}

/** 解码；末尾补位必须为 0（protocol.md §1.2），否则返回 null。 */
export function b32Decode(text) {
  const source = String(text).toUpperCase();
  let bits = 0;
  let value = 0;
  const out = [];
  for (const ch of source) {
    const index = B32_ALPHABET.indexOf(ch);
    if (index < 0) return null;
    value = (value << 5) | index;
    bits += 5;
    if (bits >= 8) {
      out.push((value >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  if (bits > 0 && (value & ((1 << bits) - 1)) !== 0) return null;
  return new Uint8Array(out);
}

/** 大小写不敏感，O→0、I/L→1（protocol.md §2.1）；含 U 或非法字符返回 null。 */
export function normalizeB32(text) {
  const cleaned = String(text)
    .toUpperCase()
    .replace(/[\s-]/g, "")
    .replace(/O/g, "0")
    .replace(/[IL]/g, "1");
  if (!/^[0-9A-HJKMNP-TV-Z]*$/.test(cleaned)) return null;
  return cleaned;
}

/* ---------------------------------------------------------------- 身份与签名 */

export function deviceIdFromPub(pubBytes) {
  return sha256(concatBytes(utf8("huchuan/dev1/v1"), pubBytes)).then((digest) => b32Encode(digest.subarray(0, 10)));
}

function fieldBytes(field) {
  if (typeof field === "string") return utf8(field);
  if (field instanceof Uint8Array) return field;
  if (typeof field === "number") {
    if (!Number.isSafeInteger(field) || field < 0) throw new TypeError("signBytes: number fields must be non-negative safe integers");
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

export function signBytes(domain, fields) {
  const parts = [utf8(domain), new Uint8Array([0])];
  for (const field of fields) {
    const bytes = fieldBytes(field);
    if (bytes.length > 0xffff) throw new RangeError("signBytes: field too long");
    parts.push(new Uint8Array([bytes.length >> 8, bytes.length & 0xff]), bytes);
  }
  return concatBytes(...parts);
}

/** P-256 的 SPKI 前缀：ecPublicKey + prime256v1，后面接 64 B 裸公钥。 */
const SPKI_P256_PREFIX = new Uint8Array([
  0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x02, 0x01,
  0x06, 0x08, 0x2a, 0x86, 0x48, 0xce, 0x3d, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00, 0x04,
]);

/** P-256 阶 n；r/s 必须落在 [1, n-1]（protocol.md §1.1）。 */
const P256_ORDER = BigInt("0xffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");

function signatureComponentsValid(sigBytes) {
  if (!(sigBytes instanceof Uint8Array) || sigBytes.length !== 64) return false;
  const toBig = (slice) => BigInt("0x" + bytesToHex(slice));
  const r = toBig(sigBytes.subarray(0, 32));
  const s = toBig(sigBytes.subarray(32));
  return r > 0n && s > 0n && r < P256_ORDER && s < P256_ORDER;
}

export async function verifyP256(pubBytes, sigBytes, messageBytes) {
  if (!(pubBytes instanceof Uint8Array) || pubBytes.length !== 64) return false;
  if (!signatureComponentsValid(sigBytes)) return false;
  try {
    const spki = concatBytes(SPKI_P256_PREFIX, pubBytes);
    const key = await crypto.subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, sigBytes, messageBytes);
  } catch {
    return false;
  }
}

/* ---------------------------------------------------------------- 哈希与随机 */

export async function tokenHashHex(token) {
  const digest = await sha256(concatBytes(utf8("huchuan/tok1/v1"), utf8(String(token))));
  return bytesToHex(digest);
}

export async function passkeyHashHex(passkeyHex) {
  const digest = await sha256(concatBytes(utf8("huchuan/trk1/v1"), utf8(String(passkeyHex).toLowerCase())));
  return bytesToHex(digest);
}

export function constantTimeEqualHex(a, b) {
  const left = String(a || "").toLowerCase();
  const right = String(b || "").toLowerCase();
  if (left.length !== right.length || left.length === 0) return false;
  let diff = 0;
  for (let i = 0; i < left.length; i += 1) diff |= left.charCodeAt(i) ^ right.charCodeAt(i);
  return diff === 0;
}

export function randomBytes(length) {
  const out = new Uint8Array(length);
  crypto.getRandomValues(out);
  return out;
}

export function randomHex(byteLength) {
  return bytesToHex(randomBytes(byteLength));
}

/** 128 bit → 26 字符 Crockford B32（link-share.md §6）。 */
export function randomToken() {
  return b32Encode(randomBytes(16));
}

/** 连接/访客的短 id，默认 8 字符 = 40 bit。 */
export function randomShortId(chars = 8) {
  return b32Encode(randomBytes(5)).slice(0, chars);
}
