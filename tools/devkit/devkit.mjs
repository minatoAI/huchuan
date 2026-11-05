#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { DevkitError, firstLine } from "./lib/errors.mjs";
import {
  adbVersion,
  getDeviceInfo,
  getFocus,
  getTopActivity,
  listDevices,
  pmListPackages,
  resolveAdb,
  runAdb,
  sleep,
} from "./lib/adb.mjs";
import { centerOf, collectTexts, dumpUi, matchNodes, parseUiXml } from "./lib/ui.mjs";
import { artifactFile, ensureDir, recordArtifact, stamp, toDisplay } from "./lib/artifacts.mjs";

const VERSION = "0.1.0";
const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const MULTI_SAFE = new Set(["screenshot", "ui-dump", "logcat-dump", "clear-logs"]);
const HANDLERS = {
  install: cmdInstall,
  launch: cmdLaunch,
  screenshot: cmdScreenshot,
  "ui-dump": cmdUiDump,
  "tap-text": cmdTapText,
  "wait-text": cmdWaitText,
  "logcat-dump": cmdLogcatDump,
  "clear-logs": cmdClearLogs,
  input: cmdInput,
  key: cmdKey,
  swipe: cmdSwipe,
  record: cmdRecord,
};

const BOOL_FLAGS = new Set([
  "json", "all", "help", "version", "no-manifest", "texts", "exact", "regex",
  "no-grant", "downgrade", "fresh", "quiet", "verify",
]);
const VALUE_FLAGS = new Set([
  "serial", "s", "artifacts", "name", "timeout", "interval", "index", "out",
  "tag", "lines", "buffer", "grep", "require", "seconds", "bitrate", "activity",
  "extra", "es",
]);
// 可重复选项：--extra / --es 各收一条 k=v，最终按先后顺序透传
const REPEAT_FLAGS = new Set(["extra", "es"]);

function usage() {
  return [
    `devkit ${VERSION} — Android 真机验证工具（adb + uiautomator）`,
    "",
    "用法: node tools/devkit/devkit.mjs <command> [args] [options]",
    "",
    "命令:",
    "  devices                      列出设备（在线/离线，含型号/系统/分辨率）",
    "  doctor                       环境自检（adb 路径、版本、产物目录、设备数）",
    "  install <apk>                安装 APK（-r -g -t；--downgrade 允许降级）",
    "  launch <pkg> [activity]      启动并确认进入前台（--fresh 先 force-stop）",
    "  screenshot                   截图，落到 artifacts/screenshots/<serial>/",
    "  ui-dump [--texts]             dump UI 层级，落到 artifacts/ui-dumps/<serial>/",
    "  tap-text <text>              点击 text/content-desc/hint 命中的节点",
    "  wait-text <text> [timeoutMs]  轮询等待文本出现（默认 15s）",
    "  input text <text>            往当前焦点输入框打字（--verify 打完 dump 校验）",
    "  logcat-dump                  导出 logcat（--grep/--tag/--lines/--out）",
    "  clear-logs                   清空 logcat 缓冲区",
    "  key <CODE>                   发送按键（如 KEYCODE_BACK / KEYCODE_HOME）",
    "  swipe <x1> <y1> <x2> <y2> [ms]",
    "  record [--seconds N]         录屏取证（1-180s，默认 10s）",
    "",
    "全局选项:",
    "  -s, --serial <serial>        指定设备，可重复或逗号分隔；默认唯一在线设备",
    "  --all                        对所有在线设备执行",
    "  --json                       每行输出一个 JSON 对象（便于 grep/jq）",
    "  --artifacts <dir>            产物根目录（默认 <repo>/artifacts）",
    "  --no-manifest                不写 artifacts/manifest.jsonl",
    "",
    "命令选项:",
    "  --name <name>                截图/UI dump 文件名后缀",
    "  --exact / --regex            文本精确匹配 / 正则匹配",
    "  --index <n>                  多个命中时选第 n 个（0 起）",
    "  --timeout <ms>               wait-text / launch 超时",
    "  --interval <ms>              wait-text 轮询间隔（默认 400）",
    "  --texts                      ui-dump 同时输出所有 TEXT/DESC 行",
    "  --grep <regex>               logcat 本地过滤",
    "  --require <regex>            logcat 必须命中，否则退出 1（断言）",
    "  --tag <tag[,tag]>            logcat tag 过滤（自动补 :V）",
    "  --lines <n>                  logcat 最近 n 行（默认 2000）",
    "  --buffer <name>              logcat buffer（main/system/crash/all）",
    "  --out <file>                 logcat 输出文件路径",
    "  --no-grant                   install 时不加 -g",
    "  --downgrade                  install 允许 -d 降级",
    "  --seconds <n>                record 时长",
    "  --bitrate <rate>             record 码率（默认 8000000）",
    "  --extra <key=value>          launch 透传字符串 intent extra（可重复，等价 am --es）",
    "  --es <key=value>             --extra 的别名",
    "  --verify                     input text 后 dump 当前界面并校验文本落进输入框",
    "  --fresh                      launch 前先 force-stop（给了 --extra 也会自动 force-stop）",
    "",
    "退出码: 0 成功 / 1 断言失败 / 2 用法错误 / 3 adb 或设备错误 / 4 本地文件错误",
    "",
    "示例:",
    "  node tools/devkit/devkit.mjs devices",
    "  node tools/devkit/devkit.mjs screenshot",
    "  node tools/devkit/devkit.mjs ui-dump --texts -s 5b94673a",
    "  node tools/devkit/devkit.mjs launch com.android.settings --fresh -s 5b94673a",
    "  node tools/devkit/devkit.mjs wait-text \"语言和输入法\" --timeout 10000 -s 5b94673a",
    "  node tools/devkit/devkit.mjs input text \"hello world\" --verify -s 5b94673a",
    "  node tools/devkit/devkit.mjs launch app.huchuan --extra huchuan.debug.network=failed -s 5b94673a",
  ].join("\n");
}

function usageError(message) {
  return new DevkitError(message, { code: 2, kind: "usage" });
}

function deviceError(message, details) {
  return new DevkitError(message, { code: 3, kind: "device", details });
}

function assertionError(message, details) {
  return new DevkitError(message, { code: 1, kind: "assert", details });
}

function ioError(message, details) {
  return new DevkitError(message, { code: 4, kind: "io", details });
}

function parseArgs(argv) {
  const opts = { serials: [], tags: [], json: false, all: false, help: false, version: false, quiet: false };
  const positionals = [];
  for (let i = 0; i < argv.length; i += 1) {
    const token = argv[i];
    if (token === "--") {
      positionals.push(...argv.slice(i + 1));
      break;
    }
    if (token === "-s") {
      const value = argv[++i];
      if (!value) throw usageError("-s requires a serial");
      opts.serials.push(...String(value).split(",").map((item) => item.trim()).filter(Boolean));
      continue;
    }
    if (token === "-h") {
      opts.help = true;
      continue;
    }
    if (token.startsWith("--")) {
      const eq = token.indexOf("=");
      const name = eq >= 0 ? token.slice(2, eq) : token.slice(2);
      let value = eq >= 0 ? token.slice(eq + 1) : undefined;
      if (VALUE_FLAGS.has(name)) {
        if (value === undefined) value = argv[++i];
        if (value === undefined || value === "") throw usageError(`--${name} requires a value`);
        if (name === "serial" || name === "s") {
          opts.serials.push(...String(value).split(",").map((item) => item.trim()).filter(Boolean));
        } else if (name === "tag") {
          opts.tags.push(...String(value).split(",").map((item) => item.trim()).filter(Boolean));
        } else if (REPEAT_FLAGS.has(name)) {
          if (!opts[name]) opts[name] = [];
          opts[name].push(String(value));
        } else {
          opts[name] = value;
        }
        continue;
      }
      if (BOOL_FLAGS.has(name)) {
        opts[name] = true;
        continue;
      }
      throw usageError(`unknown option: --${name}`);
    }
    if (token.startsWith("-") && token !== "-") {
      throw usageError(`unknown option: ${token}`);
    }
    positionals.push(token);
  }
  return { opts, positionals };
}

function formatKv(fields) {
  return Object.entries(fields)
    .filter(([, value]) => value !== undefined && value !== null)
    .map(([key, value]) => {
      if (typeof value === "number" || typeof value === "boolean") return `${key}=${value}`;
      const text = String(value);
      if (text === "") return `${key}=""`;
      return /[\s"\\]/.test(text) ? `${key}=${JSON.stringify(text)}` : `${key}=${text}`;
    })
    .join(" ");
}

function print(text) {
  process.stdout.write(text + "\n");
}

function intOption(value, name, fallback, { min = Number.MIN_SAFE_INTEGER, max = Number.MAX_SAFE_INTEGER } = {}) {
  if (value === undefined) return fallback;
  const number = Number(value);
  if (!Number.isInteger(number) || number < min || number > max) {
    throw usageError(`--${name} must be an integer in [${min}, ${max}]`);
  }
  return number;
}

function resolveArtifactsRoot(opts) {
  const raw = opts.artifacts || process.env.DEVKIT_ARTIFACTS || path.join(ROOT, "artifacts");
  return path.resolve(raw);
}

function writeArtifact(file, data) {
  try {
    fs.writeFileSync(file, data);
  } catch (error) {
    throw ioError(`cannot write artifact ${file}: ${error.message}`);
  }
}

function manifest(ctx, file, kind, extra = {}) {
  if (!ctx.manifestEnabled) return null;
  try {
    return recordArtifact({
      root: ctx.artifactsRoot,
      file,
      cmd: ctx.command,
      serial: ctx.serial,
      kind,
      extra,
    });
  } catch (error) {
    throw ioError(`cannot write artifact manifest: ${error.message}`);
  }
}

function pngSize(buffer) {
  if (buffer.length < 24) return {};
  if (buffer.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a") return {};
  return { width: buffer.readUInt32BE(16), height: buffer.readUInt32BE(20) };
}

function isMp4(buffer) {
  return buffer.length > 16 && buffer.subarray(4, 8).toString("ascii") === "ftyp";
}

function nodeLine(node) {
  const point = centerOf(node.bounds);
  const value = node.text || node.desc || node.hint;
  const type = node.text ? "text" : node.desc ? "desc" : "hint";
  return `NODE ${formatKv({
    type,
    value,
    bounds: `[${node.bounds.x1},${node.bounds.y1}][${node.bounds.x2},${node.bounds.y2}]`,
    center: `${point.x},${point.y}`,
    clickable: node.clickable,
    resId: node.resourceId,
  })}`;
}

async function cmdScreenshot(ctx) {
  const file = artifactFile(ctx.artifactsRoot, "screenshots", ctx.serial, "png", ctx.opts.name);
  const result = await runAdb(ctx.adbPath, ["exec-out", "screencap", "-p"], { serial: ctx.serial, timeoutMs: 30000 });
  if (!result.ok || result.stdout.length === 0) {
    throw deviceError(`screencap failed: ${firstLine(result.errText) || result.error?.message || "empty output"}`);
  }
  const buffer = result.stdout;
  const size = pngSize(buffer);
  if (!size.width || !size.height) {
    throw deviceError("screencap returned data that is not a PNG (exec-out encoding problem?)");
  }
  writeArtifact(file, buffer);
  const meta = manifest(ctx, file, "screenshot", { width: size.width, height: size.height });
  return {
    summary: { path: toDisplay(file), bytes: buffer.length, width: size.width, height: size.height },
    json: { path: toDisplay(file), bytes: buffer.length, width: size.width, height: size.height, sha256: meta?.sha256 },
  };
}

async function cmdUiDump(ctx) {
  const started = Date.now();
  const dump = await dumpUi(ctx.adbPath, ctx.serial, { attempts: 3 });
  const parsed = parseUiXml(dump.xml);
  const file = artifactFile(ctx.artifactsRoot, "ui-dumps", ctx.serial, "xml", ctx.opts.name);
  writeArtifact(file, dump.xml);
  const focus = await getFocus(ctx.adbPath, ctx.serial).catch(() => null);
  const texts = collectTexts(parsed.nodes);
  const descs = [...new Set(parsed.nodes.map((node) => node.desc).filter(Boolean))];
  const meta = manifest(ctx, file, "ui-dump", {
    nodes: parsed.nodes.length,
    package: parsed.packages.join(","),
  });
  const activity = focus && focus.package ? `${focus.package}/${focus.activity}` : "";
  const lines = [];
  if (ctx.opts.texts) {
    for (const node of parsed.nodes) {
      if (!node.text && !node.desc && !node.hint) continue;
      lines.push(nodeLine(node));
    }
  }
  return {
    summary: {
      path: toDisplay(file),
      bytes: dump.xml.length,
      nodes: parsed.nodes.length,
      package: parsed.packages.join(",") || undefined,
      activity: activity || undefined,
      rotation: parsed.rotation,
      elapsedMs: Date.now() - started,
    },
    json: {
      path: toDisplay(file),
      bytes: dump.xml.length,
      nodes: parsed.nodes.length,
      packages: parsed.packages,
      activity,
      rotation: parsed.rotation,
      texts,
      descs,
      attempts: dump.attempts,
      elapsedMs: Date.now() - started,
      sha256: meta?.sha256,
    },
    lines,
  };
}

function textNeedle(args, opts) {
  const needle = args[0];
  if (!needle) throw usageError("this command requires <text>");
  if (opts.regex) {
    try {
      new RegExp(needle, "i");
    } catch (error) {
      throw usageError(`invalid --regex: ${error.message}`);
    }
  }
  return needle;
}

async function cmdTapText(ctx) {
  const needle = textNeedle(ctx.args, ctx.opts);
  const dump = await dumpUi(ctx.adbPath, ctx.serial, { attempts: 2 });
  const parsed = parseUiXml(dump.xml);
  const matches = matchNodes(parsed.nodes, needle, { exact: ctx.opts.exact, regex: ctx.opts.regex });
  if (!matches.length) {
    const sample = collectTexts(parsed.nodes).slice(0, 15);
    throw assertionError(`text not found in current UI: ${needle}`, { sampleTexts: sample });
  }
  const index = intOption(ctx.opts.index, "index", 0, { min: 0, max: matches.length - 1 });
  const match = matches[index];
  const point = centerOf(match.node.bounds);
  const tap = await runAdb(ctx.adbPath, ["shell", `input tap ${point.x} ${point.y}`], {
    serial: ctx.serial,
    timeoutMs: 15000,
  });
  if (!tap.ok || /error/i.test(tap.errText)) {
    throw deviceError(`input tap failed: ${firstLine(tap.errText || tap.text) || "unknown"}`);
  }
  return {
    summary: {
      text: needle,
      matched: match.node.text || match.node.desc || match.node.hint,
      field: match.field,
      bounds: `[${match.node.bounds.x1},${match.node.bounds.y1}][${match.node.bounds.x2},${match.node.bounds.y2}]`,
      point: `${point.x},${point.y}`,
      candidates: matches.length,
      clickable: match.node.clickable,
    },
    json: {
      text: needle,
      matched: match.node.text || match.node.desc || match.node.hint,
      field: match.field,
      bounds: match.node.bounds,
      point,
      candidates: matches.length,
      clickable: match.node.clickable,
      package: match.node.package,
    },
  };
}

async function cmdWaitText(ctx) {
  const needle = textNeedle(ctx.args, ctx.opts);
  const timeout = intOption(ctx.opts.timeout ?? ctx.args[1], "timeout", 15000, { min: 100, max: 300000 });
  const interval = intOption(ctx.opts.interval, "interval", 400, { min: 50, max: 5000 });
  const started = Date.now();
  const deadline = started + timeout;
  let attempts = 0;
  let lastError = null;
  let lastPackage = "";

  while (true) {
    attempts += 1;
    try {
      const dump = await dumpUi(ctx.adbPath, ctx.serial, { attempts: 1 });
      const parsed = parseUiXml(dump.xml);
      lastPackage = parsed.packages.join(",");
      const matches = matchNodes(parsed.nodes, needle, { exact: ctx.opts.exact, regex: ctx.opts.regex });
      if (matches.length) {
        const match = matches[0];
        const point = centerOf(match.node.bounds);
        return {
          summary: {
            text: needle,
            matched: match.node.text || match.node.desc || match.node.hint,
            field: match.field,
            bounds: `[${match.node.bounds.x1},${match.node.bounds.y1}][${match.node.bounds.x2},${match.node.bounds.y2}]`,
            point: `${point.x},${point.y}`,
            elapsedMs: Date.now() - started,
            attempts,
          },
          json: {
            text: needle,
            matched: match.node.text || match.node.desc || match.node.hint,
            field: match.field,
            bounds: match.node.bounds,
            point,
            elapsedMs: Date.now() - started,
            attempts,
            package: match.node.package,
          },
        };
      }
      lastError = null;
    } catch (error) {
      lastError = error;
    }
    const remaining = deadline - Date.now();
    if (remaining <= 0) break;
    await sleep(Math.min(interval, remaining));
  }

  const message = `text not found within ${timeout}ms: ${needle}`;
  throw assertionError(
    lastError ? `${message} (last dump error: ${lastError.message})` : message,
    { attempts, lastPackage, lastDumpError: lastError?.message },
  );
}

async function cmdLogcatDump(ctx) {
  const lines = intOption(ctx.opts.lines, "lines", 2000, { min: 1, max: 200000 });
  const args = ["logcat", "-d", "-v", "threadtime", "-t", String(lines)];
  if (ctx.opts.buffer) args.push("-b", String(ctx.opts.buffer));
  if (ctx.opts.tags.length) {
    args.push("-s");
    for (const tag of ctx.opts.tags) args.push(tag.includes(":") ? tag : `${tag}:V`);
  }
  let grep = null;
  if (ctx.opts.grep) {
    try {
      grep = new RegExp(ctx.opts.grep, "i");
    } catch (error) {
      throw usageError(`invalid --grep: ${error.message}`);
    }
  }
  let require = null;
  if (ctx.opts.require) {
    try {
      require = new RegExp(ctx.opts.require, "i");
    } catch (error) {
      throw usageError(`invalid --require: ${error.message}`);
    }
  }
  const result = await runAdb(ctx.adbPath, args, { serial: ctx.serial, timeoutMs: 45000 });
  if (!result.ok) throw deviceError(`logcat failed: ${firstLine(result.errText || result.text) || "unknown"}`);
  const text = result.text.replace(/\r\n/g, "\n");
  const file = ctx.opts.out
    ? path.resolve(ctx.opts.out)
    : artifactFile(ctx.artifactsRoot, "logcat", ctx.serial, "log", ctx.opts.name);
  ensureDir(path.dirname(file));
  writeArtifact(file, text);
  const allLines = text.split("\n").filter((line) => line.length > 0);
  const matched = [];
  if (grep) for (const line of allLines) if (grep.test(line)) matched.push(line);
  const required = [];
  if (require) for (const line of allLines) if (require.test(line)) required.push(line);
  const relative = path.relative(ctx.artifactsRoot, file);
  const insideArtifacts = relative && !relative.startsWith("..") && !path.isAbsolute(relative);
  const meta = insideArtifacts
    ? manifest(ctx, file, "logcat", { lines: allLines.length, matched: grep ? matched.length : undefined })
    : null;
  if (require && required.length === 0) {
    throw assertionError(`logcat does not contain required pattern: ${ctx.opts.require}`, {
      path: toDisplay(file),
      lines: allLines.length,
    });
  }
  return {
    summary: {
      path: toDisplay(file),
      bytes: text.length,
      lines: allLines.length,
      matched: grep ? matched.length : undefined,
      required: require ? required.length : undefined,
      filter: ctx.opts.grep || undefined,
    },
    json: {
      path: toDisplay(file),
      bytes: text.length,
      lines: allLines.length,
      matched: grep ? matched.length : undefined,
      required: require ? required.length : undefined,
      filter: ctx.opts.grep || undefined,
      require: ctx.opts.require || undefined,
      sample: matched.slice(0, 10),
      sha256: meta?.sha256,
    },
    lines: matched.slice(0, 5).map((line) => `LOG ${JSON.stringify(line)}`),
  };
}

async function cmdClearLogs(ctx) {
  let method = "all";
  let result = await runAdb(ctx.adbPath, ["logcat", "-b", "all", "-c"], { serial: ctx.serial, timeoutMs: 20000 });
  if (!result.ok) {
    method = "default";
    result = await runAdb(ctx.adbPath, ["logcat", "-c"], { serial: ctx.serial, timeoutMs: 20000 });
  }
  if (!result.ok) throw deviceError(`logcat clear failed: ${firstLine(result.errText || result.text)}`);
  return { summary: { cleared: true, buffer: method }, json: { cleared: true, buffer: method } };
}

const ADB_KEYBOARD_PKG = "com.android.adbkeyboard";
const ADB_KEYBOARD_IME = "com.android.adbkeyboard/.AdbIME";
const EDITABLE_CLASS = /EditText|AutoCompleteTextView|SearchView/i;

/** 设备端 sh 的单引号转义：单引号内的内容原样传递，唯一的例外是单引号本身。 */
function shellQuote(value) {
  return `'${String(value).replace(/'/g, "'\\''")}'`;
}

function isAsciiOnly(text) {
  return /^[\x20-\x7e]+$/.test(text);
}

function describeNonAscii(text) {
  const chars = [...new Set([...text].filter((ch) => !/[\x20-\x7e]/.test(ch)))];
  return chars.slice(0, 8).join("") + (chars.length > 8 ? "…" : "");
}

/**
 * ADBKeyboard（com.android.adbkeyboard）是 Android 上唯一能把非 ASCII 注入输入框的通路：
 * 它作为输入法接收 ADB_INPUT_B64 广播，再用 IME 提交。没装 / 没设为默认输入法就只能报错。
 * 本项目实测设备（Mi MIX 2S / Android 13）未安装，这条路径由文档标注为未实测。
 */
async function adbKeyboardState(adbPath, serial) {
  const [packages, ime] = await Promise.all([
    runAdb(adbPath, ["shell", `pm list packages ${ADB_KEYBOARD_PKG}`], { serial, timeoutMs: 20000 }),
    runAdb(adbPath, ["shell", "settings get secure default_input_method"], { serial, timeoutMs: 20000 }),
  ]);
  return {
    installed: packages.ok && String(packages.text).includes(`package:${ADB_KEYBOARD_PKG}`),
    active: ime.ok && String(ime.text).includes(ADB_KEYBOARD_PKG),
    currentIme: String(ime.text || "").trim(),
  };
}

async function typeAscii(ctx, value) {
  const result = await runAdb(ctx.adbPath, ["shell", `input text ${shellQuote(value)}`], {
    serial: ctx.serial,
    timeoutMs: 20000,
  });
  const message = `${result.text || ""}\n${result.errText || ""}`;
  if (!result.ok || /Exception occurred|NullPointerException|Error:/i.test(message)) {
    throw deviceError(
      `input text 被设备拒绝：${firstLine(result.errText) || firstLine(result.text) || result.error?.message || "unknown"}`,
      {
        hint: "确认输入框已聚焦（先 tap-text 点进输入框）；非 ASCII 字符不能用 input text，见 docs/tooling/android-verify.md",
        sent: value,
      },
    );
  }
}

async function verifyTypedText(ctx, text) {
  const dump = await dumpUi(ctx.adbPath, ctx.serial, { attempts: 2 });
  const parsed = parseUiXml(dump.xml);
  const editable = parsed.nodes.filter((node) => node.bounds.valid && EDITABLE_CLASS.test(node.className));
  const target = editable.find((node) => node.focused) || editable[0] || null;
  const shown = target ? target.text : "";
  if (!shown.includes(text)) {
    let landed = "";
    for (let size = 1; size <= text.length; size += 1) {
      const prefix = text.slice(0, size);
      if (!shown.includes(prefix)) break;
      landed = prefix;
    }
    const truncated = landed.length > 0 && landed.length < text.length;
    throw assertionError(
      truncated
        ? `input text 只落进前 ${landed.length}/${text.length} 个字符，实测字段 ${JSON.stringify(shown)}`
        : `input text 没有落进输入框：期望包含 ${JSON.stringify(text)}，实测 ${JSON.stringify(shown)}`,
      {
        hint: truncated
          ? "输入框有字数上限（或输入法截断）；换更短的文本，或分段打完再 dump 断言。"
          : "先 tap-text 点进输入框并等键盘出现；密码框会以 ● 回显，拿不到明文，别用 --verify。",
        focusedField: shown || null,
        editableCount: editable.length,
        landedChars: landed.length,
      },
    );
  }
  return { field: shown, editable: editable.length };
}

async function cmdInput(ctx) {
  const sub = ctx.args[0];
  if (!sub) throw usageError("input requires a subcommand: text");
  if (sub !== "text") throw usageError(`unknown input subcommand: ${sub} (supported: text)`);
  const text = ctx.args.slice(1).join(" ");
  if (!text) throw usageError("input text requires <text>");
  if (/[\u0000-\u001f\u007f]/.test(text)) {
    throw usageError("input text does not accept control characters (\\n / \\t); use key KEYCODE_ENTER / KEYCODE_TAB");
  }
  const started = Date.now();
  const ascii = isAsciiOnly(text);
  let method = "input-text";

  if (!ascii) {
    const keyboard = await adbKeyboardState(ctx.adbPath, ctx.serial);
    if (!keyboard.installed || !keyboard.active) {
      throw deviceError(
        `input text 只能注入 ASCII；文本里的 ${describeNonAscii(text)} 需要 ADBKeyboard`,
        {
          hint: keyboard.installed
            ? `ADBKeyboard 已装但当前输入法是 ${keyboard.currentIme || "unknown"}：adb shell settings put secure default_input_method ${ADB_KEYBOARD_IME}`
            : "装 ADBKeyboard（com.android.adbkeyboard）并设为默认输入法；devkit 会自动改用 ADB_INPUT_B64 广播",
          nonAscii: describeNonAscii(text),
          installed: keyboard.installed,
          active: keyboard.active,
        },
      );
    }
    method = "adbkeyboard-b64";
    const payload = Buffer.from(text, "utf8").toString("base64");
    const result = await runAdb(
      ctx.adbPath,
      ["shell", `am broadcast -a ADB_INPUT_B64 --es msg ${shellQuote(payload)}`],
      { serial: ctx.serial, timeoutMs: 20000 },
    );
    const message = `${result.text || ""}\n${result.errText || ""}`;
    if (!result.ok || /Exception|Error:/i.test(message)) {
      throw deviceError(`ADBKeyboard 广播失败：${firstLine(result.errText) || firstLine(result.text) || "unknown"}`, {
        hint: "确认 ADBKeyboard 仍被设为默认输入法，且当前有聚焦的输入框",
      });
    }
  } else {
    // input text 会把字面量 "%s" 换成空格，所以按 "%s" 切开，单独发 "%" + "s"
    for (const chunk of text.split(/(%s)/)) {
      if (!chunk) continue;
      if (chunk === "%s") {
        await typeAscii(ctx, "%");
        await typeAscii(ctx, "s");
      } else {
        await typeAscii(ctx, chunk);
      }
    }
  }

  const verified = ctx.opts.verify ? await verifyTypedText(ctx, text) : null;
  const elapsedMs = Date.now() - started;
  return {
    summary: {
      chars: [...text].length,
      method,
      text,
      verified: verified ? true : undefined,
      field: verified?.field,
      elapsedMs,
    },
    json: {
      text,
      chars: [...text].length,
      method,
      nonAscii: !ascii,
      verified: Boolean(verified),
      field: verified?.field ?? null,
      editable: verified?.editable ?? null,
      elapsedMs,
    },
  };
}

async function cmdKey(ctx) {
  const code = ctx.args[0];
  if (!code) throw usageError("key requires a key code, e.g. KEYCODE_BACK");
  if (!/^[A-Za-z0-9_]+$/.test(code)) throw usageError(`invalid key code: ${code}`);
  const result = await runAdb(ctx.adbPath, ["shell", `input keyevent ${code}`], {
    serial: ctx.serial,
    timeoutMs: 15000,
  });
  if (!result.ok || /error/i.test(result.errText)) {
    throw deviceError(`input keyevent failed: ${firstLine(result.errText || result.text) || "unknown"}`);
  }
  return { summary: { key: code }, json: { key: code } };
}

async function cmdSwipe(ctx) {
  const values = ctx.args.slice(0, 4).map(Number);
  if (values.length < 4 || values.some((value) => !Number.isFinite(value))) {
    throw usageError("swipe requires <x1> <y1> <x2> <y2> [durationMs]");
  }
  const duration = ctx.args[4] === undefined ? 300 : Number(ctx.args[4]);
  if (!Number.isFinite(duration) || duration < 50 || duration > 10000) {
    throw usageError("swipe duration must be in [50, 10000] ms");
  }
  const command = `input swipe ${values.map((value) => Math.round(value)).join(" ")} ${Math.round(duration)}`;
  const result = await runAdb(ctx.adbPath, ["shell", command], { serial: ctx.serial, timeoutMs: 20000 });
  if (!result.ok) throw deviceError(`input swipe failed: ${firstLine(result.errText || result.text)}`);
  return {
    summary: { from: `${values[0]},${values[1]}`, to: `${values[2]},${values[3]}`, durationMs: Math.round(duration) },
    json: { from: values.slice(0, 2), to: values.slice(2, 4), durationMs: Math.round(duration) },
  };
}

async function cmdRecord(ctx) {
  const seconds = intOption(ctx.opts.seconds, "seconds", 10, { min: 1, max: 180 });
  const bitrate = ctx.opts.bitrate || "8000000";
  if (!/^\d+$/.test(String(bitrate)) && !/^\d+[kKmM]$/.test(String(bitrate))) {
    throw usageError("--bitrate must look like 8000000 or 8M");
  }
  const remote = `/sdcard/devkit-record-${process.pid}-${Date.now()}.mp4`;
  const command = `screenrecord --time-limit ${seconds} --bit-rate ${bitrate} ${remote}`;
  const result = await runAdb(ctx.adbPath, ["shell", command], {
    serial: ctx.serial,
    timeoutMs: seconds * 1000 + 30000,
  });
  if (!result.ok) {
    await runAdb(ctx.adbPath, ["shell", `rm -f ${remote}`], { serial: ctx.serial, timeoutMs: 8000 }).catch(() => {});
    throw deviceError(`screenrecord failed: ${firstLine(result.errText || result.text) || "unknown"}`);
  }
  const cat = await runAdb(ctx.adbPath, ["exec-out", "cat", remote], { serial: ctx.serial, timeoutMs: 120000 });
  await runAdb(ctx.adbPath, ["shell", `rm -f ${remote}`], { serial: ctx.serial, timeoutMs: 8000 }).catch(() => {});
  if (!cat.ok || !isMp4(cat.stdout)) {
    throw deviceError("screenrecord produced no valid mp4 (screen off or encoder busy?)");
  }
  const file = artifactFile(ctx.artifactsRoot, "videos", ctx.serial, "mp4", ctx.opts.name);
  writeArtifact(file, cat.stdout);
  const meta = manifest(ctx, file, "video", { seconds });
  return {
    summary: { path: toDisplay(file), bytes: cat.stdout.length, seconds },
    json: { path: toDisplay(file), bytes: cat.stdout.length, seconds, sha256: meta?.sha256 },
  };
}

async function cmdInstall(ctx) {
  const apkArg = ctx.args[0];
  if (!apkArg) throw usageError("install requires <apk>");
  const apk = path.resolve(apkArg);
  let stat;
  try {
    stat = fs.statSync(apk);
  } catch {
    throw ioError(`APK not found: ${apk}`);
  }
  if (!stat.isFile()) throw ioError(`APK path is not a file: ${apk}`);

  const args = ["install", "-r", "-t"];
  if (!ctx.opts["no-grant"]) args.push("-g");
  if (ctx.opts.downgrade) args.push("-d");
  args.push(apk);

  const before = await pmListPackages(ctx.adbPath, ctx.serial).catch(() => null);
  const started = Date.now();
  const result = await runAdb(ctx.adbPath, args, { serial: ctx.serial, timeoutMs: 300000 });
  const output = `${result.text}\n${result.errText}`;
  if (!result.ok || !/Success/i.test(output)) {
    const detail = output.split(/\r?\n/).map((line) => line.trim()).find((line) => /Failure|Error|INSTALL_FAILED/i.test(line));
    throw deviceError(`adb install failed: ${detail || firstLine(output) || "unknown"}`);
  }
  const after = await pmListPackages(ctx.adbPath, ctx.serial).catch(() => null);
  const added = before && after ? [...after].filter((pkg) => !before.has(pkg)) : [];
  return {
    summary: {
      apk: toDisplay(apk),
      bytes: stat.size,
      package: added.length === 1 ? added[0] : undefined,
      newPackages: added.length > 1 ? added.join(",") : undefined,
      durationMs: Date.now() - started,
    },
    json: {
      apk: toDisplay(apk),
      bytes: stat.size,
      package: added.length === 1 ? added[0] : null,
      newPackages: added,
      durationMs: Date.now() - started,
    },
  };
}

function componentFor(pkg, activityArg) {
  const activityText = String(activityArg);
  if (activityText.includes("/")) return activityText;
  if (activityText.startsWith(pkg + ".")) return `${pkg}/${activityText}`;
  return `${pkg}/${activityText.startsWith(".") ? activityText : "." + activityText}`;
}

/**
 * --extra / --es 收 key=value，转成 am 的 --es。只支持字符串 extra：
 * Intent extra 的类型由 am 的参数决定（--es 字符串 / --ei 整数...），本项目目前只需要字符串。
 */
function parseExtras(values) {
  const extras = [];
  for (const raw of values) {
    const text = String(raw);
    const at = text.indexOf("=");
    if (at <= 0) throw usageError(`--extra expects key=value, got: ${text}`);
    const key = text.slice(0, at);
    const value = text.slice(at + 1);
    if (!/^[A-Za-z0-9_.-]+$/.test(key)) throw usageError(`--extra key has invalid characters: ${key}`);
    if (value.startsWith("-")) throw usageError(`--extra ${key}: value must not start with "-" (am would read it as an option)`);
    extras.push({ key, value });
  }
  return extras;
}

/** 只给 --extra、不给 activity 时，用系统解析出 launcher activity，否则 am 无法收 extra。 */
async function resolveLauncherComponent(adbPath, serial, pkg) {
  const result = await runAdb(
    adbPath,
    ["shell", `cmd package resolve-activity --brief -c android.intent.category.LAUNCHER ${pkg}`],
    { serial, timeoutMs: 20000 },
  );
  if (!result.ok) return null;
  const lines = String(result.text).split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  return lines.reverse().find((line) => line.startsWith(`${pkg}/`)) || null;
}

async function cmdLaunch(ctx) {
  const pkg = ctx.args[0];
  if (!pkg) throw usageError("launch requires a package name");
  if (!/^[A-Za-z0-9_.]+$/.test(pkg)) throw usageError(`invalid package name: ${pkg}`);
  const timeout = intOption(ctx.opts.timeout, "timeout", 8000, { min: 500, max: 60000 });
  const extras = parseExtras([...(ctx.opts.extra || []), ...(ctx.opts.es || [])]);
  const started = Date.now();

  // 实测 Android 13：进程还在前台时 am start 只把 task 提到最前（LaunchState: HOT），
  // onCreate 不会再跑，extra 被吞掉。所以带 extra 的启动一律先 force-stop，保证是新 intent。
  const fresh = Boolean(ctx.opts.fresh) || extras.length > 0;
  if (fresh) {
    await runAdb(ctx.adbPath, ["shell", `am force-stop ${pkg}`], { serial: ctx.serial, timeoutMs: 15000 }).catch(() => {});
    await sleep(300);
  }

  const activityArg = ctx.opts.activity || ctx.args[1];
  let component = null;
  let method = "monkey";
  if (activityArg || extras.length) {
    method = "am";
    component = activityArg
      ? componentFor(pkg, activityArg)
      : await resolveLauncherComponent(ctx.adbPath, ctx.serial, pkg);
    if (!component) {
      throw assertionError(`cannot resolve a launcher activity for ${pkg}`, {
        hint: "pass an explicit activity: devkit launch <pkg> <activity> --extra k=v",
      });
    }
    const extraArgs = extras.map((extra) => ` --es ${extra.key} ${shellQuote(extra.value)}`).join("");
    const result = await runAdb(ctx.adbPath, ["shell", `am start -W -n ${component}${extraArgs}`], {
      serial: ctx.serial,
      timeoutMs: 30000,
    });
    const output = `${result.text}\n${result.errText}`;
    if (!result.ok || /Error|Exception|does not exist/i.test(output)) {
      throw deviceError(`am start failed: ${firstLine(output) || "unknown"}`);
    }
    // 普通 launch 撞上"task 已在前台"是正常的（只提到最前）；带 extra 时才说明 intent 没送达。
    if (extras.length && /Activity not started/i.test(output)) {
      throw deviceError(`am start did not deliver a new intent to ${component}`, {
        hint: "该 task 已在前台，extra 会被丢弃；加 --fresh 重来",
        output: firstLine(output),
      });
    }
  } else {
    const result = await runAdb(
      ctx.adbPath,
      ["shell", "monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1"],
      { serial: ctx.serial, timeoutMs: 20000 },
    );
    const output = `${result.text}\n${result.errText}`;
    if (!result.ok || /No activities found|monkey aborted/i.test(output)) {
      throw assertionError(`cannot launch ${pkg}: no launcher activity`, {
        hint: "pass an explicit activity: devkit launch <pkg> <activity>",
      });
    }
  }

  let focus = null;
  const deadline = Date.now() + timeout;
  while (true) {
    focus = (await getFocus(ctx.adbPath, ctx.serial).catch(() => null))
      || (await getTopActivity(ctx.adbPath, ctx.serial).catch(() => null));
    if (focus && focus.package === pkg) break;
    if (Date.now() >= deadline) break;
    await sleep(300);
  }
  if (!focus || focus.package !== pkg) {
    throw assertionError(`launch did not bring ${pkg} to foreground within ${timeout}ms`, {
      lastFocus: focus ? `${focus.package}/${focus.activity}` : null,
    });
  }

  return {
    summary: {
      package: pkg,
      activity: focus.activity || undefined,
      component: component || undefined,
      method,
      extras: extras.length ? extras.map((extra) => `${extra.key}=${extra.value}`).join(",") : undefined,
      fresh: fresh ? true : undefined,
      elapsedMs: Date.now() - started,
    },
    json: {
      package: pkg,
      activity: focus.activity || null,
      component,
      method,
      extras: extras.map((extra) => `${extra.key}=${extra.value}`),
      fresh,
      elapsedMs: Date.now() - started,
      focus: focus.raw,
    },
  };
}

async function outputDevices(adbPath, devices, opts) {
  const online = devices.filter((device) => device.state === "device");
  const details = new Map();
  await Promise.all(online.map(async (device) => {
    try {
      details.set(device.serial, await getDeviceInfo(adbPath, device.serial));
    } catch (error) {
      details.set(device.serial, { error: error.message });
    }
  }));
  const rows = devices.map((device) => ({
    serial: device.serial,
    state: device.state,
    ...(details.get(device.serial) || {}),
  }));
  if (opts.json) {
    for (const row of rows) print(JSON.stringify({ ok: true, cmd: "devices", ...row }));
    print(JSON.stringify({
      ok: true,
      cmd: "devices",
      summary: true,
      total: devices.length,
      online: online.length,
      offline: devices.length - online.length,
    }));
  } else {
    for (const row of rows) print(`DEVICE ${formatKv(row)}`);
    print(`SUMMARY ${formatKv({ total: devices.length, online: online.length, offline: devices.length - online.length })}`);
  }
  return 0;
}

async function outputDoctor(adbPath, opts) {
  let adbInfo = null;
  let adbError = null;
  try {
    adbInfo = await adbVersion(adbPath);
  } catch (error) {
    adbError = error.message;
  }
  let devices = [];
  let devicesError = null;
  try {
    devices = await listDevices(adbPath);
  } catch (error) {
    devicesError = error.message;
  }
  const online = devices.filter((device) => device.state === "device").length;
  const row = {
    devkit: VERSION,
    node: process.version,
    platform: `${process.platform} ${process.arch}`,
    adb: toDisplay(adbPath),
    source: resolveAdb().source,
    adbVersion: adbInfo || undefined,
    artifacts: toDisplay(resolveArtifactsRoot(opts)),
    devices: devices.length,
    online,
  };
  const code = adbError || devicesError ? 3 : 0;
  if (opts.json) {
    print(JSON.stringify({
      ok: code === 0,
      cmd: "doctor",
      ...row,
      ...(code === 0 ? {} : { code, error: adbError || devicesError }),
    }));
  } else if (code !== 0) {
    print(`ERR doctor ${formatKv({ adb: toDisplay(adbPath), code: 3, error: adbError || devicesError })}`);
  } else {
    print(`DOCTOR ${formatKv(row)}`);
  }
  return code;
}

async function resolveTargets(adbPath, devices, opts, command) {
  const online = devices.filter((device) => device.state === "device");
  if (opts.all) {
    if (!online.length) throw deviceError("no online devices");
    return online.map((device) => device.serial);
  }
  if (opts.serials.length) {
    if (!opts.serials.length) throw usageError("at least one serial required");
    return [...new Set(opts.serials)];
  }
  if (!online.length) throw deviceError("no online devices");
  if (online.length === 1) return [online[0].serial];
  if (MULTI_SAFE.has(command)) return online.map((device) => device.serial);
  throw usageError(
    `multiple devices online (${online.map((device) => device.serial).join(", ")}); use -s <serial> or --all`,
  );
}

function normalizeError(error) {
  if (error instanceof DevkitError) return error;
  return new DevkitError(error?.message || String(error), { code: 1, kind: "internal" });
}

function emitOk(command, serial, result, opts) {
  if (opts.json) {
    print(JSON.stringify({ ok: true, cmd: command, serial, ...result.json }));
    return;
  }
  print(`OK ${command} ${formatKv({ serial, ...result.summary })}`);
  for (const line of result.lines || []) print(line);
}

function emitError(command, serial, error, opts) {
  if (opts.json) {
    print(JSON.stringify({
      ok: false,
      cmd: command,
      serial,
      code: error.exitCode,
      kind: error.kind,
      error: error.message,
      ...(error.details ? { details: error.details } : {}),
    }));
    return;
  }
  print(`ERR ${command} ${formatKv({ serial, code: error.exitCode, kind: error.kind, error: error.message })}`);
  if (error.details?.hint) print(`HINT ${error.details.hint}`);
  else if (error.hint) print(`HINT ${error.hint}`);
}

const SEVERITY = { 2: 5, 3: 4, 4: 3, 1: 2 };

async function main(argv) {
  let parsed;
  try {
    parsed = parseArgs(argv);
  } catch (error) {
    const normalized = normalizeError(error);
    emitError("?", undefined, normalized, { json: false });
    return normalized.exitCode;
  }
  const { opts, positionals } = parsed;
  const command = positionals.shift();

  if (opts.version) {
    print(`devkit ${VERSION}`);
    return 0;
  }
  if (opts.help || !command || command === "help") {
    print(usage());
    return 0;
  }

  const adb = resolveAdb();
  const adbPath = adb.path;

  if (command === "doctor") return outputDoctor(adbPath, opts);

  let devices;
  try {
    devices = await listDevices(adbPath);
  } catch (error) {
    const normalized = normalizeError(error);
    emitError(command, undefined, normalized, opts);
    return normalized.exitCode;
  }

  if (command === "devices") return outputDevices(adbPath, devices, opts);

  if (!HANDLERS[command]) {
    const error = usageError(`unknown command: ${command}`);
    emitError(command, undefined, error, opts);
    return error.exitCode;
  }

  let targets;
  try {
    targets = await resolveTargets(adbPath, devices, opts, command);
  } catch (error) {
    const normalized = normalizeError(error);
    emitError(command, undefined, normalized, opts);
    return normalized.exitCode;
  }

  const artifactsRoot = resolveArtifactsRoot(opts);
  let worst = 0;
  for (const serial of targets) {
    const device = devices.find((item) => item.serial === serial);
    try {
      if (!device) throw deviceError(`device not connected: ${serial}`);
      if (device.state !== "device") throw deviceError(`device ${serial} is ${device.state}`);
      const ctx = {
        command,
        serial,
        args: positionals,
        opts,
        adbPath,
        artifactsRoot,
        manifestEnabled: !opts["no-manifest"],
      };
      const result = await HANDLERS[command](ctx);
      emitOk(command, serial, result, opts);
    } catch (error) {
      const normalized = normalizeError(error);
      emitError(command, serial, normalized, opts);
      if ((SEVERITY[normalized.exitCode] || 1) > (SEVERITY[worst] || 0)) worst = normalized.exitCode;
    }
  }
  return worst;
}

main(process.argv.slice(2))
  .then((code) => { process.exitCode = code; })
  .catch((error) => {
    process.stderr.write(`devkit fatal: ${error?.stack || error}\n`);
    process.exitCode = 2;
  });
