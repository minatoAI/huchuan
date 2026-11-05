#!/usr/bin/env node
/**
 * CI 式一键验证示例：在两台真机上跑同一条"安装→启动→操作→断言→留证"链路。
 *
 * 这个示例不依赖被测 App，用系统设置页证明链路本身可用：
 *   启动设置 → dump 首页 → 找候选入口 → 点击 → 等待目标页文本 → dump/截图留证。
 * 等 bt-share-app 有 APK 后，把 CANDIDATES 换成 App 内的文案即可。
 */
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const DEVKIT = path.resolve(HERE, "../devkit.mjs");
const ROOT = path.resolve(HERE, "../../..");
const VERIFY_ROOT = path.join(ROOT, "artifacts", "verify");

// 候选入口：设备/语言不同，同一功能入口文案可能不同；按顺序尝试。
// 每个 expect 是"目标页上才会出现的文本"，避免把首页自身文本误判为跳转成功。
const CANDIDATES = [
  {
    slug: "battery",
    find: ["电池"],
    expect: ["电池用量", "省电模式", "电池管理器", "充电控制"],
  },
  {
    slug: "display",
    find: ["显示与亮度"],
    expect: ["屏幕色彩模式", "亮色模式", "暗色模式", "显示效果", "屏幕分辨率", "自动旋转"],
  },
  {
    slug: "apps",
    find: ["应用"],
    expect: ["最近使用的应用", "默认应用", "应用信息", "应用管理", "所有应用"],
  },
  {
    slug: "notifications",
    find: ["通知"],
    expect: ["通知历史", "通知管理", "允许通知", "通知与状态栏"],
  },
  {
    slug: "wlan",
    find: ["WLAN"],
    expect: ["可用网络", "添加网络", "WLAN 助理", "热点和网络共享", "互联网"],
  },
];

function parseCli(argv) {
  const serials = [];
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === "-s" || token === "--serial") {
      const value = argv[++i];
      if (!value) throw new Error("-s requires a serial");
      serials.push(...value.split(",").filter(Boolean));
    } else if (token === "--help" || token === "-h") {
      console.log("用法: node tools/devkit/examples/system-ui-smoke.mjs [-s <serial>]");
      process.exit(0);
    } else {
      throw new Error(`unknown argument: ${token}`);
    }
  }
  return { serials };
}

function stamp() {
  const date = new Date();
  const pad = (value, size = 2) => String(value).padStart(size, "0");
  return `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
}

function runDevkit(args, { timeout = 120000 } = {}) {
  const result = spawnSync(process.execPath, [DEVKIT, ...args, "--json"], {
    encoding: "utf8",
    timeout,
    windowsHide: true,
    maxBuffer: 64 * 1024 * 1024,
  });
  const lines = (result.stdout || "")
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => {
      try {
        return JSON.parse(line);
      } catch {
        return { ok: false, raw: line };
      }
    });
  return {
    status: result.status,
    lines,
    stdout: result.stdout,
    stderr: result.stderr,
    error: result.error,
  };
}

function firstError(result) {
  const failed = result.lines.find((line) => line && line.ok === false);
  if (failed) return `${failed.code ?? "?"} ${failed.error ?? failed.raw ?? "unknown"}`;
  if (result.error) return result.error.message;
  return (result.stderr || "").trim() || `exit ${result.status}`;
}

function escapeRegex(text) {
  return text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function makeLogger(commandsPath) {
  return (entry) => {
    fs.appendFileSync(commandsPath, JSON.stringify({ ts: new Date().toISOString(), ...entry }) + "\n", "utf8");
  };
}

function main() {
  const { serials: requested } = parseCli(process.argv.slice(2));
  const stampText = stamp();
  const runDir = path.join(VERIFY_ROOT, stampText);
  fs.mkdirSync(runDir, { recursive: true });
  const commandsPath = path.join(runDir, "commands.jsonl");
  const log = makeLogger(commandsPath);

  const devicesResult = runDevkit(["devices"], { timeout: 60000 });
  log({ step: "devices", args: ["devices"], status: devicesResult.status });
  if (devicesResult.status !== 0) {
    console.error(`devices failed: ${firstError(devicesResult)}`);
    return 3;
  }
  const allDevices = devicesResult.lines.filter((line) => line.cmd === "devices" && !line.summary);
  let devices = allDevices.filter((line) => line.state === "device");
  if (requested.length) devices = devices.filter((line) => requested.includes(line.serial));
  if (!devices.length) {
    console.error("no online devices");
    return 3;
  }

  const results = [];
  for (const device of devices) {
    const serial = device.serial;
    const record = {
      serial,
      model: device.model,
      sdk: device.sdk,
      release: device.release,
      size: device.size,
      ok: false,
      attempts: [],
      target: null,
      error: null,
    };
    console.log(`[${serial}] ${device.model} (Android ${device.release}/API ${device.sdk}) — start`);

    const launch = runDevkit(["launch", "com.android.settings", "--fresh", "-s", serial]);
    log({ serial, step: "launch", args: ["launch", "com.android.settings", "--fresh"], status: launch.status });
    if (launch.status !== 0) {
      record.error = `launch settings failed: ${firstError(launch)}`;
      results.push(record);
      console.log(`[${serial}] FAIL ${record.error}`);
      continue;
    }

    const homeShot = runDevkit(["screenshot", "-s", serial, "--name", "01-settings-home"]);
    log({ serial, step: "screenshot", args: ["screenshot", "--name", "01-settings-home"], status: homeShot.status });
    const homeDump = runDevkit(["ui-dump", "-s", serial, "--name", "01-settings-home"]);
    log({ serial, step: "ui-dump", args: ["ui-dump", "--name", "01-settings-home"], status: homeDump.status });
    if (homeDump.status !== 0) {
      record.error = `ui-dump home failed: ${firstError(homeDump)}`;
      results.push(record);
      console.log(`[${serial}] FAIL ${record.error}`);
      continue;
    }
    const homeData = homeDump.lines.find((line) => line.cmd === "ui-dump" && line.ok === true);
    const texts = Array.isArray(homeData?.texts) ? homeData.texts : [];

    let passed = false;
    for (const candidate of CANDIDATES) {
      const find = candidate.find.find((text) => texts.some((value) => value.includes(text)));
      if (!find) {
        record.attempts.push({ slug: candidate.slug, skipped: "entry not visible" });
        continue;
      }
      const attempt = { slug: candidate.slug, find, tap: null, wait: null };
      const tap = runDevkit(["tap-text", find, "-s", serial]);
      log({ serial, step: "tap-text", args: ["tap-text", find], status: tap.status });
      if (tap.status !== 0) {
        attempt.tap = firstError(tap);
        record.attempts.push(attempt);
        const relaunch = runDevkit(["launch", "com.android.settings", "--fresh", "-s", serial]);
        log({ serial, step: "relaunch", args: ["launch", "com.android.settings", "--fresh"], status: relaunch.status });
        continue;
      }
      const tapData = tap.lines.find((line) => line.cmd === "tap-text" && line.ok === true);
      attempt.tap = tapData
        ? { matched: tapData.matched, field: tapData.field, point: tapData.point, bounds: tapData.bounds }
        : true;

      const expected = candidate.expect.map(escapeRegex).join("|");
      const wait = runDevkit(["wait-text", expected, "--regex", "--timeout", "8000", "-s", serial], { timeout: 60000 });
      log({ serial, step: "wait-text", args: ["wait-text", expected, "--regex", "--timeout", "8000"], status: wait.status });
      if (wait.status !== 0) {
        attempt.wait = firstError(wait);
        record.attempts.push(attempt);
        const relaunch = runDevkit(["launch", "com.android.settings", "--fresh", "-s", serial]);
        log({ serial, step: "relaunch", args: ["launch", "com.android.settings", "--fresh"], status: relaunch.status });
        continue;
      }

      const waitData = wait.lines.find((line) => line.cmd === "wait-text" && line.ok === true);
      attempt.wait = { matched: waitData?.matched, elapsedMs: waitData?.elapsedMs, bounds: waitData?.bounds };

      const afterDump = runDevkit(["ui-dump", "-s", serial, "--name", `02-${candidate.slug}`]);
      log({ serial, step: "ui-dump", args: ["ui-dump", "--name", `02-${candidate.slug}`], status: afterDump.status });
      const afterShot = runDevkit(["screenshot", "-s", serial, "--name", `02-${candidate.slug}`]);
      log({ serial, step: "screenshot", args: ["screenshot", "--name", `02-${candidate.slug}`], status: afterShot.status });

      record.target = {
        slug: candidate.slug,
        find,
        expect: candidate.expect,
        matched: attempt.wait.matched,
        waitElapsedMs: attempt.wait.elapsedMs,
        tap: attempt.tap,
        afterDump: afterDump.lines.find((line) => line.cmd === "ui-dump" && line.ok === true)?.path,
        afterScreenshot: afterShot.lines.find((line) => line.cmd === "screenshot" && line.ok === true)?.path,
        homeDump: homeData?.path,
        homeScreenshot: homeShot.lines.find((line) => line.cmd === "screenshot" && line.ok === true)?.path,
      };
      record.ok = true;
      passed = true;
      record.attempts.push(attempt);
      break;
    }

    if (!passed && !record.error) {
      record.error = "no usable settings entry on this device (all candidates skipped or failed)";
      const fallbackShot = runDevkit(["screenshot", "-s", serial, "--name", "99-failure"]);
      log({ serial, step: "screenshot", args: ["screenshot", "--name", "99-failure"], status: fallbackShot.status });
    }
    results.push(record);
    if (record.ok) {
      console.log(`[${serial}] PASS ${record.target.find} → ${record.target.matched} (${record.target.waitElapsedMs}ms)`);
    } else {
      console.log(`[${serial}] FAIL ${record.error}`);
    }
  }

  const pass = results.filter((item) => item.ok).length;
  const fail = results.length - pass;
  const summary = {
    generatedAt: new Date().toISOString(),
    devkit: path.relative(ROOT, DEVKIT).split(path.sep).join("/"),
    pass,
    fail,
    devices: results,
  };
  const summaryPath = path.join(runDir, "summary.json");
  const textPath = path.join(runDir, "summary.txt");
  fs.writeFileSync(summaryPath, JSON.stringify(summary, null, 2) + "\n", "utf8");
  const lines = [
    `# system-ui smoke ${stampText}`,
    `# pass=${pass} fail=${fail}`,
    ...results.map((item) => item.ok
      ? `PASS serial=${item.serial} model="${item.model}" sdk=${item.sdk} find="${item.target.find}" matched="${item.target.matched}" waitMs=${item.target.waitElapsedMs}`
      : `FAIL serial=${item.serial} model="${item.model}" sdk=${item.sdk} error="${item.error}"`),
    `# summary: ${summaryPath}`,
  ];
  fs.writeFileSync(textPath, lines.join("\n") + "\n", "utf8");
  console.log(lines.join("\n"));
  console.log(`summary: ${summaryPath}`);
  return fail === 0 ? 0 : 1;
}

try {
  process.exitCode = main();
} catch (error) {
  console.error(`smoke fatal: ${error?.stack || error}`);
  process.exitCode = 2;
}
