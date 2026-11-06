import test from "node:test";
import assert from "node:assert/strict";

import {
  B32_ALPHABET,
  b32Decode,
  b32Encode,
  bytesToHex,
  deviceIdFromPub,
  hexToBytes,
  randomToken,
  sha256,
  signBytes,
  tokenHashHex,
  utf8,
  verifyP256,
} from "../src/crypto.js";
import { createIdentity } from "../test-kit/identity.js";

test("Crockford B32：128 bit ↔ 26 字符，补位必须为 0", () => {
  const bytes = hexToBytes("000102030405060708090a0b0c0d0e0f");
  const encoded = b32Encode(bytes);
  assert.equal(encoded.length, 26);
  assert.deepEqual([...b32Decode(encoded)], [...bytes]);
  assert.equal(b32Decode("U".repeat(26)), null, "U 不在字母表里");
  assert.equal(b32Decode(b32Encode(new Uint8Array([1])) + "1"), null, "末尾补位不为 0 要拒");
  for (const ch of encoded) assert.ok(B32_ALPHABET.includes(ch));
});

test("randomToken 出 26 字符、能被解码回 16 字节", () => {
  const token = randomToken();
  assert.equal(token.length, 26);
  assert.equal(b32Decode(token).length, 16);
});

test("signBytes 的字节布局：domain ‖ 0x00 ‖ u16be(len)‖bytes", () => {
  const message = signBytes("d", ["ab", 1]);
  const expected = new Uint8Array([
    ...utf8("d"), 0x00,
    0x00, 0x02, ...utf8("ab"),
    0x00, 0x08, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01,
  ]);
  assert.deepEqual([...message], [...expected]);
});

test("deviceId = B32(SHA-256(prefix‖pub)[0..10])，16 字符且稳定", async () => {
  const identity = await createIdentity();
  const again = await deviceIdFromPub(identity.pub);
  assert.equal(identity.deviceId, again);
  assert.equal(identity.deviceId.length, 16);
  const digest = await sha256(new Uint8Array([...utf8("huchuan/dev1/v1"), ...identity.pub]));
  assert.equal(b32Encode(digest.subarray(0, 10)), identity.deviceId);
});

test("P-256 验签：正确通、改一个字节不通、r=0 不通", async () => {
  const identity = await createIdentity();
  const message = signBytes("huchuan/ws1/v1", ["DEV", "nonce", 1700000000]);
  const signature = await identity.sign("huchuan/ws1/v1", ["DEV", "nonce", 1700000000]);
  const sigBytes = hexToBytes(signature);
  assert.equal(sigBytes.length, 64);
  assert.equal(await verifyP256(identity.pub, sigBytes, message), true);

  const tampered = new Uint8Array(message);
  tampered[tampered.length - 1] ^= 0xff;
  assert.equal(await verifyP256(identity.pub, sigBytes, tampered), false);

  const zeroR = new Uint8Array(64);
  zeroR.set(sigBytes.subarray(32), 32);
  assert.equal(await verifyP256(identity.pub, zeroR, message), false);

  // 另一把钥匙的签名不认
  const other = await createIdentity();
  const otherSig = await other.sign("huchuan/ws1/v1", ["DEV", "nonce", 1700000000]);
  assert.equal(await verifyP256(identity.pub, hexToBytes(otherSig), message), false);
});

test("token 哈希：同 token 稳定、不同 token 不同、长度 64 hex", async () => {
  const token = randomToken();
  const first = await tokenHashHex(token);
  const second = await tokenHashHex(token);
  assert.equal(first, second);
  assert.equal(first.length, 64);
  assert.match(first, /^[0-9a-f]{64}$/);
  assert.notEqual(await tokenHashHex(randomToken()), first);
  assert.equal(bytesToHex(hexToBytes(first)), first);
});
