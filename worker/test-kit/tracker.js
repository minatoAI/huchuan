/** tracker 测试的共用夹具：注册群、构造 announce 请求、解 bencode。 */

import { bytesToHex, hexToBytes, sha256, utf8 } from "../src/crypto.js";
import { bdecode } from "../src/bencode.js";
import { makeRequest } from "./memory.js";

export function percentEncode(bytes) {
  let out = "";
  for (const byte of bytes) out += "%" + byte.toString(16).padStart(2, "0");
  return out;
}

/** groupHash20 = SHA-256("huchuan/gh1/v1"‖codeBytes)[0..20]（protocol.md §2.2）的测试替身 */
export async function groupHash20(seed) {
  const digest = await sha256(utf8("huchuan/gh1/v1" + seed));
  return digest.slice(0, 20);
}

export function peerIdBytes(tag = "A") {
  return utf8(("-HC0001-" + tag.repeat(12)).slice(0, 20));
}

export async function postJson(workerModule, env, path, body, ctx) {
  const response = await workerModule.route(
    makeRequest(path, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify(body) }),
    env,
    ctx,
  );
  const text = await response.text();
  return { status: response.status, body: text ? JSON.parse(text) : null };
}

export async function registerGroup(workerModule, env, { infoHashHex, gid, passkey, prevPasskey }) {
  return postJson(workerModule, env, "/api/tracker/register", { infoHash: infoHashHex, gid, passkey, prevPasskey });
}

/**
 * 发一次 announce。
 * @param {object} options infoHash(hex) / passkey / port / ip / peerId / event / numwant / compact / passkeyInPath
 */
export async function announce(workerModule, env, options) {
  const {
    infoHash,
    passkey,
    port = 47100,
    ip = "203.0.113.7",
    peerId = peerIdBytes("A"),
    event,
    numwant,
    compact = 1,
    passkeyInPath = false,
    infoHashBytes,
  } = options;

  const hashBytes = infoHashBytes || hexToBytes(infoHash);
  const params = ["info_hash=" + percentEncode(hashBytes)];
  if (peerId) params.push("peer_id=" + percentEncode(peerId));
  params.push("port=" + port);
  params.push("left=0");
  if (compact !== undefined) params.push("compact=" + compact);
  if (event) params.push("event=" + event);
  if (numwant !== undefined) params.push("numwant=" + numwant);
  if (passkey && !passkeyInPath) params.push("passkey=" + passkey);

  const path = "/announce" + (passkeyInPath ? "/" + passkey : "") + "?" + params.join("&");
  const response = await workerModule.route(makeRequest(path, { headers: { "cf-connecting-ip": ip } }), env, undefined);
  const body = bdecode(new Uint8Array(await response.arrayBuffer()));
  return { response, body };
}

export function textOf(value) {
  if (value === undefined || value === null) return null;
  return new TextDecoder().decode(value);
}

export function failureOf(body) {
  return textOf(body["failure reason"]);
}

export { bytesToHex, hexToBytes };
