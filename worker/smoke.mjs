#!/usr/bin/env node
/**
 * 线上冒烟：部署完之后在能访问 *.workers.dev 的机器上跑一次。
 *
 *   node worker/smoke.mjs https://huchuan-relay.<子域>.workers.dev
 *
 * 覆盖：/health、tracker 注册、两次 announce（能不能互相看见）、错 passkey、
 * 未注册 info_hash、分享 API 的 404。全过退出码 0，任何一条挂退出码 1。
 */

const base = (process.argv[2] || "").replace(/\/+$/, "");
if (!base) {
  console.error("用法: node worker/smoke.mjs https://<worker>.<子域>.workers.dev");
  process.exit(2);
}

let failures = 0;
function check(name, ok, detail) {
  console.log((ok ? "PASS " : "FAIL ") + name + (detail ? "  " + detail : ""));
  if (!ok) failures += 1;
}

function percentEncode(bytes) {
  let out = "";
  for (const byte of bytes) out += "%" + byte.toString(16).padStart(2, "0");
  return out;
}

function randomHex(byteLength) {
  const bytes = crypto.getRandomValues(new Uint8Array(byteLength));
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

async function main() {
  const health = await fetch(base + "/health").then((r) => r.json());
  check("/health ok", health.ok === true, JSON.stringify(health.bindings || {}));

  const infoHashBytes = crypto.getRandomValues(new Uint8Array(20));
  const infoHash = [...infoHashBytes].map((b) => b.toString(16).padStart(2, "0")).join("");
  const passkey = randomHex(32);
  const wrongPasskey = randomHex(32);

  const registered = await fetch(base + "/api/tracker/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ infoHash, gid: "7Q2F9C4M1XZ8KD3V", passkey }),
  }).then((r) => r.json());
  check("注册群", registered.ok === true, JSON.stringify(registered));

  const query = (peerId, port, extra = {}) => {
    const params = new URLSearchParams({ peer_id: peerId, port: String(port), left: "0", compact: "1", ...extra });
    return base + "/announce?info_hash=" + percentEncode(infoHashBytes) + "&" + params.toString() + "&passkey=" + passkey;
  };

  const first = await fetch(query("smoke-peer-000000001", 47100)).then((r) => r.text());
  check("第一次 announce 拿到 interval 600", first.includes("8:intervali600e"), first.slice(0, 80));
  check("min interval 300", first.includes("12:min intervali300e"));

  const second = await fetch(query("smoke-peer-000000002", 47101)).then((r) => r.text());
  check("第二次 announce 能看到第一个 peer", /5:peers6:/.test(second), second.slice(0, 120));

  const wrong = await fetch(query("smoke-peer-000000003", 47102, {}) .replace(passkey, wrongPasskey)).then((r) => r.text());
  check("错 passkey 被拒", wrong.includes("bad passkey"), wrong.slice(0, 80));

  const unregistered = await fetch(
    base + "/announce?info_hash=" + percentEncode(crypto.getRandomValues(new Uint8Array(20))) + "&port=47103&left=0&compact=1&passkey=" + passkey,
  ).then((r) => r.text());
  check("未注册 info_hash 被拒", unregistered.includes("info hash not registered"), unregistered.slice(0, 80));

  const unknownShare = await fetch(base + "/api/share/0123456789ABCDEFGHJKMNPQRS");
  check("未知分享 token 回 404", unknownShare.status === 404);

  const signal = await fetch(base + "/signal?role=guest&share=00000000000000000000000000000000&ep=guestpage1");
  check("非 WS 请求 /signal 回 426", signal.status === 426);

  console.log(failures === 0 ? "\n全部通过" : "\n" + failures + " 条失败");
  process.exit(failures === 0 ? 0 : 1);
}

main().catch((error) => {
  console.error("冒烟中断:", error);
  process.exit(1);
});
