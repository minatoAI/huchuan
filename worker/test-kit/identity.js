/** 测试用 P-256 身份：与客户端协议共用 src/crypto.js 的 signBytes。 */

import { bytesToHex, deviceIdFromPub, hexToBytes, randomHex, signBytes } from "../src/crypto.js";

export async function createIdentity() {
  const keyPair = await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"]);
  const raw = new Uint8Array(await crypto.subtle.exportKey("raw", keyPair.publicKey));
  const pub = raw.slice(1);
  const pubHex = bytesToHex(pub);
  const deviceId = await deviceIdFromPub(pub);
  return {
    pub,
    pubHex,
    deviceId,
    async sign(domain, fields) {
      const message = signBytes(domain, fields);
      const signature = new Uint8Array(await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, keyPair.privateKey, message));
      return bytesToHex(signature);
    },
  };
}

/** 造一个假 token（26 字符 Crockford B32 = 128 bit）。 */
export function fakeToken() {
  const alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
  let token = "";
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  let bits = 0;
  let value = 0;
  for (const byte of bytes) {
    value = (value << 8) | byte;
    bits += 8;
    while (bits >= 5) {
      token += alphabet[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) token += alphabet[(value << (5 - bits)) & 31];
  return token;
}

export { hexToBytes, randomHex };
