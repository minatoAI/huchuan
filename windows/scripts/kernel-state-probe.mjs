// 内核态（kernelState）与个人空间（isPersonal）在 Windows 页面上的读回：Edge headless + CDP（证据用）。
//
// 由 windows/scripts/capture-kernelstate-ui.sh 调用（它负责起 daemon、把条目点成 acquiring、再起 Edge）。
// 这里只断言页面侧的事实：
//   1. acquiring 态：P3 行状态 chip 说「正在寻找来源」且是 is-on，P4 的激活开关保持「已激活」，
//      「已落盘」那一行不出现（元数据都没到手，盘上一字节没有）；
//   2. 六值映射表：fileStateLabel() / fileActive() 逐值对一遍（页面用到的就是这两个纯函数）；
//   3. parked 态：取证别名 p3-parked 的夹具下 chip 说「休眠」，开关是关的；
//   4. 个人空间：P2 第一行就是「我的分享」且带 is-personal，P3 进得去、成员入口仍是 1 人。
//
// 退出码即结论：0 = 全过，非 0 = 某条断言红了（断言消息打在 stdout 里）。
import { setTimeout as delay } from 'node:timers/promises';

const PORT = Number(process.env.CDP_PORT || 9336);

function assert(condition, message) {
  if (!condition) throw new Error('ASSERT: ' + message);
}

const targets = await (await fetch('http://127.0.0.1:' + PORT + '/json/list')).json();
const target = targets.find((item) => item.type === 'page' && item.url.startsWith('http://127.0.0.1'));
if (!target) throw new Error('no page target: ' + JSON.stringify(targets.map((item) => item.url)));
const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });

let seq = 0;
const pending = new Map();
ws.onmessage = (event) => {
  const message = JSON.parse(event.data);
  if (!message.id) return;
  const waiter = pending.get(message.id);
  if (!waiter) return;
  pending.delete(message.id);
  if (message.error) waiter.reject(new Error(JSON.stringify(message.error)));
  else waiter.resolve(message.result);
};
function send(method, params = {}) {
  const id = ++seq;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evalJs(expression) {
  const result = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (result.exceptionDetails) throw new Error('page exception: ' + JSON.stringify(result.exceptionDetails));
  return result.result && result.result.value;
}
async function waitFor(expression, label, timeoutMs = 15000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (await evalJs(expression)) return;
    await delay(100);
  }
  throw new Error('timeout: ' + label);
}

/** 状态 chip 的读法：chip 文案 + 是不是 is-on（高亮）。 */
const CHIP_JS =
  "Array.from(document.querySelectorAll('.trow .chip')).map(function (chip) {" +
  "  return { text: chip.innerText, on: chip.className.indexOf('is-on') >= 0 };" +
  "})";

await send('Runtime.enable');

// ---------------------------------------------------------------- 1. acquiring 态
await waitFor("document.body.dataset.page === 'p3' && document.querySelectorAll('.trow').length > 0", 'P3 acquiring ready', 25000);
const rows = await evalJs("Array.from(document.querySelectorAll('.trow')).map(function (row) { return row.innerText; })");
console.log('p3 rows=' + JSON.stringify(rows));
assert(rows.some((row) => row.indexOf('公开') >= 0 && row.indexOf('正在寻找来源') >= 0), 'P3 public row says 公开 + 正在寻找来源');
assert(!rows.some((row) => row.indexOf('正在寻找来源') >= 0 && /\b0 B\b/.test(row)), 'acquiring row does not show a 0 B size');

const chips = await evalJs(CHIP_JS);
console.log('p3 chips=' + JSON.stringify(chips));
assert(chips.some((chip) => chip.text === '正在寻找来源' && chip.on), '正在寻找来源 chip is highlighted (is-on)');

// ---------------------------------------------------------------- 2. 六值映射表
const mapping = await evalJs(`(function () {
  var base = {
    id: 'probe', name: 'probe', sizeBytes: 1024, sizeKnown: true, scope: 'public',
    state: 'sleeping', progressPercent: 0, diskBytes: 0, seeders: 0, teammates: 0,
    uploadLimitKbps: 0, uploadLimitInForce: false, downloadRateBps: 0, uploadRateBps: 0,
    savedPath: '', magnet: '', sourceMember: '', comments: []
  };
  var states = ['parked', 'waiting_metadata', 'acquiring', 'downloading', 'seeding', 'absent'];
  return {
    table: states.map(function (kernelState) {
      var file = Object.assign({}, base, { kernelState: kernelState });
      return { kernelState: kernelState, label: fileStateLabel(file), active: fileActive(file) };
    }),
    legacySeeding: fileStateLabel(Object.assign({}, base, { state: 'seeding' })),
    legacySleeping: fileStateLabel(Object.assign({}, base, { kernelState: '' }))
  };
})()`);
console.log('mapping=' + JSON.stringify(mapping));
const WANT = {
  parked: ['休眠', false],
  waiting_metadata: ['等待来源', false],
  acquiring: ['正在寻找来源', true],
  downloading: ['下载中', true],
  seeding: ['做种中', true],
  absent: ['休眠', false],
};
for (const row of mapping.table) {
  const want = WANT[row.kernelState];
  assert(want, 'unexpected kernelState in mapping: ' + row.kernelState);
  assert(row.label === want[0], 'kernelState=' + row.kernelState + ' → label ' + row.label + ' (want ' + want[0] + ')');
  assert(row.active === want[1], 'kernelState=' + row.kernelState + ' → active ' + row.active + ' (want ' + want[1] + ')');
}
assert(mapping.legacySeeding === '做种中', 'old daemon without kernelState still says 做种中');
assert(mapping.legacySleeping === '休眠', 'old daemon without kernelState falls back to 休眠');

// P5 加入失败的两句话：对端明确说「我是个人空间」与普通失败不能混成一句。
const joinCopy = await evalJs("({ personal: joinFailureLabel('E_JOIN_PERSONAL'), normal: joinFailureLabel('E_JOIN_FAILED') })");
console.log('join copy=' + JSON.stringify(joinCopy));
assert(joinCopy.personal === '对方是个人空间，不能被加入', 'E_JOIN_PERSONAL says 对方是个人空间，不能被加入');
assert(joinCopy.normal === '邀请码不对', 'E_JOIN_FAILED keeps 邀请码不对');

// ---------------------------------------------------------------- 3. P4：开关不弹回休眠
await evalJs("(function () { var g = Store.groups().filter(function (x) { return x.files.some(function (f) { return f.scope === 'public'; }); })[0]; State.groupId = g.id; State.fileId = g.files.filter(function (f) { return f.scope === 'public'; })[0].id; Nav.go('p4', State.fileId); })()");
await waitFor("document.body.dataset.page === 'p4' && !!document.querySelector('[data-act=\"toggle-active\"]')", 'P4 ready');
const p4 = await evalJs(
  "(function () {" +
  "  var sw = document.querySelector('[data-act=\"toggle-active\"]');" +
  "  return { checked: sw.getAttribute('aria-checked'), text: document.body.innerText };" +
  "})()"
);
console.log('p4 acquiring checked=' + p4.checked);
assert(p4.checked === 'true', 'P4 激活 switch stays on while acquiring (aria-checked=' + p4.checked + ')');
assert(p4.text.indexOf('正在寻找来源') >= 0, 'P4 chip says 正在寻找来源');
assert(p4.text.indexOf('已落盘') < 0, 'P4 does not show 已落盘 when diskBytes is 0');

// ---------------------------------------------------------------- 4. parked 态（取证别名）
await evalJs("location.href = '/index.html?route=p3-parked'");
await waitFor("document.body.dataset.page === 'p3' && document.querySelectorAll('.trow').length > 0", 'P3 parked ready', 25000);
const parkedRows = await evalJs("Array.from(document.querySelectorAll('.trow')).map(function (row) { return row.innerText; })");
const parkedChips = await evalJs(CHIP_JS);
console.log('parked rows=' + JSON.stringify(parkedRows) + ' chips=' + JSON.stringify(parkedChips));
assert(parkedRows.some((row) => row.indexOf('休眠') >= 0), 'parked row says 休眠');
assert(parkedChips.some((chip) => chip.text === '休眠' && !chip.on), '休眠 chip is not highlighted');
assert(!parkedRows.some((row) => row.indexOf('正在寻找来源') >= 0), 'parked row never says 正在寻找来源');
await evalJs("(function () { var g = Store.groups().filter(function (x) { return x.files.length > 0; })[0]; State.fileId = g.files[0].id; Nav.go('p4', g.files[0].id); })()");
await waitFor("document.body.dataset.page === 'p4' && !!document.querySelector('[data-act=\"toggle-active\"]')", 'P4 parked ready');
const parkedActive = await evalJs("document.querySelector('[data-act=\"toggle-active\"]').getAttribute('aria-checked')");
console.log('parked p4 checked=' + parkedActive);
assert(parkedActive === 'false', 'parked file shows 休眠 with the switch off');

// ---------------------------------------------------------------- 5. 个人空间（P2 置顶 / P3 可进）
await evalJs("location.href = '/index.html?route=p2'");
await waitFor("document.body.dataset.page === 'p2' && document.querySelectorAll('.row').length > 0", 'P2 ready', 25000);
const p2 = await evalJs(
  "(function () {" +
  "  var rows = Array.from(document.querySelectorAll('.row'));" +
  "  return {" +
  "    first: rows[0].innerText," +
  "    firstPersonal: rows[0].className.indexOf('is-personal') >= 0," +
  "    personalCount: rows.filter(function (r) { return r.className.indexOf('is-personal') >= 0; }).length," +
  "    names: rows.map(function (r) { return r.innerText.split('\\n')[0]; })," +
  "    text: document.body.innerText" +
  "  };" +
  "})()"
);
console.log('p2 first=' + JSON.stringify(p2.first) + ' names=' + JSON.stringify(p2.names));
assert(p2.firstPersonal, 'P2 pins the personal space first (is-personal on the first row)');
assert(p2.first.indexOf('我的分享') >= 0, 'P2 first row is 我的分享, got ' + p2.first);
assert(p2.personalCount === 1, 'exactly one personal space in the list');
assert(p2.names.length === 2, 'P2 lists 我的分享 + 老板的群, got ' + JSON.stringify(p2.names));

await evalJs("location.href = '/index.html?route=p3-personal'");
await waitFor("document.body.dataset.page === 'p3' && document.querySelectorAll('.trow').length > 0", 'P3 personal ready', 25000);
const personal = await evalJs("({ text: document.body.innerText, rows: Array.from(document.querySelectorAll('.trow')).map(function (r) { return r.innerText; }) })");
console.log('p3 personal rows=' + JSON.stringify(personal.rows));
assert(personal.text.indexOf('我的分享') >= 0, 'P3 in the personal space shows 我的分享 in the header');
assert(personal.rows.some((row) => row.indexOf('travel-photos.zip') >= 0), 'P3 personal space lists its own file');
assert(personal.text.indexOf('成员（1 人）') >= 0, 'personal space still has the member entry (one person)');
assert(personal.text.indexOf('邀请码') < 0, 'no invite copy in the personal space P3');

console.log('KERNEL_STATE_UI_OK');
ws.close();
