import test from "node:test";
import assert from "node:assert/strict";

import * as workerModule from "../src/index.js";
import { createTestEnv } from "../test-kit/memory.js";
import { announce, failureOf, groupHash20, peerIdBytes, registerGroup, textOf } from "../test-kit/tracker.js";
import { bytesToHex, utf8 } from "../src/crypto.js";

const PASSKEY = "ab".repeat(32);
const OTHER_PASSKEY = "cd".repeat(32);
const GID = "7Q2F9C4M1XZ8KD3V";

/** 测试用的 20 字节 peer_id，保证互不相同 */
function pid(index) {
  return utf8(String(index).padStart(20, "0"));
}

async function setup(seed) {
  const env = createTestEnv(workerModule);
  const infoHashBytes = await groupHash20(seed);
  const infoHash = bytesToHex(infoHashBytes);
  const registered = await registerGroup(workerModule, env, { infoHashHex: infoHash, gid: GID, passkey: PASSKEY });
  assert.equal(registered.status, 200);
  assert.equal(registered.body.ok, true);
  return { env, infoHash, infoHashBytes };
}

test("未注册的 info_hash 一律拒绝（只服务我们自己的群）", async () => {
  const env = createTestEnv(workerModule);
  const infoHashBytes = await groupHash20("unregistered");
  const { body } = await announce(workerModule, env, { infoHashBytes, passkey: PASSKEY });
  assert.equal(failureOf(body), "info hash not registered");
});

test("注册后第一次 announce：interval 600 / min interval 300 / 空 peers", async () => {
  const { env, infoHash } = await setup("first");
  const { response, body } = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "203.0.113.7",
    port: 47100,
    peerId: pid(1),
  });
  assert.equal(response.status, 200);
  assert.equal(body.interval, 600);
  assert.equal(body["min interval"], 300);
  assert.equal(body.complete, 1);
  assert.equal(body.incomplete, 0);
  assert.equal(body.peers.length, 0);
  assert.equal(body.peers6.length, 0);
  assert.equal(textOf(body["external ip"]), "203.0.113.7");
});

test("错 passkey 被拒，而且不落 peer", async () => {
  const { env, infoHash } = await setup("badpass");
  const bad = await announce(workerModule, env, {
    infoHash,
    passkey: OTHER_PASSKEY,
    ip: "198.51.100.10",
    port: 40000,
    peerId: pid(2),
  });
  assert.equal(failureOf(bad.body), "bad passkey");

  const good = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.11",
    port: 40001,
    peerId: pid(3),
  });
  assert.equal(good.body.peers.length, 0, "坏 passkey 那次不能留下 peer");
});

test("passkey 走 /announce/<passkey> 路径也认（magnet 里就是这么拼的）", async () => {
  const { env, infoHash } = await setup("pathpasskey");
  const { body } = await announce(workerModule, env, { infoHash, passkey: PASSKEY, passkeyInPath: true });
  assert.equal(body.interval, 600);
  assert.equal(failureOf(body), null);
});

test("同一个 swarm 里第二个 peer 能看到第一个（compact 6 字节）", async () => {
  const { env, infoHash } = await setup("swarm");
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "203.0.113.7", port: 47100, peerId: pid(1) });
  const second = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.9",
    port: 51413,
    peerId: pid(2),
  });
  assert.equal(second.body.peers.length, 6);
  assert.deepEqual([...second.body.peers], [203, 0, 113, 7, 0xb7, 0xfc]);
});

test("IPv6 peer 走 peers6（16 字节地址 + 2 字节端口）", async () => {
  const { env, infoHash } = await setup("v6");
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "203.0.113.7", port: 47100, peerId: pid(1) });
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "2001:db8::5", port: 51413, peerId: pid(2) });

  const third = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.20",
    port: 6881,
    peerId: pid(3),
  });
  assert.deepEqual([...third.body.peers], [203, 0, 113, 7, 0xb7, 0xfc]);
  assert.equal(third.body.peers6.length, 18);
  assert.deepEqual([...third.body.peers6], [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 5, 0xc8, 0xd5]);
});

test("numwant 上限 32；numwant=0 就不给", async () => {
  const { env, infoHash } = await setup("numwant");
  for (let i = 1; i <= 40; i += 1) {
    await announce(workerModule, env, {
      infoHash,
      passkey: PASSKEY,
      ip: "198.51.100." + i,
      port: 10000 + i,
      peerId: pid(i),
    });
  }
  const capped = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "203.0.113.99",
    port: 50000,
    peerId: pid(99),
    numwant: 100,
  });
  assert.equal(capped.body.peers.length, 6 * 32);

  const zero = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "203.0.113.98",
    port: 50001,
    peerId: pid(98),
    numwant: 0,
  });
  assert.equal(zero.body.peers.length, 0);
});

test("compact=0 退回字典列表（BEP 3 老格式）", async () => {
  const { env, infoHash } = await setup("compact0");
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "203.0.113.7", port: 47100, peerId: pid(1) });
  const { body } = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.9",
    port: 51413,
    peerId: pid(2),
    compact: 0,
  });
  assert.ok(Array.isArray(body.peers));
  assert.equal(body.peers.length, 1);
  assert.equal(textOf(body.peers[0].ip), "203.0.113.7");
  assert.equal(body.peers[0].port, 47100);
  assert.equal(body.peers[0]["peer id"].length, 20);
});

test("event=stopped 把 peer 摘掉", async () => {
  const { env, infoHash } = await setup("stopped");
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "203.0.113.7", port: 47100, peerId: pid(1) });
  await announce(workerModule, env, { infoHash, passkey: PASSKEY, ip: "198.51.100.9", port: 51413, peerId: pid(2) });

  const stopped = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.9",
    port: 51413,
    peerId: pid(2),
    event: "stopped",
  });
  assert.equal(stopped.body.complete, 1);

  const third = await announce(workerModule, env, {
    infoHash,
    passkey: PASSKEY,
    ip: "198.51.100.20",
    port: 6881,
    peerId: pid(3),
  });
  assert.deepEqual([...third.body.peers], [203, 0, 113, 7, 0xb7, 0xfc]);
});

test("畸形入参回 failure reason，不 500", async () => {
  const { env, infoHash } = await setup("malformed");

  const shortHash = await announce(workerModule, env, { infoHashBytes: new Uint8Array(10), passkey: PASSKEY });
  assert.equal(failureOf(shortHash.body), "invalid info_hash");

  const noPasskey = await announce(workerModule, env, { infoHash, passkey: "zz" });
  assert.equal(failureOf(noPasskey.body), "missing or malformed passkey");

  const badEvent = await announce(workerModule, env, { infoHash, passkey: PASSKEY, event: "nonsense" });
  assert.equal(failureOf(badEvent.body), "invalid event");
});

test("同一个群重复注册：同 passkey 幂等，换 passkey 要带 prevPasskey", async () => {
  const env = createTestEnv(workerModule);
  const infoHashBytes = await groupHash20("rotate");
  const infoHash = bytesToHex(infoHashBytes);
  const first = await registerGroup(workerModule, env, { infoHashHex: infoHash, gid: GID, passkey: PASSKEY });
  assert.equal(first.body.created, true);

  const same = await registerGroup(workerModule, env, { infoHashHex: infoHash, gid: GID, passkey: PASSKEY });
  assert.equal(same.body.created, false);

  const conflict = await registerGroup(workerModule, env, { infoHashHex: infoHash, gid: GID, passkey: OTHER_PASSKEY });
  assert.equal(conflict.status, 409);

  const rotated = await registerGroup(workerModule, env, {
    infoHashHex: infoHash,
    gid: GID,
    passkey: OTHER_PASSKEY,
    prevPasskey: PASSKEY,
  });
  assert.equal(rotated.status, 200);
  assert.equal(rotated.body.rotated, true);

  const withNew = await announce(workerModule, env, { infoHash, passkey: OTHER_PASSKEY });
  assert.equal(failureOf(withNew.body), null);
  const withOld = await announce(workerModule, env, { infoHash, passkey: PASSKEY });
  assert.equal(failureOf(withOld.body), "bad passkey");
});
