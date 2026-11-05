import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import { DevkitError, firstLine } from "./errors.mjs";

export const IS_WINDOWS = process.platform === "win32";
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

let cached = null;

/**
 * adb 解析顺序：
 *   1. DEVKIT_ADB
 *   2. ADB
 *   3. ANDROID_HOME / ANDROID_SDK_ROOT 下的 platform-tools
 *   4. 本工作区默认 SDK（E:\agentHome\android-sdk）
 *   5. 用户目录常见 SDK、C:\Android\Sdk
 *   6. PATH 里的 adb
 */
export function resolveAdb() {
  if (cached) return cached;
  const exe = IS_WINDOWS ? "adb.exe" : "adb";
  const candidates = [];
  const add = (p, source) => {
    if (!p) return;
    const resolved = path.resolve(p);
    if (!candidates.some((item) => item.path === resolved)) candidates.push({ path: resolved, source });
  };

  // 显式指定优先且具有权威性：路径不存在就直接报错，不静默回退到别的 adb。
  for (const key of ["DEVKIT_ADB", "ADB"]) {
    const configured = process.env[key];
    if (configured) {
      const resolved = path.resolve(configured);
      cached = { path: resolved, source: key, exists: fs.existsSync(resolved) };
      return cached;
    }
  }
  for (const key of ["ANDROID_HOME", "ANDROID_SDK_ROOT"]) {
    const sdk = process.env[key];
    if (sdk) add(path.join(sdk, "platform-tools", exe), key);
  }
  add("E:/agentHome/android-sdk/platform-tools/" + exe, "workspace-default");
  add(path.join(os.homedir(), "AppData/Local/Android/Sdk/platform-tools", exe), "user-sdk");
  add("C:/Android/Sdk/platform-tools/" + exe, "common-sdk");

  for (const candidate of candidates) {
    if (fs.existsSync(candidate.path)) {
      cached = { ...candidate, exists: true };
      return cached;
    }
  }
  cached = { path: exe, source: "PATH", exists: false };
  return cached;
}

/**
 * 直接 spawn adb，不经过 shell，避免 MSYS 的 POSIX 路径转换把 /sdcard 改写成 C:/...
 * stdout/stderr 一律按 Buffer 收集，截图/录屏走二进制。
 */
export function runAdb(adbPath, args, { serial, timeoutMs = 30000, cwd } = {}) {
  const fullArgs = serial ? ["-s", serial, ...args] : args.slice();
  return new Promise((resolve) => {
    let settled = false;
    let timer = null;
    let killTimer = null;
    const settle = (result) => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      if (killTimer) clearTimeout(killTimer);
      resolve(result);
    };

    let child;
    try {
      child = spawn(adbPath, fullArgs, { windowsHide: true, cwd });
    } catch (error) {
      settle({
        ok: false, code: null, timedOut: false,
        stdout: Buffer.alloc(0), stderr: Buffer.from(String(error)),
        text: "", errText: String(error), error,
      });
      return;
    }

    const out = [];
    const err = [];
    let timedOut = false;

    timer = setTimeout(() => {
      timedOut = true;
      try { child.kill("SIGKILL"); } catch { /* ignore */ }
      killTimer = setTimeout(() => {
        settle({
          ok: false, code: null, timedOut: true,
          stdout: Buffer.concat(out), stderr: Buffer.concat(err),
          text: Buffer.concat(out).toString("utf8"), errText: Buffer.concat(err).toString("utf8"),
          error: new Error("adb timed out"),
        });
      }, 2000);
    }, timeoutMs);

    child.stdout.on("data", (chunk) => out.push(chunk));
    child.stderr.on("data", (chunk) => err.push(chunk));
    child.on("error", (error) => {
      settle({
        ok: false, code: null, timedOut,
        stdout: Buffer.concat(out), stderr: Buffer.concat(err),
        text: Buffer.concat(out).toString("utf8"), errText: Buffer.concat(err).toString("utf8"),
        error,
      });
    });
    child.on("close", (code) => {
      const stdout = Buffer.concat(out);
      const stderr = Buffer.concat(err);
      settle({
        ok: !timedOut && code === 0,
        code, timedOut,
        stdout, stderr,
        text: stdout.toString("utf8"),
        errText: stderr.toString("utf8"),
      });
    });
  });
}

function adbFailure(result, action) {
  const message = firstLine(result.errText) || firstLine(result.text) || result.error?.message || "unknown adb error";
  return new DevkitError(`${action} failed: ${message}`, { code: 3, kind: "adb" });
}

export async function adbVersion(adbPath) {
  const result = await runAdb(adbPath, ["version"], { timeoutMs: 15000 });
  if (!result.ok) throw adbFailure(result, "adb version");
  return firstLine(result.text);
}

export async function listDevices(adbPath) {
  const result = await runAdb(adbPath, ["devices", "-l"], { timeoutMs: 25000 });
  if (result.error?.code === "ENOENT") {
    throw new DevkitError(
      `adb executable not found: ${adbPath}. Set DEVKIT_ADB or ANDROID_HOME.`,
      { code: 3, kind: "adb", hint: "示例：export DEVKIT_ADB=E:/agentHome/android-sdk/platform-tools/adb.exe" },
    );
  }
  if (!result.ok) throw adbFailure(result, "adb devices");
  const devices = [];
  for (const raw of result.text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (/^List of devices attached/i.test(line)) continue;
    if (/^\*/.test(line)) continue;
    const parts = line.split(/\s+/);
    const serial = parts[0];
    const state = parts[1] || "unknown";
    const extra = {};
    for (const token of parts.slice(2)) {
      const at = token.indexOf(":");
      if (at > 0) extra[token.slice(0, at)] = token.slice(at + 1);
    }
    devices.push({ serial, state, ...extra });
  }
  return devices;
}

const INFO_SCRIPT = [
  'echo "DEVKIT_MANUFACTURER=$(getprop ro.product.manufacturer)"',
  'echo "DEVKIT_MODEL=$(getprop ro.product.model)"',
  'echo "DEVKIT_RELEASE=$(getprop ro.build.version.release)"',
  'echo "DEVKIT_SDK=$(getprop ro.build.version.sdk)"',
  "wm size",
  "wm density",
].join("; ");

export async function getDeviceInfo(adbPath, serial) {
  const result = await runAdb(adbPath, ["shell", INFO_SCRIPT], { serial, timeoutMs: 20000 });
  if (!result.ok) throw adbFailure(result, "device info");
  const info = {
    manufacturer: undefined,
    model: undefined,
    release: undefined,
    sdk: undefined,
    size: undefined,
    physicalSize: undefined,
    density: undefined,
    physicalDensity: undefined,
  };
  for (const raw of result.text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    const marked = /^DEVKIT_([A-Z_]+)=(.*)$/.exec(line);
    if (marked) {
      const key = marked[1].toLowerCase();
      const value = marked[2].trim();
      if (key === "sdk") info.sdk = Number(value) || undefined;
      else info[key] = value || undefined;
      continue;
    }
    const size = /^(Physical|Override) size: (\d+x\d+)/.exec(line);
    if (size) {
      if (size[1] === "Physical") info.physicalSize = size[2];
      else info.size = size[2];
      continue;
    }
    const density = /^(Physical|Override) density: (\d+)/.exec(line);
    if (density) {
      if (density[1] === "Physical") info.physicalDensity = Number(density[2]);
      else info.density = Number(density[2]);
    }
  }
  if (!info.size) info.size = info.physicalSize;
  if (!info.density) info.density = info.physicalDensity;
  return info;
}

export async function getFocus(adbPath, serial) {
  const result = await runAdb(
    adbPath,
    ["shell", "dumpsys window 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp'"],
    { serial, timeoutMs: 12000 },
  );
  if (!result.ok) return null;
  const line = result.text.split(/\r?\n/).find((item) => item.includes("mCurrentFocus="));
  if (!line) return null;
  const match = line.match(/mCurrentFocus=Window\{\S+\s+\S+\s+([^\s/]+)\/([^\s}]+)/);
  if (!match) return { raw: line.trim(), package: null, activity: null };
  return { package: match[1], activity: match[2], raw: line.trim() };
}

export async function getTopActivity(adbPath, serial) {
  const result = await runAdb(
    adbPath,
    ["shell", "dumpsys activity activities 2>/dev/null | grep -m1 topResumedActivity"],
    { serial, timeoutMs: 12000 },
  );
  if (!result.ok) return null;
  const line = result.text.split(/\r?\n/).find((item) => item.includes("topResumedActivity="));
  if (!line) return null;
  const match = line.match(/topResumedActivity=ActivityRecord\{\S+\s+\S+\s+([^\s/]+)\/([^\s}\s]+)/);
  if (!match) return { raw: line.trim(), package: null, activity: null };
  return { package: match[1], activity: match[2], raw: line.trim() };
}

export async function pmListPackages(adbPath, serial, { thirdParty = true } = {}) {
  const result = await runAdb(adbPath, ["shell", `pm list packages${thirdParty ? " -3" : ""}`], {
    serial,
    timeoutMs: 25000,
  });
  if (!result.ok) throw adbFailure(result, "pm list packages");
  const packages = new Set();
  for (const raw of result.text.split(/\r?\n/)) {
    const line = raw.trim();
    if (line.startsWith("package:")) packages.add(line.slice("package:".length));
  }
  return packages;
}
