import test from "node:test";
import assert from "node:assert/strict";

import * as workerModule from "../src/index.js";
import { createCtx, createTestEnv, makeRequest } from "../test-kit/memory.js";
import { createIdentity } from "../test-kit/identity.js";
import { randomToken } from "../src/crypto.js";

const FILES = [{ name: "季度报告.pdf", size: 1024, mime: "application/pdf" }];
const WEEK_MS = 7 * 24 * 3600 * 1000;

async function api(env, path, options = {}) {
  const response = await workerModule.route(makeRequest(path, options), env, createCtx());
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  return { status: response.status, body, headers: response.headers };
}

function createShare(env, identity, overrides = {}) {
  return api(env, "/api/share", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId: identity.deviceId, pub: identity.pubHex, files: FILES, ...overrides }),
  });
}

async function revokeBody(identity, token, overrides = {}) {
  const body = {
    deviceId: identity.deviceId,
    token,
    ts: Math.floor(Date.now() / 1000),
    nonce: "nonce-0001",
    ...overrides,
  };
  if (!body.sig) {
    body.sig = await identity.sign("huchuan/revoke1/v1", [body.deviceId, body.token, body.ts, body.nonce]);
  }
  return body;
}

test("创建分享：回 shareId + 26 字符 token + 7 天后过期；GET 是 offline", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const created = await createShare(env, identity);

  assert.equal(created.status, 201);
  assert.equal(created.body.ok, true);
  assert.match(created.body.shareId, /^[0-9a-f]{32}$/);
  assert.equal(created.body.token.length, 26);
  assert.equal(created.body.expiresAt - created.body.createdAt, WEEK_MS);

  const got = await api(env, "/api/share/" + created.body.token);
  assert.equal(got.status, 200);
  assert.equal(got.body.status, "offline");
  assert.equal(got.body.shareId, created.body.shareId);
  assert.equal(got.body.deviceId, identity.deviceId);
  assert.equal(got.body.pub, identity.pubHex);
  assert.deepEqual(got.body.files, FILES);
  assert.equal(got.body.expiresAt, created.body.expiresAt);
  assert.match(got.headers.get("access-control-allow-origin"), /\*/);
});

test("token 可由客户端给，也可以由服务端生成（都是 128 bit）", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);

  const clientToken = randomToken();
  const withClientToken = await createShare(env, identity, { token: clientToken });
  assert.equal(withClientToken.status, 201);
  assert.equal(withClientToken.body.token, clientToken);

  const serverToken = await createShare(env, identity, {});
  assert.equal(serverToken.status, 201);
  assert.equal(serverToken.body.token.length, 26);
  assert.notEqual(serverToken.body.token, clientToken);
});

test("deviceId 与 pub 对不上就是 400（身份不能自报）", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const badDevice = await createShare(env, identity, { deviceId: "0000000000000000" });
  assert.equal(badDevice.status, 400);

  const badPub = await createShare(env, identity, { pub: "ff".repeat(64) });
  assert.equal(badPub.status, 400);
});

test("文件清单有边界：空、超 10 个、超 1 GiB、名字超 255 B 全拒", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);

  assert.equal((await createShare(env, identity, { files: [] })).status, 400);
  const many = Array.from({ length: 11 }, (_, i) => ({ name: "f" + i, size: 1, mime: "" }));
  assert.equal((await createShare(env, identity, { files: many })).status, 400);
  assert.equal((await createShare(env, identity, { files: [{ name: "a", size: 2 ** 31, mime: "" }] })).status, 400);
  assert.equal((await createShare(env, identity, { files: [{ name: "长".repeat(86), size: 1, mime: "" }] })).status, 400);
});

test("没有这个 token 回 404", async () => {
  const env = createTestEnv(workerModule);
  const got = await api(env, "/api/share/" + randomToken());
  assert.equal(got.status, 404);
  assert.equal(got.body.status, "not-found");
});

test("同一台设备第 21 条分享回 429", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  for (let i = 0; i < 20; i += 1) {
    const created = await createShare(env, identity);
    assert.equal(created.status, 201, "第 " + (i + 1) + " 条应当成功");
  }
  const over = await createShare(env, identity);
  assert.equal(over.status, 429);
  assert.equal(over.body.error, "too-many");
});

test("撤销：签名正确即生效，GET 立刻变 revoked，重复撤销幂等", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const created = await createShare(env, identity);
  const token = created.body.token;

  const first = await api(env, "/api/share/" + created.body.shareId, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, token)),
  });
  assert.equal(first.status, 200);
  assert.equal(first.body.ok, true);
  assert.equal(first.body.shareId, created.body.shareId);

  const got = await api(env, "/api/share/" + token);
  assert.equal(got.body.status, "revoked");

  const again = await api(env, "/api/share/" + token, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, token)),
  });
  assert.equal(again.status, 200);
  assert.equal(again.body.alreadyRevoked, true);
});

test("撤销也能按 token 路径走；签名不对/不是主人一律 403", async () => {
  const identity = await createIdentity();
  const attacker = await createIdentity();
  const env = createTestEnv(workerModule);
  const created = await createShare(env, identity);
  const token = created.body.token;

  const badSignature = await api(env, "/api/share/" + token, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, token, { sig: "00".repeat(64) })),
  });
  assert.equal(badSignature.status, 403);

  const otherDevice = await api(env, "/api/share/" + token, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(attacker, token)),
  });
  assert.equal(otherDevice.status, 403);

  const wrongToken = await api(env, "/api/share/" + token, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, randomToken())),
  });
  assert.equal(wrongToken.status, 400);

  const good = await api(env, "/api/share/" + token, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, token)),
  });
  assert.equal(good.status, 200);
  assert.equal(good.body.ok, true);
});

test("撤销请求的 ts 超窗回 400", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const created = await createShare(env, identity);
  const token = created.body.token;
  const staleTs = Math.floor(Date.now() / 1000) - 3600;
  const response = await api(env, "/api/share/" + created.body.shareId, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId: identity.deviceId, token, ts: staleTs, nonce: "nonce-0001", sig: "00".repeat(64) }),
  });
  assert.equal(response.status, 400);
});

test("过了有效期四态里的 expired（时间往前推 2 小时）", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const created = await createShare(env, identity, { ttlSec: 3600 });
  assert.equal(created.body.expiresAt - created.body.createdAt, 3600 * 1000);

  const realNow = Date.now;
  Date.now = () => realNow() + 2 * 3600 * 1000;
  try {
    const got = await api(env, "/api/share/" + created.body.token);
    assert.equal(got.body.status, "expired");
  } finally {
    Date.now = realNow;
  }
});

test("GET /api/shares 只列仍然有效的（撤销/过期都不出现）", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const keep = await createShare(env, identity);
  const drop = await createShare(env, identity);

  await api(env, "/api/share/" + drop.body.shareId, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(await revokeBody(identity, drop.body.token)),
  });

  const listed = await api(env, "/api/shares?deviceId=" + identity.deviceId);
  assert.equal(listed.status, 200);
  assert.equal(listed.body.shares.length, 1);
  assert.equal(listed.body.shares[0].shareId, keep.body.shareId);
  assert.equal(listed.body.shares[0].visitCount, 0);
});

test("OPTIONS 预检带 CORS 头", async () => {
  const env = createTestEnv(workerModule);
  const response = await workerModule.route(makeRequest("/api/share", { method: "OPTIONS" }), env, undefined);
  assert.equal(response.status, 204);
  assert.equal(response.headers.get("access-control-allow-origin"), "*");
});
