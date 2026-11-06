'use strict';

/**
 * 互传 Windows 壳 · 本地二维码编码器（零依赖、零网络）。
 *
 * 只做编码，不做解码：把 daemon 给的 qrText（就是取件链接）编成模块矩阵，页面再用 SVG 画出来。
 * 接收方用系统相机或浏览器扫 —— 与 Android 的 zxing 出 BitMatrix、Compose Canvas 自己画是同一个口径
 * （docs/design/link-share.md §4.2），不调用任何在线二维码服务。
 *
 * 口径：
 *   - 字节模式（UTF-8），纠错等级 M（同 Android 的 ErrorCorrectionLevel.M）；
 *   - 版本 1–10 里自动挑最小的够用版本（链接约 59 字节 → 版本 4，与设计里「版本 3–4」一致）；
 *   - 掩码按 ISO/IEC 18004 的罚分挑最优；格式信息 / 版本信息按标准写。
 *
 * 为什么不引第三方库：这里要编的是一条约 60 字节的固定字符串，为它多一个构建依赖与一份
 * 第三方许可不划算；算法本身是标准的，约 300 行能写全，可读、可审、可离线验证。
 *
 * 公开接口：
 *   QRCode.encode(text) → { version, size, get(x, y) } | null   get 返回 true = 深色模块
 *   QRCode.svg(text, quiet) → SVG 字符串 | ''                   quiet 是静区模块数，默认 4
 */
const QRCode = (function () {
  // 纠错等级 M 的块结构：版本 → [每块纠错码字数, [块数, 每块数据码字数]...]。
  // 总码字数 = 块数 × (数据 + 纠错)，与版本容量表一致（ISO/IEC 18004 表 9）。
  const BLOCKS = {
    1: [10, [[1, 16]]],
    2: [16, [[1, 28]]],
    3: [26, [[1, 44]]],
    4: [18, [[2, 32]]],
    5: [24, [[2, 43]]],
    6: [16, [[4, 27]]],
    7: [18, [[4, 31]]],
    8: [22, [[2, 38], [2, 39]]],
    9: [22, [[3, 36], [2, 37]]],
    10: [26, [[4, 43], [1, 44]]],
  };

  // 对齐图形中心坐标（版本 1 没有）。
  const ALIGN = {
    1: [],
    2: [6, 18],
    3: [6, 22],
    4: [6, 26],
    5: [6, 30],
    6: [6, 34],
    7: [6, 22, 38],
    8: [6, 24, 42],
    9: [6, 26, 46],
    10: [6, 28, 50],
  };

  // 填完码字后剩下的填充位数（黑白无所谓，标准要求 0）。
  const REMAINDER = { 1: 0, 2: 7, 3: 7, 4: 7, 5: 7, 6: 7, 7: 0, 8: 0, 9: 0, 10: 0 };

  /* ------------------------------------------------------------ GF(256) 与 RS */
  const EXP = new Uint8Array(512);
  const LOG = new Uint8Array(256);
  (function buildTables() {
    let x = 1;
    for (let i = 0; i < 255; i += 1) {
      EXP[i] = x;
      LOG[x] = i;
      x <<= 1;
      if (x & 0x100) x ^= 0x11d;
    }
    for (let i = 255; i < 512; i += 1) EXP[i] = EXP[i - 255];
  })();

  function mul(a, b) {
    if (a === 0 || b === 0) return 0;
    return EXP[LOG[a] + LOG[b]];
  }

  /** 生成多项式 ∏(x − α^i)，首项系数固定是 1。 */
  function generatorPoly(degree) {
    let poly = [1];
    for (let i = 0; i < degree; i += 1) {
      const next = new Array(poly.length + 1).fill(0);
      for (let j = 0; j < poly.length; j += 1) {
        next[j] ^= poly[j];
        next[j + 1] ^= mul(poly[j], EXP[i]);
      }
      poly = next;
    }
    return poly;
  }

  /** 一个数据块的纠错码字：GF(256) 多项式取模，余数就是要追加的 ec 段。 */
  function errorCodewords(data, degree) {
    const gen = generatorPoly(degree);
    const work = new Uint8Array(data.length + degree);
    work.set(data, 0);
    for (let i = 0; i < data.length; i += 1) {
      const factor = work[i];
      if (factor === 0) continue;
      for (let j = 0; j < gen.length; j += 1) work[i + j] ^= mul(gen[j], factor);
    }
    return work.slice(data.length);
  }

  /* -------------------------------------------------------------- 码字布局 */
  function dataCodewords(version) {
    return BLOCKS[version][1].reduce(function (sum, group) {
      return sum + group[0] * group[1];
    }, 0);
  }

  /** 该版本能装多少个字节（扣掉模式 4 bit + 字符计数）。 */
  function capacity(version) {
    const countBits = version < 10 ? 8 : 16;
    return Math.floor((dataCodewords(version) * 8 - 4 - countBits) / 8);
  }

  function pickVersion(length) {
    for (let version = 1; version <= 10; version += 1) {
      if (length <= capacity(version)) return version;
    }
    return 0;
  }

  function makeDataCodewords(bytes, version) {
    const total = dataCodewords(version);
    const countBits = version < 10 ? 8 : 16;
    const bits = [];
    function push(value, width) {
      for (let i = width - 1; i >= 0; i -= 1) bits.push((value >>> i) & 1);
    }
    push(4, 4);                       // 字节模式 0100
    push(bytes.length, countBits);
    for (let i = 0; i < bytes.length; i += 1) push(bytes[i], 8);
    const limit = total * 8;
    for (let i = 0; i < 4 && bits.length < limit; i += 1) bits.push(0);   // 终止符
    while (bits.length % 8 !== 0) bits.push(0);                           // 补到整字节
    const words = [];
    for (let i = 0; i < bits.length; i += 8) {
      let value = 0;
      for (let j = 0; j < 8; j += 1) value = (value << 1) | bits[i + j];
      words.push(value);
    }
    // 填充码字固定 0xEC / 0x11 交替。
    let pad = 0xec;
    while (words.length < total) {
      words.push(pad);
      pad = pad === 0xec ? 0x11 : 0xec;
    }
    return words;
  }

  /** 分块 → 各自算纠错 → 数据列优先、纠错列优先地交错。 */
  function interleave(words, version) {
    const entry = BLOCKS[version];
    const ecLength = entry[0];
    const blocks = [];
    let offset = 0;
    for (let g = 0; g < entry[1].length; g += 1) {
      const count = entry[1][g][0];
      const length = entry[1][g][1];
      for (let n = 0; n < count; n += 1) {
        const data = words.slice(offset, offset + length);
        offset += length;
        blocks.push({ data: data, ec: errorCodewords(data, ecLength) });
      }
    }
    const out = [];
    let maxData = 0;
    for (let i = 0; i < blocks.length; i += 1) maxData = Math.max(maxData, blocks[i].data.length);
    for (let i = 0; i < maxData; i += 1) {
      for (let b = 0; b < blocks.length; b += 1) {
        if (i < blocks[b].data.length) out.push(blocks[b].data[i]);
      }
    }
    for (let i = 0; i < ecLength; i += 1) {
      for (let b = 0; b < blocks.length; b += 1) out.push(blocks[b].ec[i]);
    }
    return out;
  }

  /* ------------------------------------------------------------------ 矩阵 */
  function Matrix(version) {
    this.version = version;
    this.size = version * 4 + 17;
    this.modules = new Uint8Array(this.size * this.size);
    this.reserved = new Uint8Array(this.size * this.size);
  }

  Matrix.prototype.set = function (x, y, dark) {
    if (x < 0 || y < 0 || x >= this.size || y >= this.size) return;
    const at = y * this.size + x;
    this.modules[at] = dark ? 1 : 0;
    this.reserved[at] = 1;
  };

  Matrix.prototype.get = function (x, y) {
    return this.modules[y * this.size + x] === 1;
  };

  Matrix.prototype.free = function (x, y) {
    return this.reserved[y * this.size + x] === 0;
  };

  function drawFormat(matrix, mask) {
    const size = matrix.size;
    // 纠错等级 M 的两位是 00，低三位是掩码号。
    const data = mask;
    let rem = data;
    for (let i = 0; i < 10; i += 1) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
    const bits = ((data << 10) | rem) ^ 0x5412;
    function bit(index) { return ((bits >>> index) & 1) === 1; }

    for (let i = 0; i <= 5; i += 1) matrix.set(8, i, bit(i));
    matrix.set(8, 7, bit(6));
    matrix.set(8, 8, bit(7));
    matrix.set(7, 8, bit(8));
    for (let i = 9; i < 15; i += 1) matrix.set(14 - i, 8, bit(i));

    for (let i = 0; i < 8; i += 1) matrix.set(size - 1 - i, 8, bit(i));
    for (let i = 8; i < 15; i += 1) matrix.set(8, size - 15 + i, bit(i));

    matrix.set(8, size - 8, true);   // 固定的深色模块
  }

  function drawVersion(matrix) {
    const version = matrix.version;
    if (version < 7) return;
    let rem = version;
    for (let i = 0; i < 12; i += 1) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
    const bits = (version << 12) | rem;
    for (let i = 0; i < 18; i += 1) {
      const dark = ((bits >>> i) & 1) === 1;
      const a = matrix.size - 11 + (i % 3);
      const b = Math.floor(i / 3);
      matrix.set(a, b, dark);
      matrix.set(b, a, dark);
    }
  }

  function drawFunctionPatterns(matrix) {
    const size = matrix.size;

    // 定位图形 + 分隔符：中心在 (3,3) / (size-4,3) / (3,size-4)，半径 4 的外圈是分隔符。
    const centers = [[3, 3], [size - 4, 3], [3, size - 4]];
    for (let c = 0; c < centers.length; c += 1) {
      const cx = centers[c][0];
      const cy = centers[c][1];
      for (let dy = -4; dy <= 4; dy += 1) {
        for (let dx = -4; dx <= 4; dx += 1) {
          const dist = Math.max(Math.abs(dx), Math.abs(dy));
          matrix.set(cx + dx, cy + dy, dist !== 2 && dist !== 4);
        }
      }
    }

    // 定时图形：第 6 行 / 第 6 列，黑白相间。
    for (let i = 8; i < size - 8; i += 1) {
      matrix.set(i, 6, i % 2 === 0);
      matrix.set(6, i, i % 2 === 0);
    }

    // 对齐图形：5×5，中心深、第二圈浅、外圈深。
    const positions = ALIGN[matrix.version];
    for (let a = 0; a < positions.length; a += 1) {
      for (let b = 0; b < positions.length; b += 1) {
        const x = positions[a];
        const y = positions[b];
        if ((x === 6 && y === 6) || (x === 6 && y === size - 7) || (x === size - 7 && y === 6)) continue;
        for (let dy = -2; dy <= 2; dy += 1) {
          for (let dx = -2; dx <= 2; dx += 1) {
            matrix.set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
          }
        }
      }
    }

    drawFormat(matrix, 0);   // 先占位；掩码定下来后按选中的掩码重写
    drawVersion(matrix);
  }

  function drawCodewords(matrix, codewords) {
    const size = matrix.size;
    const totalBits = codewords.length * 8;
    let index = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;   // 跳过定时列
      for (let vert = 0; vert < size; vert += 1) {
        for (let j = 0; j < 2; j += 1) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vert : vert;
          if (matrix.free(x, y) && index < totalBits) {
            const dark = ((codewords[index >>> 3] >>> (7 - (index & 7))) & 1) === 1;
            matrix.modules[y * size + x] = dark ? 1 : 0;
            index += 1;
          }
        }
      }
    }
    // 剩余位保持 0（REMAINDER），即浅色。
  }

  const MASKS = [
    function (x, y) { return (x + y) % 2 === 0; },
    function (x, y) { return y % 2 === 0; },
    function (x, y) { return x % 3 === 0; },
    function (x, y) { return (x + y) % 3 === 0; },
    function (x, y) { return (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; },
    function (x, y) { return ((x * y) % 2) + ((x * y) % 3) === 0; },
    function (x, y) { return (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; },
    function (x, y) { return (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; },
  ];

  function applyMask(matrix, mask) {
    const size = matrix.size;
    for (let y = 0; y < size; y += 1) {
      for (let x = 0; x < size; x += 1) {
        const at = y * size + x;
        if (matrix.reserved[at]) continue;
        if (MASKS[mask](x, y)) matrix.modules[at] ^= 1;
      }
    }
  }

  function penalty(matrix) {
    const size = matrix.size;
    const modules = matrix.modules;
    let score = 0;

    for (let y = 0; y < size; y += 1) {
      let run = 1;
      for (let x = 1; x < size; x += 1) {
        if (modules[y * size + x] === modules[y * size + x - 1]) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) score += 3 + (run - 5);
    }

    for (let x = 0; x < size; x += 1) {
      let run = 1;
      for (let y = 1; y < size; y += 1) {
        if (modules[y * size + x] === modules[(y - 1) * size + x]) {
          run += 1;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
      if (run >= 5) score += 3 + (run - 5);
    }

    for (let y = 0; y < size - 1; y += 1) {
      for (let x = 0; x < size - 1; x += 1) {
        const a = modules[y * size + x];
        if (a === modules[y * size + x + 1] &&
            a === modules[(y + 1) * size + x] &&
            a === modules[(y + 1) * size + x + 1]) {
          score += 3;
        }
      }
    }

    // 形状像定位图形的 1:1:3:1:1（前后各 4 个浅色）各罚 40。
    for (let y = 0; y < size; y += 1) {
      let row = '';
      for (let x = 0; x < size; x += 1) row += modules[y * size + x] ? '1' : '0';
      score += countFinderLike(row);
    }
    for (let x = 0; x < size; x += 1) {
      let col = '';
      for (let y = 0; y < size; y += 1) col += modules[y * size + x] ? '1' : '0';
      score += countFinderLike(col);
    }

    let dark = 0;
    for (let i = 0; i < modules.length; i += 1) dark += modules[i];
    const percent = (dark * 100) / (size * size);
    score += Math.floor(Math.abs(percent - 50) / 5) * 10;
    return score;
  }

  function countFinderLike(line) {
    let count = 0;
    for (let i = 0; i + 11 <= line.length; i += 1) {
      const window = line.slice(i, i + 11);
      if (window === '10111010000' || window === '00001011101') count += 1;
    }
    return count * 40;
  }

  /* ------------------------------------------------------------------ 公开 */
  function build(text) {
    const source = String(text === null || text === undefined ? '' : text);
    const bytes = Array.from(new TextEncoder().encode(source));
    const version = pickVersion(bytes.length);
    if (!version) return null;

    const words = interleave(makeDataCodewords(bytes, version), version);
    let best = null;
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask += 1) {
      const matrix = new Matrix(version);
      drawFunctionPatterns(matrix);
      drawCodewords(matrix, words);
      applyMask(matrix, mask);
      drawFormat(matrix, mask);
      const score = penalty(matrix);
      if (score < bestScore) {
        bestScore = score;
        best = matrix;
      }
    }
    return best;
  }

  return {
    /** 编码成模块矩阵；内容超过版本 10（约 213 字节）时返回 null。 */
    encode: function (text) {
      const matrix = build(text);
      if (!matrix) return null;
      return {
        version: matrix.version,
        size: matrix.size,
        get: function (x, y) { return matrix.get(x, y); },
      };
    },

    /** 直接出 SVG：一个白色静区 + 一条 path（按行合并连续深色模块，节点数与版本无关）。 */
    svg: function (text, quiet) {
      const matrix = build(text);
      if (!matrix) return '';
      const size = matrix.size;
      const margin = quiet === undefined ? 4 : Math.max(0, quiet | 0);
      let path = '';
      for (let y = 0; y < size; y += 1) {
        let x = 0;
        while (x < size) {
          if (!matrix.get(x, y)) {
            x += 1;
            continue;
          }
          let run = 0;
          while (x + run < size && matrix.get(x + run, y)) run += 1;
          path += 'M' + x + ' ' + y + 'h' + run + 'v1h-' + run + 'z';
          x += run;
        }
      }
      const total = size + margin * 2;
      return '<svg class="qr" viewBox="' + (-margin) + ' ' + (-margin) + ' ' + total + ' ' + total + '" ' +
        'data-version="' + matrix.version + '" data-modules="' + size + '" ' +
        'shape-rendering="crispEdges" role="img" aria-label="二维码">' +
        '<rect x="' + (-margin) + '" y="' + (-margin) + '" width="' + total + '" height="' + total + '" fill="#ffffff"/>' +
        '<path d="' + path + '" fill="#000000"/>' +
        '</svg>';
    },
  };
})();
