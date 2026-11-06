"use strict";
/*
 * 互传 · 浏览器接收页（W1，link-share.md §3 / §5）。
 *
 * 一条页面一件事：打开链接 → 自动建链 → 自动边收边存 → 显示「已保存」或一句失败原因。
 * 令牌只在 fragment（#s=）里，不发往服务器；token 换状态走 GET /api/share/{token}。
 * 字节只走 WebRTC DataChannel，服务器不经手。
 */
(function () {
  /* ------------------------------------------------------------------ 0. 配置 */

  var query = new URLSearchParams(location.search);
  var hash = new URLSearchParams(location.hash.replace(/^#/, ""));
  var ORIGIN = (query.get("api") || hash.get("api") || location.origin).replace(/\/+$/, "");
  var WS_BASE = ORIGIN.replace(/^https:/i, "wss:").replace(/^http:/i, "ws:");
  var STUN = query.get("stun") || "stun:stun.cloudflare.com:3478";

  var OFFER_TIMEOUT_MS = 20000;   // 等待分享者 offer
  var CHANNEL_TIMEOUT_MS = 25000; // offer 发出到通道打开
  var OFFLINE_RETRY_MS = [10000, 20000, 40000, 60000];
  var WIRE_RETRY_MS = [2000, 4000, 8000, 16000, 30000];
  var COALESCE_BYTES = 256 * 1024;
  var OPFS_MAX_BYTES = 1 << 30;                    // link-share.md §12-4：单文件 1 GiB
  var MEMORY_MAX_BYTES = 128 * 1024 * 1024;        // 降级上限，实测见 docs/link-share-web.md §3

  var el = {
    name: document.getElementById("name"),
    status: document.getElementById("status"),
    bar: document.getElementById("bar"),
    track: document.querySelector(".track"),
    retry: document.getElementById("retry")
  };

  var st = {
    token: hash.get("s") || "",
    shareId: "",
    info: null,
    ep: "",
    files: [],
    fileIndex: 0,
    totalSize: 0,
    totalBytes: 0,
    bytesThisFile: 0,
    nextIndex: 0,
    sink: null,
    hash: null,
    coalesce: null,
    coalesceLen: 0,
    pipe: Promise.resolve(),
    ws: null,
    pc: null,
    channel: null,
    channelOpen: false,
    offerSeen: false,
    offerTimer: 0,
    channelTimer: 0,
    retryTimer: 0,
    offlineTries: 0,
    wireTries: 0,
    phase: "boot",
    statusText: "正在连接",
    errors: [],
    startedAt: nowMs(),
    wsOpenAt: 0,
    offerAt: 0,
    answerAt: 0,
    channelAt: 0,
    metaAt: 0,
    firstByteAt: 0,
    lastByteAt: 0,
    doneAt: 0,
    speedWindow: []
  };

  function nowMs() { return Date.now(); }
  function round(n) { return Math.round(n * 1000) / 1000; }

  /* ------------------------------------------------------------------ 1. 页面 */

  var diag = window.__huchuanRecv = {
    phase: "boot",
    status: "正在连接",
    saveMode: null,
    shareId: null,
    tokenPrefix: null,
    fileName: null,
    bytes: 0,
    total: 0,
    sha256: null,
    expectedSha256: null,
    sha256Match: null,
    timings: {},
    memory: {},
    errors: []
  };

  function setStatus(text, phase) {
    st.statusText = text;
    el.status.textContent = text;
    diag.status = text;
    if (phase) { st.phase = phase; diag.phase = phase; }
    diag.timings = timings();
    diag.errors = st.errors.slice();
    diag.memory = memory();
  }

  function fail(message) {
    if (st.phase === "done" || st.phase === "fatal" || st.phase === "gated") return;
    st.errors.push(message);
    stopPeer();
    closeWire();
    clearTimers();
    el.track.dataset.on = "0";
    el.retry.hidden = false;
    setStatus(message, "fatal");
  }

  /** 过期 / 撤销 / 不存在：不建链、不给重试。 */
  function gated() {
    if (st.phase === "done") return;
    stopPeer();
    closeWire();
    clearTimers();
    el.track.dataset.on = "0";
    el.retry.hidden = true;
    if (st.info && st.info.files && st.info.files.length) renderName(0);
    setStatus("链接已失效", "gated");
  }

  /** 裸域名访问：没有链接可收，别显示「失效」吓人。 */
  function bareVisit() {
    stopPeer();
    closeWire();
    clearTimers();
    el.track.dataset.on = "0";
    el.retry.hidden = true;
    el.name.textContent = "";
    setStatus("没有分享链接", "gated");
  }

  function clearTimers() {
    clearTimeout(st.offerTimer);
    clearTimeout(st.channelTimer);
    clearTimeout(st.retryTimer);
    st.offerTimer = 0;
    st.channelTimer = 0;
    st.retryTimer = 0;
  }

  function timings() {
    return {
      startedAt: new Date(st.startedAt).toISOString(),
      connectMs: st.channelAt ? st.channelAt - st.startedAt : 0,
      transferMs: st.firstByteAt && st.lastByteAt ? st.lastByteAt - st.firstByteAt : 0,
      totalMs: (st.doneAt || nowMs()) - st.startedAt
    };
  }

  function memory() {
    if (!performance.memory) return {};
    return {
      usedJSHeapSize: performance.memory.usedJSHeapSize,
      peakUsedJSHeapSize: Math.max(diag.memory.peakUsedJSHeapSize || 0, performance.memory.usedJSHeapSize)
    };
  }

  function fmtBytes(n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(1) + " KiB";
    if (n < 1073741824) return (n / 1048576).toFixed(2) + " MiB";
    return (n / 1073741824).toFixed(2) + " GiB";
  }

  function renderName(index) {
    var entry = st.files[index] || (st.info && st.info.files ? st.info.files[index] : null);
    if (!entry) return;
    var prefix = st.files.length > 1 ? (index + 1) + "/" + st.files.length + " " : "";
    el.name.textContent = prefix + entry.name + " · " + fmtBytes(Number(entry.size) || 0);
    diag.fileName = entry.name;
  }

  function renderProgress() {
    var total = st.totalSize || 0;
    var pct = total ? Math.min(100, st.totalBytes * 100 / total) : 0;
    el.track.dataset.on = "1";
    el.bar.style.width = pct.toFixed(2) + "%";
    var t = nowMs();
    st.speedWindow.push([t, st.totalBytes]);
    while (st.speedWindow.length > 2 && t - st.speedWindow[0][0] > 2000) st.speedWindow.shift();
    var first = st.speedWindow[0];
    var rate = 0;
    if (first && t > first[0] + 200) rate = (st.totalBytes - first[1]) * 1000 / (t - first[0]);
    setStatus(pct.toFixed(1) + "%" + (rate > 0 ? " · " + (rate / 1048576).toFixed(1) + " MiB/s" : ""), "receiving");
    diag.bytes = st.totalBytes;
    diag.total = total;
    diag.rateMiBPerSec = round(rate / 1048576);
  }

  function memoryTick() {
    if (performance.memory) {
      var used = performance.memory.usedJSHeapSize;
      diag.memory.peakUsedJSHeapSize = Math.max(diag.memory.peakUsedJSHeapSize || 0, used);
    }
  }

  /* ------------------------------------------------------------------ 2. 增量 SHA-256 */

  function createSha256() {
    var K = [
      0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
      0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
      0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
      0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
      0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
      0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
      0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
      0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    ];
    var H = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
    var W = new Uint32Array(64);
    var block = new Uint8Array(64);
    var blockLen = 0;
    var total = 0;
    var sealed = false;

    function compress(bytes, off) {
      var i, j, k;
      for (i = 0; i < 16; i++) {
        W[i] = (bytes[off + i * 4] << 24) | (bytes[off + i * 4 + 1] << 16) | (bytes[off + i * 4 + 2] << 8) | bytes[off + i * 4 + 3];
      }
      for (j = 16; j < 64; j++) {
        var w15 = W[j - 15];
        var w2 = W[j - 2];
        var s0 = ((w15 >>> 7) | (w15 << 25)) ^ ((w15 >>> 18) | (w15 << 14)) ^ (w15 >>> 3);
        var s1 = ((w2 >>> 17) | (w2 << 15)) ^ ((w2 >>> 19) | (w2 << 13)) ^ (w2 >>> 10);
        W[j] = (W[j - 16] + s0 + W[j - 7] + s1) | 0;
      }
      var a = H[0], b = H[1], c = H[2], d = H[3], e = H[4], f = H[5], g = H[6], h = H[7];
      for (k = 0; k < 64; k++) {
        var S1 = ((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7));
        var ch = (e & f) ^ (~e & g);
        var t1 = (h + S1 + ch + K[k] + W[k]) | 0;
        var S0 = ((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10));
        var maj = (a & b) ^ (a & c) ^ (b & c);
        var t2 = (S0 + maj) | 0;
        h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
      }
      H[0] = (H[0] + a) | 0; H[1] = (H[1] + b) | 0; H[2] = (H[2] + c) | 0; H[3] = (H[3] + d) | 0;
      H[4] = (H[4] + e) | 0; H[5] = (H[5] + f) | 0; H[6] = (H[6] + g) | 0; H[7] = (H[7] + h) | 0;
    }

    return {
      update: function (data) {
        if (sealed) throw new Error("sha256: sealed");
        total += data.length;
        var off = 0;
        if (blockLen > 0) {
          var take = Math.min(64 - blockLen, data.length);
          block.set(data.subarray(0, take), blockLen);
          blockLen += take;
          off = take;
          if (blockLen === 64) { compress(block, 0); blockLen = 0; }
        }
        while (off + 64 <= data.length) { compress(data, off); off += 64; }
        if (off < data.length) { block.set(data.subarray(off), 0); blockLen = data.length - off; }
      },
      hex: function () {
        if (sealed) throw new Error("sha256: sealed");
        sealed = true;
        var bitsHi = Math.floor(total / 0x20000000);
        var bitsLo = (total % 0x20000000) * 8;
        var padLen = blockLen < 56 ? 64 : 128;
        var pad = new Uint8Array(padLen);
        pad.set(block.subarray(0, blockLen), 0);
        pad[blockLen] = 0x80;
        pad[padLen - 8] = (bitsHi >>> 24) & 255;
        pad[padLen - 7] = (bitsHi >>> 16) & 255;
        pad[padLen - 6] = (bitsHi >>> 8) & 255;
        pad[padLen - 5] = bitsHi & 255;
        pad[padLen - 4] = (bitsLo >>> 24) & 255;
        pad[padLen - 3] = (bitsLo >>> 16) & 255;
        pad[padLen - 2] = (bitsLo >>> 8) & 255;
        pad[padLen - 1] = bitsLo & 255;
        compress(pad, 0);
        if (padLen === 128) compress(pad, 64);
        var out = "";
        for (var i = 0; i < 8; i++) out += (H[i] >>> 0).toString(16).padStart(8, "0");
        return out;
      }
    };
  }

  /* ------------------------------------------------------------------ 3. 落盘（sink） */

  var OPFS_DIR = "huchuan-recv";

  function memorySink() {
    var chunks = [];
    return {
      mode: "memory",
      write: function (bytes) { chunks.push(bytes); return Promise.resolve(); },
      close: function () {
        var blob = new Blob(chunks, { type: "application/octet-stream" });
        chunks = [];
        return { blob: blob };
      },
      abort: function () { chunks = []; }
    };
  }

  async function pickerSink(name) {
    var handle = await window.showSaveFilePicker({ suggestedName: name });
    var stream = await handle.createWritable();
    var closed = false;
    return {
      mode: "picker",
      write: function (bytes) { return stream.write(bytes); },
      close: async function () { await stream.close(); closed = true; return {}; },
      abort: async function () { try { if (!closed) await stream.abort(); } catch (_) { /* 已关 */ } }
    };
  }

  async function opfsSink() {
    if (!navigator.storage || typeof navigator.storage.getDirectory !== "function") return null;
    if (typeof FileSystemFileHandle !== "function" ||
        typeof FileSystemFileHandle.prototype.createWritable !== "function") return null;
    var root = await navigator.storage.getDirectory();
    var dir = await root.getDirectoryHandle(OPFS_DIR, { create: true });
    var tmpName = "recv-" + nowMs() + "-" + Math.random().toString(16).slice(2) + ".part";
    var handle = await dir.getFileHandle(tmpName, { create: true });
    var stream = await handle.createWritable();
    var closed = false;
    return {
      mode: "opfs",
      write: function (bytes) { return stream.write(bytes); },
      close: async function () {
        await stream.close();
        closed = true;
        var file = await handle.getFile();
        return { blob: file, cleanup: function () { setTimeout(function () { dir.removeEntry(tmpName).catch(function () {}); }, 300000); } };
      },
      abort: async function () {
        try { if (!closed) await stream.abort(); } catch (_) { /* 已关 */ }
        try { await dir.removeEntry(tmpName); } catch (_) { /* 已删 */ }
      }
    };
  }

  /** 启动时清掉上一次的残片，避免占满配额。 */
  async function cleanOpfs() {
    try {
      if (!navigator.storage || typeof navigator.storage.getDirectory !== "function") return;
      var root = await navigator.storage.getDirectory();
      var dir = await root.getDirectoryHandle(OPFS_DIR, { create: true });
      if (typeof dir.keys !== "function") return;
      for await (var name of dir.keys()) { try { await dir.removeEntry(name); } catch (_) { /* 跳过 */ } }
    } catch (_) { /* 没有 OPFS 就算了 */ }
  }

  async function fitOpfs(size) {
    try {
      var est = await navigator.storage.estimate();
      if (!est || !est.quota) return true;
      return size <= Math.floor(est.quota * 0.8);
    } catch (_) {
      return true;
    }
  }

  function hasActivation() {
    return !!(navigator.userActivation && navigator.userActivation.isActive);
  }

  async function makeSink(file) {
    var forced = (query.get("save") || hash.get("save") || "").toLowerCase();
    var size = Number(file.size) || 0;
    if (size > OPFS_MAX_BYTES) {
      throw new Error("这个文件超过 1 GiB，浏览器接不了，请用 App 接收");
    }
    if (forced !== "memory") {
      if (forced !== "opfs" && typeof window.showSaveFilePicker === "function" && hasActivation()) {
        try {
          return await pickerSink(file.name);
        } catch (error) {
          if (error && error.name === "AbortError") throw new Error("已取消保存");
        }
      }
      if (await fitOpfs(size)) {
        var opfs = null;
        try { opfs = await opfsSink(); } catch (_) { opfs = null; }
        if (opfs) return opfs;
      }
    }
    if (size > MEMORY_MAX_BYTES) {
      throw new Error("这个浏览器不能边收边存，超过 " + Math.round(MEMORY_MAX_BYTES / 1048576) + " MiB 请用 App 接收");
    }
    return memorySink();
  }

  function triggerDownload(blob, name) {
    var url = URL.createObjectURL(blob);
    var a = document.createElement("a");
    a.href = url;
    a.download = name;
    a.rel = "noopener";
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(function () { URL.revokeObjectURL(url); }, 120000);
  }

  /* ------------------------------------------------------------------ 4. 收帧 */

  function openSinkFor(file) {
    return makeSink(file).then(function (sink) {
      st.sink = sink;
      st.hash = createSha256();
      st.bytesThisFile = 0;
      st.nextIndex = 0;
      st.coalesce = new Uint8Array(COALESCE_BYTES);
      st.coalesceLen = 0;
      st.fileStartAt = nowMs();
      diag.saveMode = sink.mode;
    });
  }

  function flushCoalesce() {
    if (!st.coalesceLen) return Promise.resolve();
    var out = st.coalesce.slice(0, st.coalesceLen);
    st.coalesceLen = 0;
    return st.sink.write(out);
  }

  function appendBytes(view) {
    st.coalesce.set(view, st.coalesceLen);
    st.coalesceLen += view.length;
    if (st.coalesceLen < COALESCE_BYTES) return Promise.resolve();
    return flushCoalesce();
  }

  function onMeta(msg) {
    var files = Array.isArray(msg.files) ? msg.files : [];
    if (!files.length) { fail("清单为空"); return; }
    st.files = files;
    st.fileIndex = 0;
    st.totalSize = files.reduce(function (sum, item) { return sum + (Number(item.size) || 0); }, 0);
    st.metaAt = nowMs();
    renderName(0);
    setStatus("正在接收", "receiving");
    return openSinkFor(files[0]);
  }

  async function finishFile(ok) {
    var file = st.files[st.fileIndex] || {};
    await flushCoalesce();
    var sha = st.hash.hex();
    var sink = st.sink;
    st.sink = null;
    var kept = {};
    if (sink) kept = await sink.close();
    var expected = String(file.sha256 || "").toLowerCase();
    var match = expected ? sha === expected : null;
    if (!expected) st.errors.push("清单没有 sha256，只校验了字节数");
    diag.sha256 = sha;
    diag.expectedSha256 = expected || null;
    diag.sha256Match = match;
    diag.files = (diag.files || []).concat([{ index: st.fileIndex, name: file.name, size: Number(file.size) || 0, bytes: st.bytesThisFile, sha256: sha, sha256Match: match }]);
    if (kept.blob && ok) triggerDownload(kept.blob, String(file.name || "file"));
    if (kept.cleanup) kept.cleanup();
    sendControl({
      t: "verify",
      index: st.fileIndex,
      ok: ok && match !== false,
      sha256: sha,
      bytes: st.bytesThisFile,
      chunks: st.nextIndex,
      transferMs: st.lastByteAt && st.firstByteAt ? st.lastByteAt - st.firstByteAt : 0
    });
    return { sha: sha, match: match };
  }

  async function onChunk(buffer) {
    if (st.phase === "fatal" || st.phase === "gated") return;
    var view = new DataView(buffer);
    var index = view.getUint32(0);
    var payload = new Uint8Array(buffer, 4);
    var file = st.files[st.fileIndex];
    if (!st.sink) {
      st.errors.push("没收到清单就来了数据");
      return;
    }
    if (index === 0 && st.bytesThisFile > 0) {
      if (st.fileIndex + 1 < st.files.length) {
        await finishFile(st.bytesThisFile === Number(file.size));
        st.fileIndex += 1;
        st.totalBytes = st.files.slice(0, st.fileIndex).reduce(function (s, f) { return s + (Number(f.size) || 0); }, 0);
        renderName(st.fileIndex);
        await openSinkFor(st.files[st.fileIndex]);
      } else {
        st.errors.push("超出清单的数据块，忽略");
        return;
      }
    } else if (index !== st.nextIndex) {
      st.errors.push("块序号不连续: " + index + " != " + st.nextIndex);
    }
    st.nextIndex = index + 1;
    st.hash.update(payload);
    st.bytesThisFile += payload.length;
    st.totalBytes += payload.length;
    if (!st.firstByteAt) st.firstByteAt = nowMs();
    st.lastByteAt = nowMs();
    await appendBytes(payload);
    if (st.totalBytes % (256 * 1024) < payload.length) sendControl({ t: "ack", upTo: st.totalBytes });
    renderProgress();
    memoryTick();
  }

  function onDone() {
    if (!st.sink) return;
    var file = st.files[st.fileIndex] || {};
    var complete = st.bytesThisFile === (Number(file.size) || 0);
    st.pipe = st.pipe.then(function () { return finishFile(complete); }).then(function () {
      st.doneAt = nowMs();
      diag.timings = timings();
      diag.bytes = st.totalBytes;
      if (!complete) { fail("数据不完整，请重试"); return; }
      el.bar.style.width = "100%";
      setStatus("已保存", "done");
    }).catch(function (error) { fail(sentence(error)); });
  }

  function sentence(error) {
    var message = error && error.message ? String(error.message) : String(error);
    if (/NotAllowedError/.test(message)) return "浏览器不允许保存，请重试";
    if (/QuotaExceeded/.test(message)) return "浏览器存储空间不足，请重试";
    return message;
  }

  function noteError(message) {
    if (st.errors.indexOf(message) < 0) st.errors.push(message);
  }

  function sendControl(object) {
    try { if (st.channel && st.channel.readyState === "open") st.channel.send(JSON.stringify(object)); } catch (_) { /* 通道已断 */ }
  }

  function setupChannel(channel) {
    st.channel = channel;
    st.channelAt = nowMs();
    channel.binaryType = "arraybuffer";
    channel.onopen = function () {
      if (st.channel !== channel) return;
      st.channelOpen = true;
      clearTimeout(st.channelTimer);
      setStatus("正在接收", "receiving");
    };
    channel.onmessage = function (event) {
      if (st.channel !== channel && st.phase !== "done") return;
      if (typeof event.data === "string") {
        var msg = null;
        try { msg = JSON.parse(event.data); } catch (_) { return; }
        if (!msg) return;
        if (msg.t === "meta") { st.pipe = st.pipe.then(function () { return onMeta(msg); }).catch(function (e) { fail(sentence(e)); }); return; }
        if (msg.t === "done") { onDone(); return; }
        return;
      }
      st.pipe = st.pipe.then(function () { return onChunk(event.data); }).catch(function (error) { fail(sentence(error)); });
    };
    channel.onclose = function () {
      if (st.channel !== channel) return;
      st.channelOpen = false;
      if (st.phase === "receiving" || st.phase === "connecting") {
        if (st.sink) { try { st.sink.abort(); } catch (_) { /* 已关 */ } st.sink = null; }
        fail("连接已断开，请重试");
      }
    };
  }

  /* ------------------------------------------------------------------ 5. 验签 */

  var SPKI_P256_PREFIX = hexToBytes("3059301306072a8648ce3d020106082a8648ce3d03010703420004");
  var B32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

  function utf8(text) { return new TextEncoder().encode(text); }

  function concat(parts) {
    var total = 0;
    var i;
    for (i = 0; i < parts.length; i++) total += parts[i].length;
    var out = new Uint8Array(total);
    var offset = 0;
    for (i = 0; i < parts.length; i++) { out.set(parts[i], offset); offset += parts[i].length; }
    return out;
  }

  function hexToBytes(hex) {
    var out = new Uint8Array(hex.length / 2);
    for (var i = 0; i < out.length; i++) out[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
    return out;
  }

  function bytesToHex(bytes) {
    var out = "";
    for (var i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, "0");
    return out;
  }

  function b32Encode(bytes) {
    var bits = 0;
    var value = 0;
    var out = "";
    for (var i = 0; i < bytes.length; i++) {
      value = (value << 8) | bytes[i];
      bits += 8;
      while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5; }
    }
    if (bits > 0) out += B32[(value << (5 - bits)) & 31];
    return out;
  }

  function fieldBytes(field) {
    if (typeof field === "string") return utf8(field);
    if (field instanceof Uint8Array) return field;
    if (typeof field === "number") {
      var out = new Uint8Array(8);
      var value = BigInt(field);
      for (var i = 7; i >= 0; i--) { out[i] = Number(value & 255n); value >>= 8n; }
      return out;
    }
    throw new Error("bad signBytes field");
  }

  function signBytes(domain, fields) {
    var parts = [utf8(domain), new Uint8Array([0])];
    for (var i = 0; i < fields.length; i++) {
      var bytes = fieldBytes(fields[i]);
      parts.push(new Uint8Array([bytes.length >> 8, bytes.length & 255]));
      parts.push(bytes);
    }
    return concat(parts);
  }

  function fingerprint(sdp) {
    var lines = String(sdp).split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      if (lines[i].indexOf("a=fingerprint:") === 0) return lines[i].slice("a=fingerprint:".length).trim();
    }
    return null;
  }

  async function verifyOffer(sdp, sigHex) {
    var info = st.info;
    if (!info || !info.pub) throw new Error("分享记录没有公钥，不建链");
    if (!sigHex || !/^[0-9a-fA-F]{128}$/.test(sigHex)) throw new Error("offer 没有签名，不建链");
    var fp = fingerprint(sdp);
    if (!fp) throw new Error("offer 没有 DTLS 指纹，不建链");

    var pub = hexToBytes(String(info.pub).toLowerCase());
    var derived = b32Encode(new Uint8Array(await crypto.subtle.digest(
      "SHA-256", concat([utf8("huchuan/dev1/v1"), pub])
    )).subarray(0, 10));
    if (String(info.deviceId || "").toUpperCase() !== derived) throw new Error("记录里的公钥与设备不匹配，不建链");

    var message = signBytes("huchuan/share1/v1", [st.token, fp, Number(info.expiresAt)]);
    var key = await crypto.subtle.importKey("spki", concat([SPKI_P256_PREFIX, pub]), { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    var ok = await crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, hexToBytes(sigHex), message);
    if (!ok) throw new Error("offer 签名校验失败，不建链");
    return fp;
  }

  /* ------------------------------------------------------------------ 6. 信令 */

  function randomEp() {
    var bytes = new Uint8Array(10);
    crypto.getRandomValues(bytes);
    return b32Encode(bytes).slice(0, 16);
  }

  function stopPeer() {
    var channel = st.channel;
    var pc = st.pc;
    st.channel = null;
    st.pc = null;
    st.channelOpen = false;
    clearTimeout(st.channelTimer);
    try { if (channel) channel.close(); } catch (_) { /* 已关 */ }
    try { if (pc) pc.close(); } catch (_) { /* 已关 */ }
  }

  function closeWire() {
    var ws = st.ws;
    st.ws = null;
    if (!ws) return;
    ws.onopen = null;
    ws.onmessage = null;
    ws.onclose = null;
    ws.onerror = null;
    try { ws.close(4009, "client-close"); } catch (_) { /* 已关 */ }
  }

  function resetTransfer() {
    if (st.sink) { try { st.sink.abort(); } catch (_) { /* 已关 */ } }
    st.sink = null;
    st.hash = null;
    st.coalesceLen = 0;
    st.bytesThisFile = 0;
    st.totalBytes = 0;
    st.nextIndex = 0;
    st.fileIndex = 0;
  }

  function wire() {
    closeWire();
    var url = WS_BASE + "/signal?role=guest&share=" + encodeURIComponent(st.shareId) + "&ep=" + st.ep;
    var ws;
    try { ws = new WebSocket(url); } catch (error) { fail("信令连不上"); return; }
    st.ws = ws;
    ws.onopen = function () {
      st.wsOpenAt = nowMs();
      ws.send(JSON.stringify({ t: "share-join", token: st.token }));
      if (!st.offerSeen) {
        clearTimeout(st.offerTimer);
        st.offerTimer = setTimeout(function () {
          if (!st.offerSeen) fail("无法直连");
        }, OFFER_TIMEOUT_MS);
      }
    };
    ws.onmessage = function (event) {
      var msg = null;
      try { msg = JSON.parse(String(event.data)); } catch (_) { return; }
      if (msg) onSignal(msg);
    };
    ws.onclose = function (event) { onWireClose(event.code); };
    ws.onerror = function () { st.errors.push("websocket error"); };
  }

  function onWireClose(code) {
    if (st.phase === "done" || st.phase === "fatal" || st.phase === "gated") return;
    if (st.channelOpen) return;            // 通道已开：信令断了不影响字节
    if (code === 4001) { gated(); return; }  // 撤销
    if (code === 4006) return;               // 同 ep 的另一个页面接管了
    if (code === 4000) { fail("对方正忙"); return; }
    if (code === 4002) {                    // 分享者不在线：留着页等，定时重连
      st.phase = "offline";
      setStatus("对方暂时离线", "offline");
      scheduleRejoin(OFFLINE_RETRY_MS);
      return;
    }
    st.wireTries += 1;
    if (st.wireTries > WIRE_RETRY_MS.length) { fail("无法直连"); return; }
    setStatus("正在连接", "connecting");
    st.retryTimer = setTimeout(wire, WIRE_RETRY_MS[st.wireTries - 1]);
  }

  function scheduleRejoin(table) {
    clearTimeout(st.retryTimer);
    var delay = table[Math.min(st.offlineTries, table.length - 1)];
    st.offlineTries += 1;
    st.retryTimer = setTimeout(function () {
      if (st.phase === "done" || st.phase === "fatal" || st.phase === "gated") return;
      wire();
    }, delay);
  }

  function onSignal(msg) {
    if (msg.t === "joined") {
      st.offlineTries = 0;
      setStatus("正在连接", "connecting");
      return;
    }
    if (msg.t === "offer") { handleOffer(msg); return; }
    if (msg.t === "ice") {
      // 非 trickle：候选本来就在 SDP 里。散帧没有 sdpMid/sdpMLineIndex 时浏览器会拒收，直接丢。
      var candidate = typeof msg.candidate === "string"
        ? { candidate: msg.candidate, sdpMid: "0", sdpMLineIndex: 0 }
        : msg.candidate;
      if (!st.pc || !candidate || !candidate.candidate) return;
      if (candidate.sdpMid == null && candidate.sdpMLineIndex == null) return;
      st.pc.addIceCandidate(candidate).catch(function (error) {
        noteError("addIceCandidate: " + String(error && error.message ? error.message : error));
      });
      return;
    }
    if (msg.t === "bye") {
      if (msg.code === "gone") { gated(); return; }
      if (msg.code === "offline" || msg.code === "peer-left") {
        if (st.phase === "receiving") st.errors.push("分享者断开，等待重连");
        resetTransfer();
        stopPeer();
        st.offerSeen = false;
        st.phase = "offline";
        el.track.dataset.on = "0";
        setStatus("对方暂时离线", "offline");
        return;
      }
      return;
    }
    if (msg.t === "err") {
      if (msg.code === "gone") { gated(); return; }
      if (msg.code === "busy") { fail("对方正忙"); return; }
      if (msg.code === "rate") {
        st.phase = "offline";
        setStatus("对方暂时离线", "offline");
        scheduleRejoin([65000]);
        return;
      }
      if (msg.code === "connect-failed") { fail("无法直连"); return; }
      if (msg.code === "too-large") { fail("文件太大，请用 App 接收"); return; }
    }
  }

  function handleOffer(msg) {
    st.offerAt = nowMs();
    clearTimeout(st.offerTimer);
    if (st.phase === "done") return;
    resetTransfer();
    stopPeer();
    Promise.resolve()
      .then(function () { return verifyOffer(msg.sdp, msg.sig); })
      .then(function () {
        var pc = new RTCPeerConnection({ iceServers: STUN ? [{ urls: STUN }] : [] });
        st.pc = pc;
        pc.onconnectionstatechange = function () {
          if (st.pc !== pc || st.channelOpen) return;
          if (pc.connectionState === "failed") fail("无法直连");
        };
        pc.ondatachannel = function (event) { setupChannel(event.channel); };
        return pc.setRemoteDescription({ type: "offer", sdp: msg.sdp }).then(function () {
          return pc.createAnswer();
        }).then(function (answer) {
          return pc.setLocalDescription(answer);
        }).then(function () {
          return waitGathering(pc, 4000);
        }).then(function () {
          st.answerAt = nowMs();
          st.offerSeen = true;
          if (st.ws) st.ws.send(JSON.stringify({ t: "answer", sdp: pc.localDescription.sdp }));
          clearTimeout(st.channelTimer);
          st.channelTimer = setTimeout(function () {
            if (!st.channelOpen) fail("无法直连");
          }, CHANNEL_TIMEOUT_MS);
        });
      })
      .catch(function (error) { fail(sentence(error)); });
  }

  function waitGathering(pc, timeoutMs) {
    if (pc.iceGatheringState === "complete") return Promise.resolve();
    return new Promise(function (resolve) {
      var timer = setTimeout(done, timeoutMs);
      function done() {
        clearTimeout(timer);
        pc.removeEventListener("icegatheringstatechange", check);
        resolve();
      }
      function check() { if (pc.iceGatheringState === "complete") done(); }
      pc.addEventListener("icegatheringstatechange", check);
    });
  }

  /* ------------------------------------------------------------------ 7. 启动 */

  function pathShareId() {
    var match = /^\/r\/([0-9a-fA-F]{32})\/?$/.exec(location.pathname);
    return match ? match[1].toLowerCase() : "";
  }

  async function boot() {
    el.retry.onclick = function () { location.reload(); };
    diag.tokenPrefix = st.token ? st.token.slice(0, 4) : null;
    setStatus("正在连接", "boot");
    if (!st.token) {
      if (pathShareId()) { gated(); return; }   // /r/<id> 但没有 token：链接不完整
      bareVisit();
      return;
    }
    if (!window.isSecureContext || !crypto || !crypto.subtle) { fail("请用 https 打开这个链接"); return; }
    cleanOpfs();
    st.ep = randomEp();
    try {
      var response = await fetchShare();
      var info = await response.json().catch(function () { return null; });
      if (!response.ok || !info) { gated(); return; }
      if (info.status === "revoked" || info.status === "expired" || info.status === "not-found") { gated(); return; }
      var fromPath = pathShareId();
      if (fromPath && info.shareId && fromPath !== String(info.shareId).toLowerCase()) { gated(); return; }
      st.info = info;
      st.shareId = info.shareId;
      diag.shareId = info.shareId;
      if (info.files && info.files.length) {
        st.files = info.files;
        st.totalSize = info.files.reduce(function (sum, item) { return sum + (Number(item.size) || 0); }, 0);
        renderName(0);
      }
      if (info.status === "offline") { st.phase = "offline"; setStatus("对方暂时离线", "offline"); }
      wire();
    } catch (error) {
      fail("连不上服务器，请重试");
    }
  }

  /** 查询分享状态：冷启动时 DNS/握手可能慢，超时就重来一次，别让用户看 12 s 白屏。 */
  async function fetchShare() {
    var url = ORIGIN + "/api/share/" + encodeURIComponent(st.token);
    var lastError = null;
    for (var attempt = 0; attempt < 3; attempt += 1) {
      var controller = new AbortController();
      var timer = setTimeout(function () { controller.abort(); }, 15000);
      try {
        var response = await fetch(url, { cache: "no-store", signal: controller.signal });
        clearTimeout(timer);
        return response;
      } catch (error) {
        clearTimeout(timer);
        lastError = error;
        await new Promise(function (done) { setTimeout(done, 1200); });
      }
    }
    throw lastError || new Error("fetch failed");
  }

  window.addEventListener("error", function (event) {
    st.errors.push("window.error: " + String(event.message || event.error));
  });
  window.addEventListener("unhandledrejection", function (event) {
    var reason = event.reason;
    st.errors.push("unhandledrejection: " + String(reason && reason.message ? reason.message : reason));
  });

  boot();
})();