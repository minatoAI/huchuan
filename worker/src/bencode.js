/**
 * 最小 bencode 编解码。
 * 编码器认 number / string / Uint8Array / Array / 普通对象（键为 ASCII，按字节序排序）；
 * 解码器把字节串还原成 Uint8Array（info_hash、compact peers 都是二进制，不能当文本解）。
 */

const encoder = new TextEncoder();

function utf8(str) {
  return encoder.encode(str);
}

function concat(chunks) {
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let off = 0;
  for (const c of chunks) {
    out.set(c, off);
    off += c.length;
  }
  return out;
}

function encodeInto(value, out) {
  if (typeof value === "number") {
    if (!Number.isInteger(value)) throw new TypeError("bencode: only integers are supported");
    out.push(utf8("i" + value + "e"));
    return;
  }
  if (typeof value === "string") {
    const bytes = utf8(value);
    out.push(utf8(bytes.length + ":"), bytes);
    return;
  }
  if (value instanceof Uint8Array) {
    out.push(utf8(value.length + ":"), value);
    return;
  }
  if (Array.isArray(value)) {
    out.push(utf8("l"));
    for (const item of value) encodeInto(item, out);
    out.push(utf8("e"));
    return;
  }
  if (value && typeof value === "object") {
    const keys = Object.keys(value)
      .filter((k) => value[k] !== undefined)
      .sort();
    out.push(utf8("d"));
    for (const key of keys) {
      encodeInto(key, out);
      encodeInto(value[key], out);
    }
    out.push(utf8("e"));
    return;
  }
  throw new TypeError("bencode: unsupported value " + String(value));
}

export function bencode(value) {
  const out = [];
  encodeInto(value, out);
  return concat(out);
}

/** 解码；字符串一律给 Uint8Array，需要文本的地方自己 decode。 */
export function bdecode(bytes) {
  const buf = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  const [value, offset] = decodeAt(buf, 0);
  if (offset !== buf.length) throw new TypeError("bencode: trailing bytes");
  return value;
}

function decodeAt(buf, start) {
  const head = buf[start];
  if (head === 0x69) {
    // i<n>e
    let end = start + 1;
    while (end < buf.length && buf[end] !== 0x65) end += 1;
    if (end >= buf.length) throw new TypeError("bencode: unterminated integer");
    const text = new TextDecoder().decode(buf.subarray(start + 1, end));
    if (!/^-?\d+$/.test(text)) throw new TypeError("bencode: bad integer");
    return [Number(text), end + 1];
  }
  if (head === 0x6c) {
    // l...e
    const items = [];
    let offset = start + 1;
    while (offset < buf.length && buf[offset] !== 0x65) {
      const [item, next] = decodeAt(buf, offset);
      items.push(item);
      offset = next;
    }
    if (offset >= buf.length) throw new TypeError("bencode: unterminated list");
    return [items, offset + 1];
  }
  if (head === 0x64) {
    // d...e
    const dict = {};
    let offset = start + 1;
    while (offset < buf.length && buf[offset] !== 0x65) {
      const [keyBytes, nextKey] = decodeAt(buf, offset);
      if (!(keyBytes instanceof Uint8Array)) throw new TypeError("bencode: dict key must be a string");
      const [value, nextValue] = decodeAt(buf, nextKey);
      dict[new TextDecoder().decode(keyBytes)] = value;
      offset = nextValue;
    }
    if (offset >= buf.length) throw new TypeError("bencode: unterminated dict");
    return [dict, offset + 1];
  }
  if (head >= 0x30 && head <= 0x39) {
    // <len>:<bytes>
    let end = start;
    while (end < buf.length && buf[end] !== 0x3a) end += 1;
    if (end >= buf.length) throw new TypeError("bencode: unterminated string length");
    const text = new TextDecoder().decode(buf.subarray(start, end));
    if (!/^\d+$/.test(text)) throw new TypeError("bencode: bad string length");
    const length = Number(text);
    const from = end + 1;
    const to = from + length;
    if (to > buf.length) throw new TypeError("bencode: string overruns buffer");
    return [buf.subarray(from, to), to];
  }
  throw new TypeError("bencode: unexpected byte 0x" + head.toString(16));
}
