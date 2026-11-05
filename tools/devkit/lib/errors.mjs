export class DevkitError extends Error {
  constructor(message, { code = 1, kind = "error", hint, details } = {}) {
    super(message);
    this.name = "DevkitError";
    this.exitCode = code;
    this.kind = kind;
    this.hint = hint;
    this.details = details;
  }
}

export function firstLine(text) {
  return String(text || "")
    .split(/\r?\n/)
    .map((line) => line.trim())
    .find(Boolean) || "";
}
