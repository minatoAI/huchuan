import test from "node:test";
import assert from "node:assert/strict";

import * as workerModule from "../src/index.js";
import { createCtx, createTestEnv, makeRequest, settle, upgradeRequest } from "../test-kit/memory.js";
import { createIdentity } from "../test-kit/identity.js";
import { randomToken } from "../src/crypto.js";

const FILES = [{ name: "a.bin", size: 4096, mime: "application/octet-stream" }];

async function api(env, path, options = {}) {
  const response = await workerModule.route(makeRequest(path, options), env, createCtx());
  const text = await response.text();
  let body = null;
  try {
    body = text ? JSON.parse(text) : null;
  } catch {
    body = null;
  }
  return { status: response.status, body };
}

async function newShare(env, identity, ttlSec) {
  const token = randomToken();
  const created = await api(env, "/api/share", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ token, deviceId: identity.deviceId, pub: identity.pubHex, files: FILES, ttlSec }),
  });
  assert.equal(created.status, 201);
  return { token, shareId: created.body.shareId, expiresAt: created.body.expiresAt };
}

async function openSharer(env, ctx, shareId, identity, overrides = {}) {
  const ts = Math.floor(Date.now() / 1000) + (overrides.skewSec || 0);
  const nonce = overrides.nonce || "nonce-abcdefgh";
  const dev = overrides.dev || identity.deviceId;
  const signature = overrides.signature || (await identity.sign("huchuan/ws1/v1", [dev, nonce, ts]));
  const query = new URLSearchParams({ role: "sharer", share: shareId, dev, ts: String(ts), nonce, sig: signature });
  return upgradeRequest(workerModule, env, "/signal?" + query.toString(), ctx);
}

async function openGuest(env, ctx, shareId, ep) {
  const query = new URLSearchParams({ role: "guest", share: shareId, ep });
  return upgradeRequest(workerModule, env, "/signal?" + query.toString(), ctx);
}

async function joinRoom(guest, token, epPub = "epub") {
  guest.send(JSON.stringify({ t: "share-join", token, epPub }));
}

async function revoke(env, identity, shareId, token) {
  const ts = Math.floor(Date.now() / 1000);
  const nonce = "nonce-revoke";
  const sig = await identity.sign("huchuan/revoke1/v1", [identity.deviceId, token, ts, nonce]);
  return api(env, "/api/share/" + shareId, {
    method: "DELETE",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ deviceId: identity.deviceId, token, ts, nonce, sig }),
  });
}

test("分享者 + 访客配对：share-incoming、offer/answer/ice 原样转发", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharerResponse = await openSharer(env, ctx, shareId, identity);
  assert.equal(sharerResponse.status, 101);
  const sharer = sharerResponse.webSocket;

  const online = await api(env, "/api/share/" + token);
  assert.equal(online.body.status, "available", "分享者在信令里，四态是 available");

  const guestResponse = await openGuest(env, ctx, shareId, "guestpage1");
  assert.equal(guestResponse.status, 101);
  const guest = guestResponse.webSocket;

  await joinRoom(guest, token, "epub-1");
  const joined = await guest.nextJson();
  assert.equal(joined.t, "joined");
  assert.equal(joined.shareId, shareId);
  assert.equal(typeof joined.visitorId, "string");

  const incoming = await sharer.nextJson();
  assert.equal(incoming.t, "share-incoming");
  assert.equal(incoming.shareId, shareId);
  assert.equal(incoming.epPub, "epub-1");
  assert.equal(incoming.visitorId, joined.visitorId);

  sharer.send(JSON.stringify({ t: "offer", sdp: "v=0 fake offer", sig: "ab" }));
  const offer = await guest.nextJson();
  assert.equal(offer.t, "offer");
  assert.equal(offer.sdp, "v=0 fake offer");

  guest.send(JSON.stringify({ t: "answer", sdp: "v=0 fake answer" }));
  const answer = await sharer.nextJson();
  assert.equal(answer.t, "answer");
  assert.equal(answer.sdp, "v=0 fake answer");

  sharer.send(JSON.stringify({ t: "ice", candidate: "candidate-from-sharer" }));
  assert.equal((await guest.nextJson()).candidate, "candidate-from-sharer");

  guest.send(JSON.stringify({ t: "ice", candidate: "candidate-from-guest" }));
  assert.equal((await sharer.nextJson()).candidate, "candidate-from-guest");

  await ctx.drain();
  const visited = await api(env, "/api/share/" + token);
  assert.equal(visited.body.visitCount, 1, "访客握手记一次被访问");
});

test("房间最多 1 个访客：第二个当场 err busy 并被关掉", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharer = (await openSharer(env, ctx, shareId, identity)).webSocket;

  const firstGuest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(firstGuest, token);
  assert.equal((await firstGuest.nextJson()).t, "joined");
  assert.equal((await sharer.nextJson()).t, "share-incoming");

  const secondResponse = await openGuest(env, ctx, shareId, "guestpage2");
  assert.equal(secondResponse.status, 101);
  const secondGuest = secondResponse.webSocket;
  assert.deepEqual(await secondGuest.nextJson(), { t: "err", code: "busy" });
  const closed = await secondGuest.closed();
  assert.equal(closed.code, 4000);
  await settle(env);
});

test("并发两个访客也只放一个进来", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharer = (await openSharer(env, ctx, shareId, identity)).webSocket;

  const [first, second] = await Promise.all([
    openGuest(env, ctx, shareId, "guestpagea"),
    openGuest(env, ctx, shareId, "guestpageb"),
  ]);
  assert.equal(first.status, 101);
  assert.equal(second.status, 101);
  const sockets = [first.webSocket, second.webSocket];

  for (const socket of sockets) {
    try {
      await joinRoom(socket, token);
    } catch {
      /* 已被拒的那条会在发送时报错 */
    }
  }

  const results = await Promise.all(
    sockets.map((socket) =>
      socket.nextJson(500).catch((error) => ({ closed: String(error.message) })),
    ),
  );
  const joinedCount = results.filter((item) => item && item.t === "joined").length;
  const busyCount = results.filter((item) => item && item.t === "err" && item.code === "busy").length;
  assert.equal(joinedCount, 1, "只能有一个访客进房间");
  assert.equal(busyCount, 1, "另一个拿到 busy");

  await settle(env);
  const incomingCount = await countIncoming(sharer);
  assert.equal(incomingCount, 1);
});

test("撤销后：房间里的人收到 bye gone，新的连接直接 410", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const guest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(guest, token);
  await guest.nextJson();
  await sharer.nextJson();

  const revoked = await revoke(env, identity, shareId, token);
  assert.equal(revoked.status, 200);
  assert.equal(revoked.body.ok, true);

  const guestBye = await guest.nextJson();
  assert.deepEqual(guestBye, { t: "bye", code: "gone" });
  const sharerBye = await sharer.nextJson();
  assert.deepEqual(sharerBye, { t: "bye", code: "gone" });

  const rejected = await openGuest(env, ctx, shareId, "guestpage2");
  assert.equal(rejected.status, 410);
  const rejectedSharer = await openSharer(env, ctx, shareId, identity);
  assert.equal(rejectedSharer.status, 410);

  const status = await api(env, "/api/share/" + token);
  assert.equal(status.body.status, "revoked");
});

test("错误 token 的 share-join 被拒（err gone 并断开）", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { shareId } = await newShare(env, identity);
  await openSharer(env, ctx, shareId, identity);

  const guest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(guest, randomToken());
  assert.deepEqual(await guest.nextJson(), { t: "err", code: "gone" });
  assert.equal((await guest.closed()).code, 4005);
});

test("分享者不在线：访客收到 bye offline", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const guest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(guest, token);
  assert.deepEqual(await guest.nextJson(), { t: "bye", code: "offline" });
  assert.equal((await guest.closed()).code, 4002);
});

test("分享者签名/时钟/身份校验", async () => {
  const identity = await createIdentity();
  const attacker = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { shareId } = await newShare(env, identity);

  const badSig = await openSharer(env, ctx, shareId, identity, { signature: "00".repeat(64) });
  assert.equal(badSig.status, 403);

  const stale = await openSharer(env, ctx, shareId, identity, { skewSec: -3600 });
  assert.equal(stale.status, 400);

  const impostor = await openSharer(env, ctx, shareId, attacker);
  assert.equal(impostor.status, 403);

  const plain = await workerModule.route(makeRequest("/signal?role=guest&share=" + shareId + "&ep=guestpage1"), env, ctx);
  assert.equal(plain.status, 426, "没有 Upgrade 头就不是 WS 请求");
});

test("访客断开后房间能接下一个访客", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const firstGuest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(firstGuest, token);
  await firstGuest.nextJson();
  await sharer.nextJson();

  firstGuest.close();
  await settle(env);
  const left = await sharer.nextJson();
  assert.equal(left.t, "bye");
  assert.equal(left.code, "peer-left");

  const secondGuest = (await openGuest(env, ctx, shareId, "guestpage2")).webSocket;
  await joinRoom(secondGuest, token);
  assert.equal((await secondGuest.nextJson()).t, "joined");
});

test("撤销之后过期的分享，/signal 一律 410", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { shareId } = await newShare(env, identity, 60);

  const realNow = Date.now;
  Date.now = () => realNow() + 3600 * 1000;
  try {
    const rejected = await openGuest(env, ctx, shareId, "guestpage1");
    assert.equal(rejected.status, 410);
  } finally {
    Date.now = realNow;
  }
});

test("分享者断线重连：房间重放 share-incoming，访客不用重新 join", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const firstSharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const guest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(guest, token);
  const joined = await guest.nextJson();
  assert.equal(joined.t, "joined");
  const firstIncoming = await firstSharer.nextJson();
  assert.equal(firstIncoming.t, "share-incoming");

  firstSharer.close();
  await settle(env);
  const left = await guest.nextJson();
  assert.deepEqual(left, { t: "bye", code: "peer-left" }, "访客先收到 peer-left");

  // 分享者换一条 socket 回来：房间把还在等的访客重放给新的分享者
  const secondSharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const replay = await secondSharer.nextJson();
  assert.equal(replay.t, "share-incoming");
  assert.equal(replay.visitorId, joined.visitorId, "重放的是同一个访客");
  assert.equal(replay.resumed, true);

  // 访客仍在房间里，新 offer 直接可达，不需要再发 share-join
  secondSharer.send(JSON.stringify({ t: "offer", sdp: "v=0 resumed offer", sig: "ab" }));
  const offer = await guest.nextJson();
  assert.equal(offer.t, "offer");
  assert.equal(offer.sdp, "v=0 resumed offer");
});

test("同一 ep 的访客重连：接管旧 socket，不占「最多 1 个访客」的名额", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const sharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const firstGuest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(firstGuest, token);
  const firstJoined = await firstGuest.nextJson();
  await sharer.nextJson();

  // 同一个 ep 再连一次（浏览器断线重连），不能因为名额被自己的旧 socket 占着而拿到 busy
  const resumedResponse = await openGuest(env, ctx, shareId, "guestpage1");
  assert.equal(resumedResponse.status, 101);
  const resumedGuest = resumedResponse.webSocket;
  assert.equal((await firstGuest.closed()).code, 4006, "旧 socket 被接管");

  await joinRoom(resumedGuest, token);
  const resumedJoined = await resumedGuest.nextJson();
  assert.equal(resumedJoined.t, "joined");
  assert.equal(typeof resumedJoined.visitorId, "string");

  const incoming = await sharer.nextJson();
  assert.equal(incoming.t, "share-incoming");
  assert.equal(incoming.visitorId, resumedJoined.visitorId);
  assert.notEqual(resumedJoined.visitorId, firstJoined.visitorId);
});

test("分享者换连接后，旧 socket 关闭不再误发 peer-left", async () => {
  const identity = await createIdentity();
  const env = createTestEnv(workerModule);
  const ctx = createCtx();
  const { token, shareId } = await newShare(env, identity);

  const firstSharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  const guest = (await openGuest(env, ctx, shareId, "guestpage1")).webSocket;
  await joinRoom(guest, token);
  await guest.nextJson();
  await firstSharer.nextJson();

  const secondSharer = (await openSharer(env, ctx, shareId, identity)).webSocket;
  assert.equal((await secondSharer.nextJson()).t, "share-incoming");

  firstSharer.close();
  await settle(env);
  await assert.rejects(() => guest.next(300), /timeout/, "还有分享者在线时不该发 peer-left");
});

/** 数一数分享者在这条 socket 上收到了几个 share-incoming（不阻塞） */
async function countIncoming(socket) {
  let count = 0;
  for (;;) {
    try {
      const message = await socket.next(60);
      if (message && typeof message === "string" && JSON.parse(message).t === "share-incoming") count += 1;
    } catch {
      return count;
    }
  }
}
