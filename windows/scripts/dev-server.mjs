// 互传 Windows 壳 · 开发用静态服务器。
//
// 由 scripts/dev-web.sh 启动；只服务 windows/resources/web，改完刷新浏览器就能看到。
// 端口、根目录可用参数覆盖。除了静态文件什么都不做：没有构建步骤、没有依赖、不引 CDN。
import { createServer } from 'node:http';
import { readFile, stat } from 'node:fs/promises';
import { extname, join, normalize, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const DEFAULT_ROOT = resolve(HERE, '..', 'resources', 'web');

function parseArgs(argv) {
  const options = { root: DEFAULT_ROOT, port: 8731 };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg === '--port' || arg === '-p') {
      options.port = Number(argv[i + 1]);
      i += 1;
    } else if (arg === '--root') {
      options.root = resolve(argv[i + 1]);
      i += 1;
    } else if (arg === '--help' || arg === '-h') {
      options.help = true;
    }
  }
  if (process.env.PORT) options.port = Number(process.env.PORT);
  return options;
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

const options = parseArgs(process.argv.slice(2));

if (options.help) {
  console.log('用法：node windows/scripts/dev-server.mjs [--port 8731] [--root <目录>]');
  process.exit(0);
}

if (!Number.isFinite(options.port) || options.port <= 0 || options.port > 65535) {
  console.error('[x] 端口不合法：', options.port);
  process.exit(2);
}

const server = createServer(async (request, response) => {
  try {
    const url = new URL(request.url || '/', 'http://localhost');
    let pathname = decodeURIComponent(url.pathname);
    if (pathname.endsWith('/')) pathname += 'index.html';

    // 只允许读根目录内的文件：normalize 之后必须仍在 root 之下。
    const target = normalize(join(options.root, pathname));
    if (!target.startsWith(options.root + sep) && target !== options.root) {
      response.writeHead(403, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('forbidden');
      return;
    }

    const info = await stat(target).catch(() => null);
    if (!info || !info.isFile()) {
      response.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      response.end('not found: ' + pathname);
      return;
    }

    const body = await readFile(target);
    response.writeHead(200, {
      'Content-Type': MIME[extname(target).toLowerCase()] || 'application/octet-stream',
      'Content-Length': body.length,
      // 改完立刻生效：开发服务器一律不缓存。
      'Cache-Control': 'no-store, must-revalidate',
    });
    response.end(body);
  } catch (error) {
    response.writeHead(500, { 'Content-Type': 'text/plain; charset=utf-8' });
    response.end('server error: ' + error.message);
  }
});

server.listen(options.port, '127.0.0.1', () => {
  console.log('互传 dev-web：http://127.0.0.1:' + options.port + '/');
  console.log('静态根目录：' + options.root);
  console.log('调试参数：?route=p3  ?theme=dark  ?network=limited|fail  ?hold=boot|checking|result');
  console.log('停止：Ctrl+C');
});
