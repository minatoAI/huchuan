"use strict";

/**
 * WebView 里的发送端：只负责 RTCPeerConnection / DataChannel / 读文件字节。
 *
 * 与 spikes/webview-link 的区别：设备身份、建分享、信令 WS 都在原生侧（core 的 SignalClient），
 * 这里不再自己连信令、不再自己签名。原生通过 window.HuPeer.* 驱动这个页面，
 * 页面通过 window.HuNative.* 把 offer / ICE / 进度 / verify 交回原生。
 *
 * 字节协议与原生实现完全一致（接收页 pages/public/recv/app.js 认的就是这套）：
 *   - 文本帧 {t:"meta", files:[{name,size,sha256,mime}], chunks, chunkSize}
 *   - 二进制帧 u32be(index) ‖ payload，payload 固定 16384 字节（最后一帧可为余数）
 *   - 文本帧 {t:"done", chunks}；接收页回 {t:"verify", ok, sha256, bytes}
 * 帧必须凑满 16 KiB：接收页往 256 KiB 的 coalesce 缓冲里直接 set，碎帧会 offset out of bounds。
 */
(function () {
  var CHUNK = 16 * 1024;
  var MAX_BUFFERED = 2 * 1024 * 1024;
  var REPORT_BYTES = 1024 * 1024;
  var VERIFY_TIMEOUT_MS = 60000;
  var STUN = "stun:stun.cloudflare.com:3478";

  var native = window.HuNative || null;
  var el = { status: document.getElementById("status"), log: document.getElementById("log") };

  var s = {
    meta: null,
    pc: null,
    channel: null,
    pendingIce: [],
    reader: null,
    sending: false,
    stopping: false,
    reported: false,
    sent: 0
  };

  function call(name) {
    if (!native || typeof native[name] !== "function") return;
    var args = Array.prototype.slice.call(arguments, 1);
    try { native[name].apply(native, args); } catch (_) { /* 原生侧已销毁 */ }
  }

  function log(line) {
    if (el.log) el.log.textContent += line + "\n";
    call("log", String(line));
  }

  function setStatus(text) { if (el.status) el.status.textContent = text; }

  /** 能力探测：接收端（Kotlin）在启动时也会自己探一次，这里探的是"这台设备的 WebView 到底能不能用"。 */
  function capabilities() {
    var proto = window.RTCPeerConnection && window.RTCPeerConnection.prototype;
    return {
      rtc: !!window.RTCPeerConnection,
      dc: !!window.RTCDataChannel && !!proto && typeof proto.createDataChannel === "function",
      crypto: !!(window.crypto && window.crypto.subtle),
      secure: !!window.isSecureContext
    };
  }

  function fail(error) {
    var text = error && error.message ? error.message : String(error);
    log("FAIL " + text);
    if (s.reported) return;
    s.reported = true;
    call("fail", "webview");
  }

  function stopPeer() {
    s.stopping = true;
    try { if (s.reader) s.reader.cancel(); } catch (_) { /* 已关 */ }
    s.reader = null;
    try { if (s.channel) s.channel.close(); } catch (_) { /* 已关 */ }
    try { if (s.pc) s.pc.close(); } catch (_) { /* 已关 */ }
    s.channel = null;
    s.pc = null;
    s.pendingIce = [];
    s.sending = false;
    s.stopping = false;
  }

  function drain(channel) {
    if (channel.bufferedAmount <= MAX_BUFFERED) return Promise.resolve();
    return new Promise(function (resolve) {
      var timer = setInterval(function () {
        if (channel.bufferedAmount <= MAX_BUFFERED || channel.readyState !== "open") {
          clearInterval(timer);
          resolve();
        }
      }, 1);
    });
  }

  async function transfer(channel) {
    if (s.sending || s.reported) return;
    s.sending = true;
    var meta = s.meta;
    if (!meta) throw new Error("meta-missing");

    var response = await fetch("file", { cache: "no-store" });
    if (!response.ok || !response.body) throw new Error("file-http-" + response.status);
    s.reader = response.body.getReader();

    channel.send(JSON.stringify({
      t: "meta",
      files: [{ name: meta.name, size: meta.size, sha256: meta.sha256, mime: meta.mime }],
      chunks: Math.ceil(meta.size / CHUNK),
      chunkSize: CHUNK
    }));
    log("META sent " + meta.name + " " + meta.size + "B");

    var frame = new Uint8Array(4 + CHUNK);
    var index = 0;
    var startedAt = performance.now();
    var lastReport = 0;
    var pending = new Uint8Array(0);

    async function sendFrame(bytes) {
      frame[0] = (index >>> 24) & 255;
      frame[1] = (index >>> 16) & 255;
      frame[2] = (index >>> 8) & 255;
      frame[3] = index & 255;
      frame.set(bytes, 4);
      await drain(channel);
      channel.send(frame.subarray(0, 4 + bytes.length));
      index += 1;
      s.sent += bytes.length;
      if (s.sent - lastReport >= REPORT_BYTES) {
        lastReport = s.sent;
        call("progress", s.sent, meta.size);
      }
    }

    while (true) {
      var step = await s.reader.read();
      if (step.done) break;
      var value = step.value;
      if (pending.length) {
        var merged = new Uint8Array(pending.length + value.length);
        merged.set(pending);
        merged.set(value, pending.length);
        value = merged;
      }
      var offset = 0;
      while (value.length - offset >= CHUNK) {
        await sendFrame(value.subarray(offset, offset + CHUNK));
        offset += CHUNK;
      }
      pending = value.subarray(offset);
      if (s.reported) return;
    }
    if (pending.length) await sendFrame(pending);

    channel.send(JSON.stringify({ t: "done", chunks: index }));
    var ms = Math.round(performance.now() - startedAt);
    log("SEND done chunks=" + index + " bytes=" + s.sent + " ms=" + ms
      + " MiB/s=" + (ms > 0 ? (s.sent / 1048576 / (ms / 1000)).toFixed(2) : "-"));
    call("progress", meta.size, meta.size);
    setTimeout(function () {
      if (!s.reported) { s.reported = true; call("fail", "verify-timeout"); }
    }, VERIFY_TIMEOUT_MS);
  }

  async function nextVisitor() {
    stopPeer();
    s.reported = false;
    s.sent = 0;
    var pc = new RTCPeerConnection({ iceServers: STUN ? [{ urls: STUN }] : [] });
    s.pc = pc;
    pc.onicecandidate = function (event) {
      if (!event.candidate || !event.candidate.candidate) return;
      call("ice", event.candidate.candidate);
    };
    pc.onconnectionstatechange = function () { log("CONN " + pc.connectionState); };
    var channel = pc.createDataChannel("huchuan/1", { ordered: true });
    s.channel = channel;
    channel.binaryType = "arraybuffer";
    channel.onopen = function () {
      log("CHANNEL_OPEN");
      call("open");
      transfer(channel).catch(fail);
    };
    channel.onclose = function () {
      log("CHANNEL_CLOSE");
      if (!s.stopping && !s.reported) call("closed");
    };
    channel.onmessage = function (event) {
      if (typeof event.data !== "string") return;
      var message = null;
      try { message = JSON.parse(event.data); } catch (_) { return; }
      if (!message || message.t !== "verify") return;
      s.reported = true;
      log("VERIFY ok=" + message.ok + " bytes=" + message.bytes);
      call("verify", message.ok === true, String(message.sha256 || ""), Number(message.bytes || 0));
    };
    var offer = await pc.createOffer();
    await pc.setLocalDescription(offer);
    var sdp = pc.localDescription ? pc.localDescription.sdp : "";
    if (!sdp || sdp.indexOf("a=fingerprint:") < 0) throw new Error("offer-no-fingerprint");
    log("OFFER " + sdp.length + "B");
    call("offer", sdp);
  }

  function addRemote(candidate) {
    var pc = s.pc;
    if (!pc) return;
    var mid = null;
    try {
      var m = /a=mid:(\S+)/.exec(pc.remoteDescription ? pc.remoteDescription.sdp : "");
      if (m) mid = m[1];
    } catch (_) { /* 取不到就让 sdpMLineIndex 兜底 */ }
    pc.addIceCandidate({ candidate: candidate, sdpMid: mid, sdpMLineIndex: 0 }).catch(function (error) {
      log("ICE_REMOTE_ERR " + (error && error.message));
    });
  }

  function answer(sdp) {
    var pc = s.pc;
    if (!pc || !sdp) return;
    pc.setRemoteDescription({ type: "answer", sdp: sdp }).then(function () {
      log("ANSWER applied " + String(sdp).length + "B");
      var queued = s.pendingIce;
      s.pendingIce = [];
      for (var i = 0; i < queued.length; i++) addRemote(queued[i]);
    }).catch(function (error) {
      fail(new Error("answer: " + (error && error.message ? error.message : error)));
    });
  }

  function ice(candidate) {
    if (!candidate) return;
    if (s.pc && s.pc.remoteDescription) addRemote(candidate);
    else s.pendingIce.push(candidate);
  }

  async function boot() {
    var caps = null;
    try { caps = capabilities(); } catch (_) { caps = { rtc: false, dc: false, crypto: false, secure: false }; }
    var ok = !!(caps.rtc && caps.dc && caps.crypto);
    log("BOOT origin=" + location.origin + " secure=" + caps.secure + " " + JSON.stringify(caps));
    call("capable", ok);
    if (!ok) {
      setStatus("这台设备的浏览器内核不支持分享链接");
      return;
    }
    var response = await fetch("meta", { cache: "no-store" });
    s.meta = await response.json();
    s.meta.size = Number(s.meta.size);
    setStatus(s.meta.name);
    log("META " + JSON.stringify(s.meta));
    call("ready");
  }

  window.HuPeer = {
    nextVisitor: function () { nextVisitor().catch(fail); },
    answer: answer,
    ice: ice,
    stop: function () { stopPeer(); }
  };

  window.addEventListener("error", function (event) {
    log("WINDOW_ERROR " + String(event && event.message));
  });
  window.addEventListener("unhandledrejection", function (event) {
    var reason = event && event.reason;
    log("UNHANDLED " + String(reason && reason.message ? reason.message : reason));
  });

  boot().catch(fail);
})();
