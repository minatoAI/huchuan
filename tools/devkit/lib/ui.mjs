import { DevkitError, firstLine } from "./errors.mjs";
import { runAdb, sleep } from "./adb.mjs";

const REMOTE_DUMP = (suffix) => `/sdcard/devkit-dump-${suffix}.xml`;

/**
 * 取一次 UI 层级。
 *
 * 关键点（真机踩坑）：
 *   1. 每次先 rm 远端文件。uiautomator 失败时不会写文件，若不删就会 cat 到上一次的脏 XML。
 *   2. uiautomator 在 UI 不 idle 时输出 "ERROR: could not get idle state." 且 exit code 仍是 0，
 *      所以不能只看退出码，必须校验拿到的 XML。
 *   3. 失败重试；仍失败就明确报错，绝不返回旧数据。
 */
export async function dumpUi(adbPath, serial, { attempts = 3, delayMs = 500, timeoutMs = 30000 } = {}) {
  const remote = REMOTE_DUMP(`${process.pid}-${Date.now()}`);
  const started = Date.now();
  let lastError = null;
  try {
    for (let attempt = 1; attempt <= attempts; attempt += 1) {
      await runAdb(adbPath, ["shell", `rm -f ${remote}`], { serial, timeoutMs: 8000 });
      const dump = await runAdb(adbPath, ["shell", `uiautomator dump ${remote}`], { serial, timeoutMs });
      const message = (dump.errText || "") + (dump.text || "");
      const idleFailure = /could not get idle state/i.test(message);
      const cat = await runAdb(adbPath, ["exec-out", "cat", remote], { serial, timeoutMs: 15000 });
      if (cat.ok && cat.text.includes("<hierarchy")) {
        return { xml: cat.text, attempts: attempt, elapsedMs: Date.now() - started };
      }
      lastError = new DevkitError(
        idleFailure
          ? "uiautomator dump timed out waiting for idle state"
          : `uiautomator dump failed: ${firstLine(message) || "no dump file produced"}`,
        {
          code: 3,
          kind: "uiautomator",
          hint: idleFailure
            ? "该页面持续动画/加载，uiautomator 无法 idle。改用 Espresso/Compose 测试，或避开该页面；devkit 不会退回旧 XML。"
            : undefined,
        },
      );
      if (attempt < attempts) await sleep(delayMs);
    }
    throw lastError || new DevkitError("uiautomator dump failed", { code: 3, kind: "uiautomator" });
  } finally {
    await runAdb(adbPath, ["shell", `rm -f ${remote}`], { serial, timeoutMs: 8000 }).catch(() => {});
  }
}

export function decodeXml(value) {
  return String(value)
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&amp;/g, "&");
}

export function parseBounds(value) {
  const match = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(String(value || ""));
  if (!match) return { x1: 0, y1: 0, x2: 0, y2: 0, width: 0, height: 0, valid: false };
  const [x1, y1, x2, y2] = match.slice(1).map(Number);
  const width = x2 - x1;
  const height = y2 - y1;
  return { x1, y1, x2, y2, width, height, valid: width > 0 && height > 0 };
}

export function centerOf(bounds) {
  return {
    x: Math.max(0, Math.round((bounds.x1 + bounds.x2) / 2)),
    y: Math.max(0, Math.round((bounds.y1 + bounds.y2) / 2)),
  };
}

/**
 * 解析 uiautomator XML。
 * uiautomator 的节点属性是扁平 attribute，文本里的特殊字符已实体化，所以用一个
 * 轻量标签扫描 + 属性正则即可；不引 XML 依赖，避免安装成本。
 */
export function parseUiXml(xml) {
  const nodes = [];
  const stack = [];
  const tagRe = /<node\b([^>]*)>|<\/node>/g;
  let match;
  while ((match = tagRe.exec(xml))) {
    if (match[0] === "</node>") {
      stack.pop();
      continue;
    }
    let attrsText = match[1];
    const selfClosing = /\/\s*$/.test(attrsText);
    if (selfClosing) attrsText = attrsText.replace(/\/\s*$/, "");

    const attrs = {};
    // uiautomator 遇到含双引号的文本会改用单引号包属性值（text='say "hi"'），两种都要认。
    const attrRe = /([A-Za-z_:][-A-Za-z0-9_:.]*)=(?:"([^"]*)"|'([^']*)')/g;
    let attr;
    while ((attr = attrRe.exec(attrsText))) {
      attrs[attr[1]] = decodeXml(attr[2] !== undefined ? attr[2] : attr[3]);
    }

    const bounds = parseBounds(attrs.bounds);
    const node = {
      index: Number(attrs.index || 0),
      text: attrs.text || "",
      desc: attrs["content-desc"] || "",
      hint: attrs.hint || "",
      resourceId: attrs["resource-id"] || "",
      className: attrs.class || "",
      package: attrs.package || "",
      clickable: attrs.clickable === "true",
      enabled: attrs.enabled !== "false",
      focusable: attrs.focusable === "true",
      focused: attrs.focused === "true",
      scrollable: attrs.scrollable === "true",
      bounds,
      depth: stack.length,
      parent: stack[stack.length - 1] || null,
    };
    nodes.push(node);
    if (!selfClosing) stack.push(node);
  }

  const rotationMatch = /<hierarchy[^>]*rotation="(-?\d+)"/.exec(xml);
  return {
    nodes,
    rotation: rotationMatch ? Number(rotationMatch[1]) : 0,
    packages: [...new Set(nodes.map((node) => node.package).filter(Boolean))],
  };
}

function normalize(value) {
  return String(value || "").trim().toLowerCase();
}

/**
 * 文本匹配。默认 substring（不区分大小写），--exact 精确，--regex 正则。
 * 只接受可点击区域（bounds 有效）的节点，避免点 (0,0)。
 * 排序：精确命中优先，其余保持文档顺序。
 */
export function matchNodes(nodes, needle, { exact = false, regex = false, fields = ["text", "desc", "hint"] } = {}) {
  let test;
  let exactTest = null;
  if (regex) {
    const re = new RegExp(needle, "i");
    test = (value) => re.test(value);
  } else if (exact) {
    const target = normalize(needle);
    test = (value) => normalize(value) === target;
    exactTest = test;
  } else {
    const target = normalize(needle);
    test = (value) => normalize(value).includes(target);
    exactTest = (value) => normalize(value) === target;
  }

  const matches = [];
  for (const node of nodes) {
    if (!node.bounds.valid) continue;
    const values = fields.map((field) => node[field]).filter(Boolean);
    if (!values.length) continue;
    if (!values.some((value) => test(value))) continue;
    const matchedField = fields.find((field) => node[field] && test(node[field])) || "text";
    const isExact = exactTest ? values.some((value) => exactTest(value)) : false;
    matches.push({ node, exact: isExact, field: matchedField });
  }
  // 精确命中优先；同等精确度下优先 text（真正显示的文字），再到 content-desc / hint。
  const rank = (match) => (match.exact ? 0 : 10) + (match.field === "text" ? 0 : match.field === "desc" ? 1 : 2);
  matches.sort((a, b) => rank(a) - rank(b));
  return matches;
}

export function collectTexts(nodes) {
  const seen = new Set();
  const texts = [];
  for (const node of nodes) {
    for (const value of [node.text, node.desc]) {
      const trimmed = String(value || "").trim();
      if (!trimmed || seen.has(trimmed)) continue;
      seen.add(trimmed);
      texts.push(trimmed);
    }
  }
  return texts;
}
