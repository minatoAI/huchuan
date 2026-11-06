// `node --test worker/` 的入口。
//
// 为什么需要这个文件：Node 24 的 test runner 把位置参数一律当 glob 处理，传一个
// 目录进去会被当成模块执行（"Cannot find module .../worker"），"目录递归查找"在
// --test 参数上已经不可用。这个文件只做一件事：把 worker/test 下的用例全部 import
// 进来，让「node --test worker/」这条命令真的跑全部用例。
//
// 它不是 Worker 的部署入口——部署入口是 src/index.js（wrangler.toml 的 main），
// 本文件不会被打包，也不会被任何生产代码引用。
//
// 等价的写法（都绿）：
//   node --test "worker/**/*.test.mjs"
//   cd worker && node --test "test/**/*.test.mjs"
import "./test/health.test.mjs";
import "./test/crypto.test.mjs";
import "./test/announce.test.mjs";
import "./test/share.test.mjs";
import "./test/signal.test.mjs";
