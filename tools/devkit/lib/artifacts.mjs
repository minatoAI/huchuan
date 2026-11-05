import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";

export function ensureDir(dir) {
  fs.mkdirSync(dir, { recursive: true });
}

export function stamp(date = new Date()) {
  const pad = (value, size = 2) => String(value).padStart(size, "0");
  return [
    date.getFullYear(),
    pad(date.getMonth() + 1),
    pad(date.getDate()),
    "-",
    pad(date.getHours()),
    pad(date.getMinutes()),
    pad(date.getSeconds()),
    "-",
    pad(date.getMilliseconds(), 3),
  ].join("");
}

export function sanitizeName(value, fallback = "item") {
  const text = String(value ?? "")
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
  return text || fallback;
}

export function sanitizeSerial(value) {
  return String(value).replace(/[^A-Za-z0-9._-]+/g, "_") || "unknown";
}

export function artifactFile(root, kind, serial, extension, name) {
  const dir = path.join(root, kind, sanitizeSerial(serial));
  ensureDir(dir);
  const suffix = name ? `-${sanitizeName(name)}` : "";
  return path.join(dir, `${stamp()}${suffix}.${extension}`);
}

export function toDisplay(filePath) {
  return String(filePath).replace(/\\/g, "/");
}

export function sha256(file) {
  return crypto.createHash("sha256").update(fs.readFileSync(file)).digest("hex");
}

export function recordArtifact({ root, file, cmd, serial, kind, extra = {} }) {
  ensureDir(root);
  const stat = fs.statSync(file);
  const entry = {
    ts: new Date().toISOString(),
    cmd,
    kind,
    serial,
    path: path.relative(root, file).split(path.sep).join("/"),
    bytes: stat.size,
    sha256: sha256(file),
    ...extra,
  };
  fs.appendFileSync(path.join(root, "manifest.jsonl"), JSON.stringify(entry) + "\n", "utf8");
  return entry;
}
