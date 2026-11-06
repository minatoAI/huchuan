#!/usr/bin/env node
/**
 * 链接分享端到端验收：daemon 当分享者，Edge headless 打开接收页，8 MiB 经真 Worker 的
 * /signal 建链、WebRTC DataChannel 直推，浏览器自己算 SHA-256 并在 verify 回执里带回来。
 *
 *   node daemon/e2e/link-share-e2e.mjs --port 47200 --file artifacts/.../payload-8m.bin \
 *        --out artifacts/link-share-win/e2e --log artifacts/.../daemon-A.log --tag win
 *
 * 流程（全部走 daemon 的公开 HTTP 口，不碰 core 内部）：
 *   1. POST /api/groups 建一个临时群 → POST .../files 共享文件（拿到 fileId）
 *   2. POST /api/shares {fileId} → 分享对象（link/token/shareId）
 *   3. 本地静态服务复刻 Pages 的 / 与 /r/* → recv/index.html 重写，页面从 127.0.0.1 载入
 *   4. Edge headless（CDP 驱动）打开 /#s=<token>&api=<Worker>：页面自己查分享、连 /signal、
 *      收字节、落盘、算 SHA、回 verify
 *   5. 断言：浏览器 phase=done、字节数、SHA 一致、verify 回执 ok；daemon 侧 status=done、
 *      visitor.sha256 一致、日志有 link_transfer_done bytes=… sha256=…
 *   6. 撤销分享（DELETE）并留证；退出码 0/1
 *
 * 退出码：0 = 通过；1 = 失败；2 = 参数/环境问题（没 Edge、连不上 daemon）。
 */
import { createHash } from "node:crypto";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import {
  createReadStream,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { dirname, extname, join, normalize, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, "../..");
const PAGES = join(ROOT, "pages/public");
const EDGE_CANDIDATES = [
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
];

function arg(name, fallback) {
  // 同时接受 --name value 与 --name=value（m1 里两种写法都出现过）
  const prefix = "--" + name + "=";
  const inline = process.argv.find((value) => value.startsWith(prefix));
  if (inline) return inline.slice(prefix.length);
  const i = process.argv.indexOf("--" + name);
  return i > 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback;
}
const PORT = Number(arg("port", "47200"));
const FILE = arg("file", "");
const OUT = resolve(arg("out", join(ROOT, "artifacts/link-share-win/e2e")));
const TAG = arg("tag", "win");
const API = (arg("api", "https://huchuan-a9f.pages.dev")).replace(/\/+$/, "");
const DAEMON_LOG = arg("log", "");
const KEEP = process.argv.includes("--keep");
const EXPIRE = process.argv.includes("--expire");
const BASE = "http://127.0.0.1:" + PORT;

rmSync(OUT, { recursive: true, force: true });
mkdirSync(OUT, { recursive: true });

const logLines = [];
function log(...parts) {
  const line = "[" + new Date().toISOString() + "] " + parts.join(" ");
  logLines.push(line);
  console.log(line);
}
function saveLog() {
  writeFileSync(join(OUT, "e2e.log"), logLines.join("\n") + "\n");
}
function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

/* ------------------------------------------------------------ HTTP */

async function apiJson(method, path, body) {
  const response = await fetch(BASE + path, {
    method,
    headers: body === undefined ? {} : { "content-type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await response.text();
  let json = null;
  try { json = JSON.parse(text); } catch { /* 非 JSON 原样留在 text 里 */ }
  return { status: response.status, json, text };
}

async function sha256File(path) {
  const hash = createHash("sha256");
  await new Promise((done, fail) => {
    createReadStream(path).on("data", (chunk) => hash.update(chunk)).on("end", done).on("error", fail);
  });
  return hash.digest("hex");
}

/* ------------------------------------------------------------ 静态服务（复刻 Pages 重写） */

const MIME = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
};

function startStatic() {
  const server = createServer((req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    let path = url.pathname;
    if (path === "/" || /^\/r\/[^/]*$/.test(path)) path = "/recv/index.html";
    const file = normalize(join(PAGES, path));
    if (!file.startsWith(PAGES) || !existsSync(file) || !statSync(file).isFile()) {
      res.writeHead(404, { "content-type": "text/plain" });
      res.end("not found");
      return;
    }
    res.writeHead(200, {
      "content-type": MIME[extname(file)] || "application/octet-stream",
      "cache-control": "no-store",
    });
    createReadStream(file).pipe(res);
  });
  return new Promise((done) => server.listen(0, "127.0.0.1", () => done(server)));
}

/* ------------------------------------------------------------ CDP */

async function fetchJson(url) {
  const reply = await fetch(url);
  return reply.json();
}

class Cdp {
  constructor(ws) {
    this.ws = ws;
    this.id = 0;
    this.pending = new Map();
    this.notes = [];
    this.hooks = [];
    ws.addEventListener("message", (event) => {
      const msg = JSON.parse(event.data);
      for (const hook of this.hooks) {
        try { hook(msg); } catch { /* 观察者不许炸 */ }
      }
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) reject(new Error(JSON.stringify(msg.error)));
        else resolve(msg.result);
        return;
      }
      if (msg.method === "Runtime.consoleAPICalled") {
        this.notes.push("console." + msg.params.type + ": " + msg.params.args.map((a) => a.value ?? a.description ?? "").join(" "));
      } else if (msg.method === "Runtime.exceptionThrown") {
        this.notes.push("exception: " + (msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text));
      } else if (msg.method === "Log.entryAdded") {
        this.notes.push("log." + msg.params.entry.level + ": " + msg.params.entry.text);
      }
    });
  }
  onMessage(hook) { this.hooks.push(hook); }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    const payload = { id, method, params };
    if (sessionId) payload.sessionId = sessionId;
    this.ws.send(JSON.stringify(payload));
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      setTimeout(() => {
        if (this.pending.has(id)) {
          this.pending.delete(id);
          reject(new Error("cdp timeout: " + method));
        }
      }, 60000);
    });
  }
}

async function connectCdp(port) {
  const version = await fetchJson("http://127.0.0.1:" + port + "/json/version");
  const ws = new WebSocket(version.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => {
    ws.addEventListener("open", resolve, { once: true });
    ws.addEventListener("error", reject, { once: true });
  });
  return new Cdp(ws);
}

async function launchBrowser(port, userDataDir) {
  const exe = EDGE_CANDIDATES.find((p) => existsSync(p));
  if (!exe) throw new Error("找不到 Edge/Chrome（试过：" + EDGE_CANDIDATES.join(", ") + "）");
  const child = spawn(exe, [
    "--headless=new",
    "--disable-gpu",
    "--no-proxy-server",
    "--disable-background-networking",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-features=msEdgeFirstRunExperience,Translate",
    "--user-data-dir=" + userDataDir,
    "--remote-debugging-port=" + port,
    "--window-size=900,700",
    "about:blank",
  ], { stdio: ["ignore", "pipe", "pipe"] });
  const chunks = [];
  child.stdout.on("data", (b) => chunks.push(String(b)));
  child.stderr.on("data", (b) => chunks.push(String(b)));
  for (let i = 0; i < 150; i += 1) {
    try {
      await fetchJson("http://127.0.0.1:" + port + "/json/version");
      return { child, chunks, exe };
    } catch {
      await sleep(200);
    }
  }
  throw new Error("浏览器没打开调试端口：" + chunks.join("").slice(0, 400));
}

/* ------------------------------------------------------------ 主流程 */

async function main() {
  const result = {
    ok: false,
    tag: TAG,
    at: new Date().toISOString(),
    daemon: BASE,
    api: API,
    file: FILE,
    checks: {},
    errors: [],
  };

  if (!FILE || !existsSync(FILE)) throw new Error("--file 必须指向一个存在的文件：" + FILE);
  const size = statSync(FILE).size;
  const sourceSha = await sha256File(FILE);
  log("file " + FILE + " size " + size + " sha256 " + sourceSha);
  result.source = { size, sha256: sourceSha };

  const health = await apiJson("GET", "/api/health");
  if (health.status !== 200) throw new Error("daemon /api/health 不通：" + health.status + " " + health.text.slice(0, 200));
  log("daemon " + health.text.slice(0, 160));

  const availability = await apiJson("GET", "/api/shares");
  const available = availability.json?.data?.available === true;
  result.checks.daemonAvailable = available;
  log("daemon link available=" + available + " reason=" + (availability.json?.data?.reason || ""));
  if (!available) throw new Error("daemon 的链接分享不可用：" + (availability.json?.data?.reason || "?"));

  // 1. 临时群 + 共享文件
  const group = await apiJson("POST", "/api/groups", { name: "link-e2e-" + TAG });
  if (group.status !== 201) throw new Error("建群失败：" + group.status + " " + group.text.slice(0, 200));
  const gid = group.json.data.group.id;
  const added = await apiJson("POST", "/api/groups/" + gid + "/files", { path: FILE });
  if (added.status !== 201) throw new Error("共享文件失败：" + added.status + " " + added.text.slice(0, 200));
  const fileId = added.json.data.file.id;
  log("group " + gid + " file " + fileId);

  // 2. 创建分享
  const created = await apiJson("POST", "/api/shares", EXPIRE ? { fileId, ttlSec: 60 } : { fileId });
  if (created.status !== 201) throw new Error("创建分享失败：" + created.status + " " + created.text.slice(0, 300));
  share = created.json.data.share;
  log("share " + share.shareId + " token " + String(share.token).slice(0, 4) + "… link " + share.link);
  result.share = {
    token: share.token,
    shareId: share.shareId,
    link: share.link,
    expiresAt: share.expiresAt,
  };

  if (EXPIRE) {
    // 只验过期：等到 expiresAt 之后看状态与日志，不开浏览器。
    const deadline = Date.now() + 90_000;
    let status = null;
    while (Date.now() < deadline) {
      const snapshot = await apiJson("GET", "/api/shares/" + share.token + "/status");
      status = snapshot.json?.data || null;
      if (status && status.status === "expired") break;
      await sleep(1000);
    }
    result.checks.expired = status?.status === "expired";
    result.checks.expirePhase = status?.phase;
    log("expire status=" + status?.status + " phase=" + status?.phase);
    result.ok = result.checks.expired === true;
    if (!KEEP) await apiJson("DELETE", "/api/shares/" + share.token);
    writeFileSync(join(OUT, "result.json"), JSON.stringify(result, null, 2));
    saveLog();
    log("RESULT " + (result.ok ? "PASS" : "FAIL") + " " + JSON.stringify(result.checks));
    process.exit(result.ok ? 0 : 1);
  }

  // 3. 静态页 + 浏览器
  const server = await startStatic();
  const pagePort = server.address().port;
  const pageBase = "http://127.0.0.1:" + pagePort;
  const debugPort = 9400 + Math.floor(Math.random() * 400);
  const userData = join(tmpdir(), "huchuan-link-edge-" + Date.now());
  mkdirSync(userData, { recursive: true });
  let browser = null;
  let cdp = null;
  let state = null;
  let share = null;
  try {
    browser = await launchBrowser(debugPort, userData);
    cdp = await connectCdp(debugPort);
    const targets = await cdp.send("Target.getTargets");
    const pageTarget = targets.targetInfos.find((t) => t.type === "page");
    const attached = await cdp.send("Target.attachToTarget", { targetId: pageTarget.targetId, flatten: true });
    const S = attached.sessionId;
    await cdp.send("Page.enable", {}, S);
    await cdp.send("Runtime.enable", {}, S);
    await cdp.send("Log.enable", {}, S);
    await cdp.send("Network.enable", {}, S);
    let wsCreated = 0;
    cdp.onMessage((msg) => { if (msg.method === "Network.webSocketCreated") wsCreated += 1; });

    // 与真实链接同形状：/#s=<token>；api 指到 Worker（页面默认用 location.origin，这里不是）
    const url = pageBase + "/#s=" + share.token + "&api=" + encodeURIComponent(API);
    log("open " + pageBase + "/#s=" + String(share.token).slice(0, 4) + "…&api=" + API);
    await cdp.send("Page.navigate", { url }, S);

    let progressShot = false;
    const deadline = Date.now() + 150_000;
    while (Date.now() < deadline) {
      await sleep(500);
      try {
        const reply = await cdp.send("Runtime.evaluate", {
          expression: "JSON.stringify(window.__huchuanRecv || null)",
          returnByValue: true,
        }, S);
        state = reply.result.value ? JSON.parse(reply.result.value) : null;
      } catch {
        state = null;
      }
      if (!state) continue;
      log("page " + state.phase + " · " + state.status + " · " + state.bytes + "/" + state.total + " · " + (state.saveMode || "-"));
      if (state.phase === "receiving" && !progressShot) {
        progressShot = true;
        const mid = await cdp.send("Page.captureScreenshot", { format: "png" }, S);
        writeFileSync(join(OUT, "view-progress.png"), Buffer.from(mid.data, "base64"));
      }
      if (["done", "fatal", "gated"].includes(state.phase)) break;
    }
    try {
      const shot = await cdp.send("Page.captureScreenshot", { format: "png" }, S);
      writeFileSync(join(OUT, "view-done.png"), Buffer.from(shot.data, "base64"));
    } catch { /* 截图失败不影响断言 */ }
    result.page = state;
    result.webSockets = wsCreated;
    writeFileSync(join(OUT, "browser.log"), (browser.chunks.join("") + "\n" + cdp.notes.join("\n")).slice(0, 200_000));

    // 4. daemon 侧状态
    const statusReply = await apiJson("GET", "/api/shares/" + share.token + "/status");
    const status = statusReply.json?.data || null;
    result.status = status;
    const listReply = await apiJson("GET", "/api/shares");
    result.list = listReply.json?.data || null;

    // 5. 日志证据
    let logLine = "";
    if (DAEMON_LOG && existsSync(DAEMON_LOG)) {
      const text = readFileSync(DAEMON_LOG, "utf8");
      // 只认真正的传输行（ws_push 的 event 字段里也有这串字，靠后面的 bytes= 区分）
      const line = text.split(/\r?\n/).filter((row) => row.includes("event=link_transfer_done bytes=")).pop() || "";
      const joinLine = text.split(/\r?\n/).filter((row) => row.includes("event=link_visitor_join")).pop() || "";
      logLine = line;
      result.daemonLog = { transferDone: line, visitorJoin: joinLine };
      writeFileSync(join(OUT, "daemon-link.log"), text.split(/\r?\n/).filter((row) => row.includes("link_")).join("\n") + "\n");
    }

    const pageDone = !!state && state.phase === "done";
    const bytesOk = !!state && state.bytes === size;
    const browserSha = state ? String(state.sha256 || "").toLowerCase() : "";
    const shaOk = browserSha === sourceSha;
    const matchOk = !!state && state.sha256Match === true;
    const statusOk = !!status && status.status === "done" && status.visitor && status.visitor.sha256 === sourceSha;
    const bytesSentOk = !!status && status.bytesSent === size;
    const listOk = !!result.list && Array.isArray(result.list.shares) &&
      result.list.shares.some((s) => s.token === share.token && s.status === "done" && s.transfers >= 1);
    const logOk = logLine.includes("event=link_transfer_done") && logLine.includes("bytes=" + size) &&
      logLine.toLowerCase().includes("sha256=" + sourceSha);

    result.checks = {
      daemonAvailable: available,
      pageDone,
      bytesOk,
      browserSha256: browserSha,
      sourceSha256: sourceSha,
      shaOk,
      sha256Match: matchOk,
      saveMode: state ? state.saveMode : null,
      fileBytes: state ? state.bytes : 0,
      expectedBytes: size,
      daemonStatus: status ? status.status : null,
      daemonVisitorSha: status?.visitor?.sha256 || null,
      statusOk,
      bytesSentOk,
      listDone: listOk,
      logLine,
      logOk,
      webSockets: wsCreated,
      timings: state ? state.timings : null,
      rateMiBPerSec: state ? state.rateMiBPerSec : null,
      errors: state ? state.errors : [],
    };
    result.ok = pageDone && bytesOk && shaOk && matchOk && statusOk && bytesSentOk && listOk && logOk;
    log("RESULT " + (result.ok ? "PASS" : "FAIL") + " " + JSON.stringify(result.checks));
  } catch (error) {
    result.errors.push(String(error && error.stack ? error.stack : error));
    log("FATAL " + result.errors[result.errors.length - 1]);
  } finally {
    try { if (cdp) cdp.ws.close(); } catch { /* 已关 */ }
    if (browser) {
      try { browser.child.kill(); } catch { /* 已退 */ }
      await sleep(500);
    }
    try { server.close(); } catch { /* 已关 */ }
    if (!KEEP && share) {
      const revoked = await apiJson("DELETE", "/api/shares/" + share.token).catch((e) => ({ status: 0, text: String(e) }));
      result.revoked = { status: revoked.status, body: revoked.text ? revoked.text.slice(0, 400) : "" };
      log("revoke share status=" + revoked.status);
    }
  }

  writeFileSync(join(OUT, "result.json"), JSON.stringify(result, null, 2));
  saveLog();
  process.exit(result.ok ? 0 : 1);
}

main().catch((error) => {
  log("FATAL " + (error && error.stack ? error.stack : error));
  saveLog();
  process.exit(2);
});
