'use strict';

/**
 * 互传 Windows 壳 · 十页 Web UI（纯静态，无框架、无 CDN、无在线二维码服务）。
 *
 * 信息架构与 Android 壳同构：P0 自检 → P1 用户名 → P2 首页 → P3 群组 → P4 文件详情，
 * P5 新群组、P6 成员、P7 设置抽屉、P8 链接分享（二维码）、P9 分享管理。数据来自 data.js 的 store。
 *
 * 路由：#/p0 … #/p9。#/p7 是 P2 之上的设置抽屉（Android 里是 Drawer，没有独立目的地）；
 * 分享链接从 P4「…」→「分享链接」创建后进 #/p8/<token>，P7「分享管理」进 #/p9。
 * 开发/取证参数（正常启动不带）：?route=p3&theme=dark&network=fail&hold=result
 *
 * 桌面差异（相对 Android，见 docs/windows-ui.md）：没有系统返回键，所以页头给了 ←，
 * 并绑定 Esc / Alt+←；键盘 Enter 提交当前页的主操作。
 */

/* ------------------------------------------------------------ 文案表
 * 逐条对应 docs/design/product-spec.md §3 各页「文案」行，字面照抄。
 * 任何文案改动先改 spec，再改这里。
 */
const COPY = {
  // P0 自检开屏
  p0_booting: '正在启动',
  p0_checking: '正在检测网络',
  p0_pass: '可互传',
  p0_degraded: '仅局域网可用',
  p0_failed: '网络不可用',
  p0_retry: '重试',

  // P1 用户名
  p1_title: '用户名',
  p1_save: '保存',
  p1_saving: '保存中',
  p1_empty: '请输入用户名',
  p1_max: '最多 20 字',

  // P2 首页
  p2_search: '搜索群组',
  p2_empty: '还没有群组',
  p2_settings: '设置',
  p2_new_group: '+',
  p2_seeding: function (n) { return '做种 ' + n; },
  p2_team: function (n) { return '组队 ' + n + ' 人'; },
  p2_unread: function (n) { return n + ' 条新消息'; },

  // P3 群组
  p3_add_file: '添加文件',
  p3_add_local: '本机文件',
  p3_add_magnet: '磁力链接',
  p3_add_need_shell: '只能在互传窗口里选文件',
  p3_add_failed: '添加失败',
  p3_members: function (n) { return '成员（' + n + ' 人）'; },
  p3_seeding: '做种中',
  p3_sleeping: '休眠',
  p3_downloading: '下载中',
  p3_waiting_source: '等待来源',
  p3_scope_public: '公开',
  p3_team: function (n) { return '组队 ' + n + ' 人'; },
  p3_seeders: function (n) { return '做种 ' + n; },
  p3_empty: '还没有文件',
  p3_magnet_hint: '粘贴磁力链接',
  p3_magnet_import: '导入',
  p3_magnet_importing: '导入中',

  // P4 文件详情
  p4_activate: '激活',
  p4_sleep: '休眠',
  p4_remove: '移除',
  p4_more: '更多',
  p4_share_link: '分享链接',
  p4_teaming: function (n) { return '正在组队 · ' + n + ' 人'; },
  p4_seeders: function (n) { return '做种者 ' + n; },
  p4_waiting: '等待做种者',
  p4_comment_hint: '写评论',
  p4_send: '发送',
  p4_no_comments: '还没有评论',
  p4_reply: function (name) { return '回复 ' + name; },
  p4_upload_limit: '单文件限速',
  p4_source: function (name) { return '来源 ' + name; },
  // 单文件限速的读回：core 没有「读回某个 torrent 的限速」的口子（daemon-api 缺口 G9），
  // 所以这里给的是 daemon 记的值 + 此刻是不是真的套在会话里（uploadLimitInForce）。
  p4_limit_in_force: function (kbps) { return '已生效 ' + kbps + ' ' + COPY.unit_kbps; },
  p4_limit_saved: function (kbps) { return '已设置 ' + kbps + ' ' + COPY.unit_kbps; },

  // P5 新群组
  p5_title: '新群组',
  p5_join: '加入',
  p5_joining: '加入中',
  p5_create_group: '新建群组',
  p5_invite_hint: '邀请码后 8 位',
  p5_invite_wrong: '邀请码不对',
  p5_group_name_default: function (name) { return name + '的群组'; },
  p5_create: '创建',

  // P6 成员
  p6_title: '成员',
  p6_online: '在线',
  p6_seeding: function (n) { return '做种 ' + n; },
  p6_invite: '邀请码',
  p6_copied: '已复制',
  p6_empty: '暂无成员',

  // P7 设置
  p7_title: '设置',
  p7_user_name: '用户名',
  p7_global_limit: '全局限速',
  p7_upload: '上行',
  p7_download: '下行',
  p7_unlimited: '不限',
  // 内核读回：读的是会话里真正生效的值（0 = 不限），不是输入框里那个「用户想要的值」
  p7_readback: '内核读回',
  p7_readback_value: function (up, down) { return '上行 ' + up + ' · 下行 ' + down; },
  p7_notification: '通知',
  p7_tracker: '跨网发现',
  p7_lan_only: '仅局域网',
  p7_tracker_pending: '重启后生效',
  p7_network_status: '网络状态',
  p7_bubble: '系统气泡',
  p7_in_app: '仅应用内',
  p7_muted: '静音',
  p7_share_manage: '分享管理',

  // P8 链接分享（文案同 Android，链接终态是二维码）
  p8_title: '分享链接',
  p8_creating: '创建中',
  p8_waiting: '等待访客',
  p8_incoming: '访客已加入',
  p8_sending: function (n) { return '传输中 ' + n + '%'; },
  p8_finished: '已结束',
  p8_finished_failed: '已结束 · 传输失败',
  p8_finished_revoked: '已结束 · 链接已撤销',
  p8_finished_expired: '已结束 · 链接已过期',
  p8_finished_missing: '已结束 · 文件不在本机',
  p8_expires_days: function (n) { return n + ' 天后失效'; },
  p8_expires_hours: function (n) { return n + ' 小时后失效'; },
  p8_expires_minutes: function (n) { return n + ' 分钟后失效'; },
  p8_expired: '已失效',
  p8_copy: '复制链接',
  p8_not_found: '没有这条分享',
  p8_not_configured: '分享服务未配置',
  p8_server_unreachable: '连不上分享服务',
  p8_too_many: '分享数量已达上限',
  p8_no_local: '文件不在本机',
  p8_file_changed: '文件已改变',
  p8_no_file: '没有这个文件',
  p8_create_failed: '创建失败',
  p8_read_failed: '读取失败',

  // P9 分享管理
  p9_title: '分享管理',
  p9_loading: '正在读取',
  p9_empty: '还没有分享',
  p9_visits: function (n) { return '被访问 ' + n + ' 次'; },
  p9_revoke: '撤销',
  p9_revoke_title: '撤销分享',
  p9_revoke_confirm: '撤销后这条链接立即失效',
  p9_revoke_failed: '撤销失败',
  p9_cancel: '取消',

  // 通用
  unit_kbps: 'KB/s',
  back: '返回',
  close: '关闭',
};

/* ------------------------------------------------------------ 运行环境 */
const Params = new URLSearchParams(location.search);

/** 只给开发与截图取证用的开关，正常启动一个都不带。 */
const Dev = {
  network: Params.get('network') || '',  // ''（真实自检）| pass | limited | fail
  hold: Params.get('hold') || '',        // boot | checking | result
  pick: Params.get('pick') || '',        // 1 = 进 P3 后自动弹一次系统选择器（验证壳桥用）
};

const app = document.getElementById('app');

const State = {
  route: 'p0',
  groupId: 'g1',
  fileId: null,
  report: { status: 'pass' },
  search: '',
  draftComment: '',
  replyTo: null,
  createOpen: false,
  copied: false,
  // P3「添加文件」弹层：一个入口两项（本机文件 / 磁力链接），不新开页面。
  addOpen: false,
  addMode: 'menu',        // menu | magnet
  magnetText: '',
  magnetBusy: false,
  magnetNote: '',         // 导入失败时显示在输入框下一行
  magnetNoteError: false, // 非法 / 重复这类「被拒」才染红；无源 / 超时只是还没成
  magnetCopied: false,
  magnetCopiedTimer: null,
  // P4「…」溢出菜单（本轮只有「分享链接」一项；单文件限速在页内，不占菜单）。
  p4Menu: false,
  // P8：shareToken 为空时可能是「创建中」或创建失败；shareFileId 记着从哪个文件建的。
  shareToken: '',
  shareFileId: '',
  shareCreating: false,
  shareError: '',
  shareCopied: false,
  shareCopiedTimer: null,
  sharePoll: null,        // P8 / P9 打开期间的 /api/shares 轮询定时器
  // P9：待确认撤销的 token（不可逆动作给一次确认）
  revokeToken: '',
  revokeError: '',
};

/**
 * 取证态路由别名：只在 ?route= / --route= 里出现，正常导航不带。
 *   p3-magnet  落 P3 并直接打开「磁力链接」输入（截图磁力弹层用）
 *   p4-public  落 P4 并选中本群第一条公开条目（截图公开详情用）
 * 页面名仍然是 p3 / p4，所以截图任务表的 --shot-page 不用改。
 */
const RouteAlias = {
  'p3-magnet': { page: 'p3', add: 'magnet' },
  'p4-public': { page: 'p4', public: true },
  // 取证用：落 P8/P9 前先确保 daemon 里有一条有效分享（没有就用本群第一个私有文件建一条）。
  // 正常导航不带这两个别名 —— 真实入口是 P4「…」与 P7「分享管理」。
  'p8-share': { page: 'p8', ensureShare: true },
  'p9-list': { page: 'p9', ensureShare: true },
};

/* -------------------------------------------------------------- 小工具 */
function esc(text) {
  return String(text).replace(/[&<>"']/g, function (ch) {
    return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch];
  });
}

/** 文件大小：资源管理器写法，1.2 GB（与 Android 的 formatSize 一致）。 */
function formatSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes / 1024;
  let index = 0;
  while (value >= 1024 && index < units.length - 1) {
    value /= 1024;
    index += 1;
  }
  return value.toFixed(1) + ' ' + units[index];
}

function formatRate(bytesPerSecond) {
  return formatSize(bytesPerSecond) + '/s';
}

function digitsOnly(text, max) {
  return String(text).replace(/\D/g, '').slice(0, max);
}

/** 公开条目：P3 的「公开」chip、P4 的磁力摘要与「移除」都只认这一个判断。 */
function isPublicFile(file) {
  return file.scope === FileScope.PUBLIC;
}

/**
 * 元数据还没到手：公开条目 + 尺寸未知。此时既不下载也不做种，状态说「等待来源」，
 * 尺寸也不显示（daemon 不给 sizeBytes，0 不是「零字节」，见 docs/daemon-api.md §3.3）。
 * 口径同 Android 的 GroupFile.awaitingSource()。
 */
function fileAwaitingSource(file) {
  return isPublicFile(file) && (file.sizeKnown === false || !(file.sizeBytes > 0));
}

/** 磁力摘要：只留 infohash 前 16 位，点一下复制整条（同 Android 的 magnetDigest）。 */
function magnetDigest(magnet) {
  const text = String(magnet || '');
  const prefix = 'magnet:?xt=urn:btih:';
  if (text.slice(0, prefix.length).toLowerCase() !== prefix) return text;
  const infohash = text.slice(prefix.length).split('&')[0];
  return infohash.length > 16 ? 'btih:' + infohash.slice(0, 16) + '…' : text;
}

function fileStateLabel(file) {
  if (fileAwaitingSource(file)) return COPY.p3_waiting_source;
  if (file.state === FileState.SEEDING) return COPY.p3_seeding;
  if (file.state === FileState.DOWNLOADING) return COPY.p3_downloading;
  return COPY.p3_sleeping;
}

/**
 * P7 的网络状态。跨网发现关掉时，能到达的范围就是局域网：不管自检本身是 pass 还是
 * degraded，都只说「仅局域网」。开关是落盘设置，重启后才真正改变内核，所以只有落盘为开时
 * 才按自检结论说「可互传」。
 */
function networkLabel() {
  if (State.report.status === 'failed') return COPY.p0_failed;
  if (!Store.device().trackerEnabled) return COPY.p7_lan_only;
  if (State.report.status === 'degraded') return COPY.p0_degraded;
  return COPY.p0_pass;
}

function localName() {
  const device = Store.device();
  return device.userName || device.deviceName;
}

/** 限速读数的单位口径：0 就是「不限」（core 的约定，界面不再另造一个零值说法）。 */
function limitRateLabel(kbps) {
  return kbps > 0 ? kbps + ' ' + COPY.unit_kbps : COPY.p7_unlimited;
}

/** P7 的内核读回：上行 / 下行各一项，值来自 /api/state 的 settings.session*Kbps。 */
function globalLimitReadback(device) {
  return COPY.p7_readback_value(
    limitRateLabel(device.sessionUploadLimitKbps),
    limitRateLabel(device.sessionDownloadLimitKbps)
  );
}

/** P4 的单文件限速读回：设了值才有「已生效 / 已设置」两种说法，0 就是「不限」。 */
function fileLimitReadback(file) {
  if (!(file.uploadLimitKbps > 0)) return COPY.p7_unlimited;
  return file.uploadLimitInForce
    ? COPY.p4_limit_in_force(file.uploadLimitKbps)
    : COPY.p4_limit_saved(file.uploadLimitKbps);
}

/* -------------------------------------------------------------- 路由 */
const Nav = {
  stack: [{ page: 'p0', id: null }],

  current: function () {
    return this.stack[this.stack.length - 1];
  },

  /** 前进：入栈 + 重绘。 */
  go: function (page, id) {
    this.stack.push({ page: page, id: id || null });
    this.sync();
  },

  /** 返回：出栈 + 重绘；栈底不动（P2 / P0 没有上级）。 */
  back: function () {
    if (this.stack.length <= 1) return;
    this.stack.pop();
    this.sync();
  },

  /** 进入新的一段流程（清空返回栈），如 P0 放行、P5 建群成功。 */
  reset: function (page, id) {
    this.stack = [{ page: page, id: id || null }];
    this.sync();
  },

  /** 留在当前栈深度上换一页 / 换 id：P8 创建成功后把 #/p8 补成 #/p8/<token>，不改返回目标。 */
  replace: function (page, id) {
    this.stack[this.stack.length - 1] = { page: page, id: id || null };
    this.sync();
  },

  sync: function () {
    const item = this.current();
    State.route = item.page;
    // 栈里的 id 在 P3/P6 是群组、在 P4 是文件、在 P8 是分享 token；其余页忽略。
    if (item.page === 'p3' || item.page === 'p6') {
      if (item.id) State.groupId = item.id;
    } else if (item.page === 'p4') {
      if (item.id) State.fileId = item.id;
    } else if (item.page === 'p8') {
      if (item.id) State.shareToken = item.id;
    }
    const hash = '#/' + item.page + (item.id ? '/' + item.id : '');
    history.replaceState(null, '', location.pathname + location.search + hash);
    render();
  },
};

/** 深链（开发 / 取证）时把返回栈补齐，让 ← 的行为和点进来时一致。 */
function seedStack(route, id) {
  const parent = {
    p0: [],
    p1: ['p0'],
    p2: [],
    p3: ['p2'],
    p4: ['p2', 'p3'],
    p5: ['p2'],
    p6: ['p2', 'p3'],
    p7: ['p2'],
    p8: ['p2'],          // 深链进 P8 时没有文件上下文，返回落到 P2
    p9: ['p2', 'p7'],    // P9 的上级是 P7 设置抽屉（spec：出口 → P7）
  }[route] || [];
  Nav.stack = parent.map(function (page) { return { page: page, id: null }; });
  Nav.stack.push({ page: route, id: id || null });
  Nav.sync();
}

/* -------------------------------------------------------------- 渲染 */
function render() {
  clearSplash();

  // 离开某页就清掉只属于它的瞬时状态（溢出菜单 / 撤销确认），免得下次进来还挂着。
  if (State.route !== 'p4') State.p4Menu = false;
  if (State.route !== 'p9') State.revokeToken = '';

  // 数据每秒都可能变，整页重绘会换掉 DOM：把焦点和光标位置记下来，画完再还回去。
  const active = document.activeElement;
  const focusId = active && active.id && app.contains(active) ? active.id : '';
  const caret = focusId && typeof active.selectionStart === 'number' ? active.selectionStart : null;

  let html = '';
  switch (State.route) {
    case 'p0': html = viewSplash(); break;
    case 'p1': html = viewUserName(); break;
    case 'p2': html = viewHome(); break;
    case 'p3': html = viewGroup(); break;
    case 'p4': html = viewFile(); break;
    case 'p5': html = viewNewGroup(); break;
    case 'p6': html = viewMembers(); break;
    case 'p7': html = viewHome() + viewSettings(); break;
    case 'p8': html = viewShare(); break;
    case 'p9': html = viewShareList(); break;
    default: html = viewHome();
  }

  app.innerHTML = html;
  document.body.dataset.page = State.route;

  syncSharePolling();

  if (State.route === 'p0') startSplash();

  const autofocus = app.querySelector('[data-autofocus]');
  if (autofocus) autofocus.focus();

  const restored = focusId ? document.getElementById(focusId) : null;
  if (restored) {
    restored.focus();
    if (caret !== null && restored.setSelectionRange) {
      try {
        restored.setSelectionRange(caret, caret);
      } catch (error) {
        // number 类输入框不支持光标定位，忽略
      }
    }
  }
}

/**
 * P8 / P9 打开期间每 2 s 拉一次 /api/shares：状态行（等待访客 → 传输中 xx% → 已结束）
 * 与 P9 的撤销结果都靠它推进。离开两页立刻停 —— 轮询只服务这两页，不在后台空转。
 * 其余页面仍然走 WS 推送（state / tick）；分享不在 /api/state 里，所以这两页单独轮询。
 */
function syncSharePolling() {
  const active = State.route === 'p8' || State.route === 'p9';
  if (active && !State.sharePoll) {
    State.sharePoll = setInterval(function () {
      if (State.route === 'p8' || State.route === 'p9') Store.loadShares(true);
    }, 2000);
    Store.loadShares(true);
  } else if (!active && State.sharePoll) {
    clearInterval(State.sharePoll);
    State.sharePoll = null;
  }
}

/* ------------------------------------------------------- P0 自检开屏 */
let splashTimers = [];

function clearSplash() {
  splashTimers.forEach(clearTimeout);
  splashTimers = [];
}

function later(ms, fn) {
  splashTimers.push(setTimeout(fn, ms));
}

/**
 * 自检结果：唯一判据是「daemon 在不在」—— 它就是内核，连不上就什么都做不了。
 * ?network= 只给取证时强制降级 / 失败，正常启动不带。
 */
function probeReport() {
  if (Dev.network === 'limited') return { status: 'degraded' };
  if (Dev.network === 'fail') return { status: 'failed' };
  if (Dev.network === 'pass') return { status: 'pass' };
  return { status: Store.online() ? 'pass' : 'failed' };
}

function setSplashPhase(phase) {
  const status = document.getElementById('splash-status');
  const spinner = document.getElementById('splash-spinner');
  const retry = document.getElementById('splash-retry');
  if (!status) return;

  const text = {
    boot: COPY.p0_booting,
    checking: COPY.p0_checking,
    pass: COPY.p0_pass,
    degraded: COPY.p0_degraded,
    failed: COPY.p0_failed,
  }[phase];

  const tone = { pass: ' is-ok', degraded: ' is-notice', failed: ' is-error' }[phase] || '';
  status.textContent = text;
  status.className = 'splash-status' + tone;

  const failed = phase === 'failed';
  if (spinner) spinner.classList.toggle('is-idle', failed);  // 失败态停转，把注意力让给「重试」
  if (retry) retry.hidden = !failed;
}

function startSplash() {
  State.report = probeReport();
  setSplashPhase('boot');

  later(700, function () {
    if (Dev.hold === 'boot') return;
    setSplashPhase('checking');
    later(700, function () {
      if (Dev.hold === 'checking') return;
      const status = State.report.status;
      setSplashPhase(status);
      if (Dev.hold === 'result') return;
      if (status === 'pass') {
        later(450, enterApp);
      } else if (status === 'degraded') {
        later(1200, enterApp);
      }
    });
  });
}

/** 自检通过后的放行：首次启动进 P1（还没设用户名），否则直接 P2。 */
function enterApp() {
  if (Store.device().userName) {
    Nav.reset('p2');
  } else {
    Nav.reset('p1');
  }
}

function viewSplash() {
  return '<div class="splash">' +
    '<div class="splash-brand">互传</div>' +
    '<div class="spinner" id="splash-spinner" role="progressbar" aria-label="' + COPY.p0_booting + '"></div>' +
    '<p class="splash-status" id="splash-status" aria-live="polite">' + COPY.p0_booting + '</p>' +
    '<button class="btn btn-primary" id="splash-retry" data-act="retry" hidden>' + COPY.p0_retry + '</button>' +
    '</div>';
}

/* ---------------------------------------------------------- P1 用户名 */
function viewUserName() {
  const device = Store.device();
  const name = device.userName || device.deviceName;
  return '<div class="screen">' +
    topbar(COPY.p1_title) +
    '<div class="content"><div class="page page-narrow">' +
      '<div class="card stack">' +
        '<input class="input" id="user-name" maxlength="20" value="' + esc(name) + '" ' +
          'aria-label="' + COPY.p1_title + '" data-autofocus>' +
        '<p class="field-note" id="user-name-note">' + COPY.p1_max + '</p>' +
        '<button class="btn btn-primary btn-block" data-act="save-name">' + COPY.p1_save + '</button>' +
      '</div>' +
    '</div></div>' +
    '</div>';
}

function saveUserName() {
  const input = document.getElementById('user-name');
  const note = document.getElementById('user-name-note');
  const button = app.querySelector('[data-act="save-name"]');
  const name = input ? input.value.trim() : '';

  if (!name) {
    if (note) {
      note.textContent = COPY.p1_empty;
      note.className = 'field-note is-error';
    }
    if (input) input.focus();
    return;
  }

  if (button) {
    button.disabled = true;
    button.textContent = COPY.p1_saving;
  }
  Store.saveUserName(name).then(function () {
    if (Store.groups().length === 0) {
      Nav.reset('p5');
    } else {
      Nav.reset('p2');
    }
  }, function () {
    if (button) {
      button.disabled = false;
      button.textContent = COPY.p1_save;
    }
    if (note) {
      note.textContent = COPY.p0_failed;
      note.className = 'field-note is-error';
    }
  });
}

/* ------------------------------------------------------------ P2 首页 */
function topbar(title, options) {
  const opts = options || {};
  return '<div class="topbar">' +
    (opts.back ? '<button class="icon-btn" data-act="back" aria-label="' + COPY.back + '">←</button>' : '') +
    (title ? '<h1 class="title">' + esc(title) + '</h1>' : '') +
    '<span class="spacer"></span>' +
    (opts.actions || '') +
    '</div>';
}

function groupRow(group) {
  const team = groupTeamSize(group);
  const meta = [COPY.p2_seeding(groupSeedingCount(group))];
  if (team > 0) meta.push(COPY.p2_team(team));
  const unread = group.unreadCount > 0
    ? '<span class="chip is-on row-tag">' + COPY.p2_unread(group.unreadCount) + '</span>'
    : '<span class="row-tag"></span>';
  return '<button class="row" data-act="open-group" data-id="' + group.id + '">' +
    '<span class="row-title">' + esc(group.name) + '</span>' +
    '<span class="row-meta">' + meta.join(' · ') + '</span>' +
    unread +
    '</button>';
}

function viewHome() {
  const groups = Store.groups().filter(function (group) {
    const query = State.search.trim().toLowerCase();
    return !query || group.name.toLowerCase().indexOf(query) >= 0;
  });

  const rows = groups.length
    ? groups.map(groupRow).join('')
    : '<p class="empty" style="padding:0 24px 20px">' + COPY.p2_empty + '</p>';

  return '<div class="screen">' +
    '<div class="topbar">' +
      '<label class="search"><svg viewBox="0 0 20 20" width="16" height="16" aria-hidden="true">' +
        '<path d="M9 3a6 6 0 1 0 3.6 10.8l3.3 3.3 1.4-1.4-3.3-3.3A6 6 0 0 0 9 3zm0 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8z" ' +
        'fill="currentColor"/></svg>' +
        '<input id="search" placeholder="' + COPY.p2_search + '" aria-label="' + COPY.p2_search + '" ' +
          'autocomplete="off" value="' + esc(State.search) + '"></label>' +
      '<span class="spacer"></span>' +
      '<button class="btn btn-text" data-act="settings">' + COPY.p2_settings + '</button>' +
      '<button class="btn btn-primary btn-round" data-act="new-group" aria-label="' + COPY.p5_title + '">' +
        COPY.p2_new_group + '</button>' +
    '</div>' +
    '<div class="content"><div class="page">' +
      '<div class="list" id="group-list">' + rows + '</div>' +
    '</div></div>' +
    '</div>';
}

function renderGroupRows() {
  const list = document.getElementById('group-list');
  if (!list) return;
  const query = State.search.trim().toLowerCase();
  const groups = Store.groups().filter(function (group) {
    return !query || group.name.toLowerCase().indexOf(query) >= 0;
  });
  list.innerHTML = groups.length
    ? groups.map(groupRow).join('')
    : '<p class="empty" style="padding:0 24px 20px">' + COPY.p2_empty + '</p>';
}

/* ------------------------------------------------------------ P3 群组 */
function fileRow(file) {
  const chips = [];
  // 「公开」只在公开条目出现，位置固定在状态 chip 左侧（public-swarm §6.2）；
  // 私有条目没有任何补位 chip，不出现「私有」字样。
  if (isPublicFile(file)) {
    chips.push('<span class="chip">' + COPY.p3_scope_public + '</span>');
  }
  chips.push('<span class="chip' + (file.state === FileState.SLEEPING ? '' : ' is-on') + '">' +
    fileStateLabel(file) + '</span>');
  if (fileInTeam(file)) {
    chips.push('<span class="chip is-on">' + COPY.p3_team(fileTeamSize(file)) + '</span>');
  }
  const progress = file.state === FileState.DOWNLOADING
    ? '<span class="cell-progress"><span class="progress"><i style="width:' + file.progressPercent +
      '%"></i></span><span class="cell-pct">' + file.progressPercent + '%</span></span>'
    : '';
  // 元数据没到手时没有尺寸可写：不显示「0 B」，等来源。（同 Android 的 P3 行）
  const size = fileAwaitingSource(file) ? '' : formatSize(file.sizeBytes);

  return '<button class="trow" data-act="open-file" data-id="' + file.id + '">' +
    '<span class="cell-main"><span class="cell-title">' + esc(file.name) + '</span>' + progress + '</span>' +
    '<span class="cell-size">' + size + '</span>' +
    '<span class="cell-seeders">' + COPY.p3_seeders(file.seeders) + '</span>' +
    '<span class="cell-chips">' + chips.join('') + '</span>' +
    '</button>';
}

/**
 * P3「添加文件」弹层：一个入口两项（public-swarm §6.1）—— 本机文件（壳的系统选择器）
 * 与磁力链接（输入框 + 导入）。不新开页面；点空白处或 Esc 关闭。
 */
function addFileModal() {
  if (!State.addOpen) return '';
  const scrim = '<div class="scrim" data-act="close-add"></div>';

  if (State.addMode === 'magnet') {
    const busy = State.magnetBusy;
    return scrim +
      '<div class="modal" role="dialog" aria-label="' + COPY.p3_add_magnet + '">' +
        '<div class="magnet-form">' +
          '<input class="input" id="magnet-input" autocomplete="off" spellcheck="false" ' +
            'placeholder="' + COPY.p3_magnet_hint + '" aria-label="' + COPY.p3_add_magnet + '" ' +
            'value="' + esc(State.magnetText) + '" data-autofocus' + (busy ? ' disabled' : '') + '>' +
          '<button class="btn btn-primary" data-act="import-magnet"' +
            (busy || !State.magnetText.trim() ? ' disabled' : '') + '>' +
            (busy ? COPY.p3_magnet_importing : COPY.p3_magnet_import) + '</button>' +
        '</div>' +
        (State.magnetNote
          ? '<p class="field-note' + (State.magnetNoteError ? ' is-error' : '') + '">' +
            esc(State.magnetNote) + '</p>'
          : '') +
      '</div>';
  }

  return scrim +
    '<div class="modal" role="dialog" aria-label="' + COPY.p3_add_file + '">' +
      '<button class="sheet-row" data-act="add-local">' + COPY.p3_add_local + '</button>' +
      '<button class="sheet-row" data-act="add-magnet">' + COPY.p3_add_magnet + '</button>' +
    '</div>';
}

function viewGroup() {
  const group = Store.group(State.groupId);
  if (!group) return viewHome();

  const body = group.files.length
    ? '<div class="table">' + group.files.map(fileRow).join('') + '</div>'
    : '<p class="empty">' + COPY.p3_empty + '</p>';

  return '<div class="screen">' +
    topbar(group.name, {
      back: true,
      actions: '<button class="btn btn-text" data-act="members">' +
        COPY.p3_members(group.members.length) + '</button>',
    }) +
    '<div class="content"><div class="page page-wide">' +
      '<div class="toolbar">' +
        '<button class="btn btn-primary" data-act="add-file">' + COPY.p3_add_file + '</button>' +
      '</div>' +
      '<p class="field-note is-error" id="add-note" hidden></p>' +
      body +
    '</div></div>' +
    addFileModal() +
    '</div>';
}

/* -------------------------------------------------------- P4 文件详情 */
function commentRow(comment) {
  return '<button class="comment" data-act="reply" data-id="' + comment.id + '">' +
    '<span class="comment-head"><span class="comment-author">' + esc(comment.author) + '</span>' +
      '<span class="comment-time">' + esc(comment.time) + '</span></span>' +
    '<span class="comment-body">' + esc(comment.body) + '</span>' +
    '</button>';
}

/**
 * P4 右上「…」：本轮只有「分享链接」一项 —— 单文件限速在页内，不重复收进菜单。
 * 分享服务不可用时整块不出现（与 Android 的 linkAvailable 同口径）；浏览器里点开就是普通菜单。
 */
function fileMenu(file) {
  if (!Store.shares().available) return '';
  const open = State.p4Menu;
  return '<span class="menu-wrap">' +
    '<button class="btn btn-text more-btn" data-act="more" aria-haspopup="menu" ' +
      'aria-expanded="' + open + '" aria-label="' + COPY.p4_more + '">…</button>' +
    (open
      ? '<div class="menu" role="menu">' +
          '<button class="menu-item" role="menuitem" data-act="share-link" data-id="' +
            esc(file.id) + '">' + COPY.p4_share_link + '</button>' +
        '</div>'
      : '') +
    '</span>';
}

function viewFile() {
  const group = Store.group(State.groupId);
  if (!group) return viewHome();
  const file = (State.fileId && Store.file(group.id, State.fileId)) || group.files[0];
  if (!file) return viewGroup();

  const comments = file.comments || [];
  const replyTarget = State.replyTo
    ? comments.find(function (comment) { return comment.id === State.replyTo; })
    : null;
  const placeholder = replyTarget ? COPY.p4_reply(replyTarget.author) : COPY.p4_comment_hint;

  const teaming = fileInTeam(file)
    ? '<p class="line">' + COPY.p4_teaming(fileTeamSize(file)) + '</p>'
    : '';
  const seeders = file.seeders > 0
    ? COPY.p4_seeders(file.seeders)
    : COPY.p4_waiting;

  // 「公开」固定在状态 chip 左侧；元数据没到手时不写尺寸（不是 0 B）。
  const publicChip = isPublicFile(file)
    ? '<span class="chip">' + COPY.p3_scope_public + '</span>'
    : '';
  const size = fileAwaitingSource(file)
    ? ''
    : '<span>' + formatSize(file.sizeBytes) + '</span>';
  // 公开条目的磁力摘要：一行，点一下复制整条链接；复制成功的提示在行内留 1.6 秒。
  const magnet = isPublicFile(file) && file.magnet
    ? '<div class="magnet-line">' +
        '<button class="magnet-digest" data-act="copy-magnet" data-id="' + file.id + '" ' +
          'title="' + esc(file.magnet) + '">' + esc(magnetDigest(file.magnet)) + '</button>' +
        (State.magnetCopied ? '<span class="copied">' + COPY.p6_copied + '</span>' : '') +
      '</div>'
    : '';
  // 「移除」只对公开条目出现（public-swarm §5.2）：私有条目的正确动作是「休眠」。
  const removeAction = isPublicFile(file)
    ? '<button class="btn btn-text" data-act="remove-file" data-id="' + file.id + '">' +
      COPY.p4_remove + '</button>'
    : '';

  return '<div class="screen">' +
    topbar(file.name, { back: true, actions: removeAction + fileMenu(file) }) +
    '<div class="content"><div class="page page-wide">' +
      '<div class="file-meta">' +
        publicChip +
        '<span class="chip' + (file.state === FileState.SLEEPING ? '' : ' is-on') + '">' +
          fileStateLabel(file) + '</span>' +
        size +
        '<span>' + COPY.p4_source(file.sourceMember) + '</span>' +
      '</div>' +
      magnet +
      '<div class="file-grid">' +
        '<section class="panel stack">' +
          '<div class="stack-tight">' +
            '<div class="progress"><i style="width:' + file.progressPercent + '%"></i></div>' +
            '<div class="rate-line">' +
              '<span class="pct">' + file.progressPercent + '%</span>' +
              '<span>↓' + formatRate(file.downloadRateBps) + '</span>' +
              '<span>↑' + formatRate(file.uploadRateBps) + '</span>' +
            '</div>' +
          '</div>' +
          teaming +
          '<p class="line">' + seeders + '</p>' +
          '<hr class="divider">' +
          '<div class="setting-row">' +
            '<span class="setting-label">' + COPY.p4_activate + '</span>' +
            '<button class="switch" role="switch" data-act="toggle-active" ' +
              'aria-checked="' + (file.state !== FileState.SLEEPING) + '" ' +
              'aria-label="' + COPY.p4_activate + ' / ' + COPY.p4_sleep + '"></button>' +
            '<span class="setting-label">' + COPY.p4_sleep + '</span>' +
          '</div>' +
          '<div class="setting-row">' +
            '<span class="setting-label">' + COPY.p4_upload_limit + '</span>' +
            '<span class="spacer"></span>' +
            '<input class="input input-num" id="upload-limit" inputmode="numeric" ' +
              'value="' + (file.uploadLimitKbps > 0 ? file.uploadLimitKbps : '') + '" ' +
              'placeholder="' + COPY.p7_unlimited + '" aria-label="' + COPY.p4_upload_limit + '">' +
            '<span class="unit">' + COPY.unit_kbps + '</span>' +
          '</div>' +
          '<p class="line" id="upload-limit-readback">' + fileLimitReadback(file) + '</p>' +
        '</section>' +
        '<section class="panel comments-panel">' +
          '<div class="comment-list">' +
            (comments.length ? comments.map(commentRow).join('') : '<p class="empty" style="margin:0">' +
              COPY.p4_no_comments + '</p>') +
          '</div>' +
          '<div class="comment-form">' +
            '<input class="input" id="comment-input" placeholder="' + esc(placeholder) + '" ' +
              'value="' + esc(State.draftComment) + '" aria-label="' + COPY.p4_comment_hint + '">' +
            '<button class="btn btn-primary" data-act="send-comment"' +
              (State.draftComment.trim() ? '' : ' disabled') + '>' + COPY.p4_send + '</button>' +
          '</div>' +
        '</section>' +
      '</div>' +
    '</div></div>' +
    '</div>';
}

/* ---------------------------------------------------------- P5 新群组 */
function viewNewGroup() {
  const modal = State.createOpen
    ? '<div class="scrim" data-act="close-create"></div>' +
      '<div class="modal" role="dialog" aria-label="' + COPY.p5_create_group + '">' +
        '<h2 class="modal-title">' + COPY.p5_create_group + '</h2>' +
        '<input class="input" id="group-name" maxlength="20" data-autofocus ' +
          'value="' + esc(COPY.p5_group_name_default(localName())) + '" aria-label="' + COPY.p5_title + '">' +
        '<div class="modal-actions">' +
          '<button class="btn btn-primary" data-act="confirm-create">' + COPY.p5_create + '</button>' +
        '</div>' +
      '</div>'
    : '';

  return '<div class="screen">' +
    topbar(COPY.p5_title, { back: Nav.stack.length > 1 }) +
    '<div class="content"><div class="page page-narrow">' +
      '<div class="card stack">' +
        '<input class="input" id="invite-code" maxlength="8" autocomplete="off" spellcheck="false" ' +
          'placeholder="' + COPY.p5_invite_hint + '" aria-label="' + COPY.p5_invite_hint + '" ' +
          'value="' + esc(State.inviteCode || '') + '" data-autofocus>' +
        '<p class="field-note" id="invite-note" hidden>' + COPY.p5_invite_wrong + '</p>' +
        '<button class="btn btn-primary btn-block" data-act="join">' + COPY.p5_join + '</button>' +
        '<button class="btn btn-text btn-block" data-act="create-group">' + COPY.p5_create_group + '</button>' +
      '</div>' +
    '</div></div>' +
    modal +
    '</div>';
}

function joinGroup() {
  const input = document.getElementById('invite-code');
  const note = document.getElementById('invite-note');
  const button = app.querySelector('[data-act="join"]');
  const code = input ? input.value.trim().toUpperCase() : '';

  if (code.length !== 8) {
    if (note) note.hidden = false;
    if (input) input.focus();
    return;
  }

  if (button) {
    button.disabled = true;
    button.textContent = COPY.p5_joining;
  }
  // 只发后 8 位；daemon 负责发现（mDNS + 手动地址）与握手。
  Store.joinGroup(code).then(function (joined) {
    if (!joined) throw new Error('join failed');
    // spec §3 P5 出口：加入成功 → P3，且 P3 的返回必须落到 P2。
    State.inviteCode = '';
    Nav.reset('p2');
    Nav.go('p3', joined.id);
  }).catch(function () {
    if (note) note.hidden = false;
    if (button) {
      button.disabled = false;
      button.textContent = COPY.p5_join;
    }
  });
}

/**
 * 壳桥：页面在 WebView2 里由壳承载时，window.chrome.webview 就是壳本身。
 * 点「添加文件」发 {type:'pick-file'}，壳弹系统文件选择器，把选中的绝对路径回成
 * {type:'file-picked', path:'…'} —— 这正是 POST /api/groups/{gid}/files 的入参
 * （docs/daemon-api.md）。浏览器里没有这个对象，也就没有那条上行通道。
 */
const Shell = {
  available: !!(window.chrome && window.chrome.webview && window.chrome.webview.postMessage),
  pending: false,
  listen: function () {
    if (!Shell.available) return;
    window.chrome.webview.addEventListener('message', function (event) {
      const message = event.data || {};
      if (message.type === 'file-picked') {
        Shell.pending = false;
        addPickedFile(message.path);
      } else if (message.type === 'pick-file-cancelled') {
        Shell.pending = false;
        setAddBusy(false);
      }
    });
  },
};

function setAddNote(text) {
  const note = document.getElementById('add-note');
  if (!note) return;
  note.textContent = text || '';
  note.hidden = !text;
}

function setAddBusy(busy) {
  const button = app.querySelector('[data-act="add-file"]');
  if (button) button.disabled = !!busy;
}

/** 点「添加文件」：路径由壳的系统选择器给，页面不猜。 */
function pickFile() {
  const group = Store.group(State.groupId);
  if (!group) return;
  if (!Shell.available) {
    setAddNote(COPY.p3_add_need_shell);
    return;
  }
  if (Shell.pending) return;
  Shell.pending = true;
  setAddNote('');
  setAddBusy(true);
  window.chrome.webview.postMessage({ type: 'pick-file' });
}

/** 壳回传的本机绝对路径 → daemon。 */
function addPickedFile(path) {
  const group = Store.group(State.groupId);
  setAddBusy(false);
  if (!group || !path) return;
  Store.addFile(group.id, path).then(function (created) {
    if (!created) return;
    // spec 流程 4：添加文件完成后直接进 P4，开关默认开。
    State.fileId = created.id;
    State.draftComment = '';
    State.replyTo = null;
    Nav.go('p4', created.id);
  }).catch(function (error) {
    setAddNote((error && error.message) || COPY.p3_add_failed);
  });
}

/** 打开「添加文件」弹层（menu = 两项；magnet = 直接进输入态）。 */
function openAddSheet(mode) {
  State.addOpen = true;
  State.addMode = mode === 'magnet' ? 'magnet' : 'menu';
  State.magnetNote = '';
  State.magnetNoteError = false;
  render();
}

function closeAddSheet() {
  if (State.magnetBusy) return;   // 导入中不许关（同 Android 的弹层）
  State.addOpen = false;
  State.addMode = 'menu';
  State.magnetText = '';
  State.magnetNote = '';
  State.magnetNoteError = false;
  render();
}

/** 「本机文件」：先关弹层，再走原来的壳选择器链路。 */
function pickLocalFile() {
  if (State.magnetBusy) return;
  State.addOpen = false;
  State.addMode = 'menu';
  render();
  pickFile();
}

/** 这四种是「被拒」（非法 / 重复 / 会话 / 群没了），文案染红；无源 / 超时只是还没成。 */
function magnetRejected(code) {
  return code === 'invalid_link' || code === 'duplicate' ||
    code === 'session_error' || code === 'no_group';
}

/**
 * 「磁力链接」→ POST /api/groups/{gid}/files/import-magnet。
 *
 * 与 Android 同口径：内核最多等 60 s 元数据。ok=true 直接进 P4；ok=false 时条目已经建好
 * （no_source / timeout / bad_metadata），内核一句话留在输入框下面，列表里显示「等待来源」，
 * 不弹窗、不要求重试。连请求都失败（非法 / 重复 / 503）才按错误处理。
 */
function importMagnet() {
  const group = Store.group(State.groupId);
  const input = document.getElementById('magnet-input');
  const magnet = (input ? input.value : State.magnetText).trim();
  if (!group || !magnet || State.magnetBusy) return;

  State.magnetText = magnet;
  State.magnetBusy = true;
  State.magnetNote = '';
  State.magnetNoteError = false;
  render();

  Store.importMagnet(group.id, magnet, '', 60000).then(function (result) {
    State.magnetBusy = false;
    if (result && result.ok) {
      State.addOpen = false;
      State.addMode = 'menu';
      State.magnetText = '';
      State.magnetNote = '';
      State.fileId = result.fileId;
      State.draftComment = '';
      State.replyTo = null;
      Nav.go('p4', result.fileId);
      return;
    }
    State.magnetNote = (result && result.message) || COPY.p3_add_failed;
    State.magnetNoteError = magnetRejected(result && result.code);
    render();
  }, function (error) {
    State.magnetBusy = false;
    State.magnetNote = (error && error.message) || COPY.p3_add_failed;
    State.magnetNoteError = true;
    render();
  });
}

/** P4 磁力摘要：点一下复制整条链接。 */
function copyMagnet(fileId) {
  const group = Store.group(State.groupId);
  const file = group && Store.file(group.id, fileId || State.fileId);
  if (!file || !file.magnet) return;
  copyText(file.magnet, function () {
    State.magnetCopied = true;
    render();
    if (State.magnetCopiedTimer) clearTimeout(State.magnetCopiedTimer);
    State.magnetCopiedTimer = setTimeout(function () {
      State.magnetCopied = false;
      render();
    }, 1600);
  });
}

function openGroup(groupId) {
  State.groupId = groupId;
  Nav.go('p3');
}

/* ------------------------------------------------------------ P6 成员 */
function memberRow(member) {
  const meta = [];
  if (member.online) meta.push(COPY.p6_online);
  meta.push(COPY.p6_seeding(member.seedingCount));
  return '<div class="row">' +
    '<span class="row-title">' + esc(member.name) + '</span>' +
    '<span class="row-meta">' + meta.join(' · ') + '</span>' +
    '<span class="row-end">' + esc(member.platform) + '</span>' +
    '</div>';
}

function viewMembers() {
  const group = Store.group(State.groupId);
  if (!group) return viewHome();

  const list = group.members.length
    ? '<div class="list">' + group.members.map(memberRow).join('') + '</div>'
    : '<p class="empty">' + COPY.p6_empty + '</p>';

  return '<div class="screen">' +
    topbar(COPY.p6_title, { back: true }) +
    '<div class="content"><div class="page">' +
      list +
      '<div class="invite">' +
        '<span class="field-label">' + COPY.p6_invite + '</span>' +
        '<button class="invite-code" data-act="copy-invite">' + group.inviteCode + '</button>' +
        '<span class="spacer"></span>' +
        (State.copied ? '<span class="copied">' + COPY.p6_copied + '</span>' : '') +
      '</div>' +
    '</div></div>' +
    '</div>';
}

/**
 * 复制一段文本：WebView2 里 navigator.clipboard 可能被拒，退回 execCommand；
 * 两条都失败就不回调（不假装复制成功）。邀请码与磁力摘要共用这一条。
 */
function copyText(text, done) {
  const fallback = function () {
    const area = document.createElement('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.style.position = 'fixed';
    area.style.opacity = '0';
    document.body.appendChild(area);
    area.select();
    let ok = false;
    try {
      ok = document.execCommand('copy');
    } catch (error) {
      ok = false;
    }
    document.body.removeChild(area);
    if (ok) done();
  };
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(done, fallback);
  } else {
    fallback();
  }
}

function copyInviteCode() {
  const group = Store.group(State.groupId);
  if (!group) return;
  copyText(group.inviteCode, function () {
    State.copied = true;
    render();
    setTimeout(function () {
      State.copied = false;
      render();
    }, 1600);
  });
}

/* ------------------------------------------------------------ P7 设置 */
function viewSettings() {
  const device = Store.device();
  const groups = Store.groups();
  // P9 的入口只在分享服务可用时出现；计数只数仍然有效的链接（与 P9 列表同一个口径）。
  const shareCount = Store.activeShares().length;

  const notifications = groups.length
    ? groups.map(function (group) {
        const chips = [
          [NotificationLevel.SYSTEM_BUBBLE, COPY.p7_bubble],
          [NotificationLevel.IN_APP_ONLY, COPY.p7_in_app],
          [NotificationLevel.MUTED, COPY.p7_muted],
        ].map(function (entry) {
          const selected = group.notificationLevel === entry[0];
          return '<button class="chip chip-filter' + (selected ? ' is-selected' : '') + '" ' +
            'data-act="notify" data-level="' + entry[0] + '" data-id="' + group.id + '" ' +
            'role="radio" aria-checked="' + selected + '">' + entry[1] + '</button>';
        }).join('');
        return '<div class="stack-tight"><span class="row-title">' + esc(group.name) + '</span>' +
          '<div class="chip-row">' + chips + '</div></div>';
      }).join('')
    : '<p class="empty" style="margin:0">' + COPY.p2_empty + '</p>';

  return '<div class="scrim" data-act="close-settings"></div>' +
    '<aside class="drawer" role="dialog" aria-label="' + COPY.p7_title + '">' +
      '<header class="drawer-head">' +
        '<h1 class="title">' + COPY.p7_title + '</h1>' +
        '<span class="spacer"></span>' +
        '<button class="icon-btn" data-act="close-settings" aria-label="' + COPY.close + '">×</button>' +
      '</header>' +
      '<div class="drawer-body stack">' +
        '<label class="field-label" for="settings-name">' + COPY.p7_user_name + '</label>' +
        '<input class="input" id="settings-name" maxlength="20" value="' + esc(localName()) + '">' +

        '<h2 class="section-title">' + COPY.p7_global_limit + '</h2>' +
        '<div class="limit-row">' +
          '<label class="limit-field"><span class="field-label">' + COPY.p7_upload + '</span>' +
            '<span class="input-group"><input class="input input-num" id="limit-up" inputmode="numeric" ' +
              (device.unlimited ? 'disabled ' : '') +
              'value="' + (device.uploadLimitKbps > 0 ? device.uploadLimitKbps : '') + '">' +
              '<span class="unit">' + COPY.unit_kbps + '</span></span></label>' +
          '<label class="limit-field"><span class="field-label">' + COPY.p7_download + '</span>' +
            '<span class="input-group"><input class="input input-num" id="limit-down" inputmode="numeric" ' +
              (device.unlimited ? 'disabled ' : '') +
              'value="' + (device.downloadLimitKbps > 0 ? device.downloadLimitKbps : '') + '">' +
              '<span class="unit">' + COPY.unit_kbps + '</span></span></label>' +
        '</div>' +
        '<div class="setting-row">' +
          '<span class="setting-label">' + COPY.p7_unlimited + '</span>' +
          '<span class="spacer"></span>' +
          '<button class="switch" role="switch" data-act="toggle-unlimited" ' +
            'aria-checked="' + device.unlimited + '" aria-label="' + COPY.p7_unlimited + '"></button>' +
        '</div>' +
        '<div class="kv"><span class="field-label">' + COPY.p7_readback + '</span>' +
          '<span id="limit-readback">' + globalLimitReadback(device) + '</span></div>' +

        '<h2 class="section-title">' + COPY.p7_notification + '</h2>' +
        notifications +

        /* 跨网发现：开关是落盘的设置，地址只读。tracker 在内核启动时装配，
           所以设置与内核现状不一致时补一行「重启后生效」（restartRequired 由 daemon 给）。 */
        '<div class="setting-row">' +
          '<span class="setting-label">' + COPY.p7_tracker + '</span>' +
          '<span class="spacer"></span>' +
          '<button class="switch" role="switch" data-act="toggle-tracker" ' +
            'aria-checked="' + device.trackerEnabled + '" aria-label="' + COPY.p7_tracker + '"></button>' +
        '</div>' +
        '<p class="field-note tracker-base" id="tracker-base">' + esc(device.trackerBaseUrl) + '</p>' +
        (device.trackerRestartRequired
          ? '<p class="field-note" id="tracker-pending">' + COPY.p7_tracker_pending + '</p>'
          : '') +

        /* P9 唯一入口（link-share.md §3 P9）：分享服务不可用时这一行不出现，
           与 Android 的 linkAvailable 同口径。计数只数有效链接。 */
        (Store.shares().available
          ? '<button class="drawer-item" data-act="share-manage">' +
              '<span>' + COPY.p7_share_manage + '</span>' +
              '<span class="spacer"></span>' +
              (shareCount > 0 ? '<span class="drawer-count">' + shareCount + '</span>' : '') +
            '</button>'
          : '') +

        '<div class="kv"><span class="field-label">' + COPY.p7_network_status + '</span>' +
          '<span>' + networkLabel() + '</span></div>' +
      '</div>' +
    '</aside>';
}

/* ------------------------------------------------------- P8 链接分享 */
const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

/**
 * 剩余有效期：与 Android 的 expiryLabel 同一口径（向上取整，刚建好显示「7 天后失效」）。
 * 「现在」取 max(本机时间, 创建时间)：Worker 的时钟比本机快一两秒时，直接减会算出 7 天零几秒、
 * 显示成「8 天后失效」。剩余时间不可能超过从创建时刻算起的整段有效期，所以用创建时间兜一下。
 */
function expiryLabel(expiresAt, createdAt) {
  const now = Math.max(Date.now(), Number(createdAt) || 0);
  const left = Number(expiresAt) - now;
  if (!(left > 0)) return COPY.p8_expired;
  if (left >= DAY_MS) return COPY.p8_expires_days(Math.ceil(left / DAY_MS));
  if (left >= HOUR_MS) return COPY.p8_expires_hours(Math.ceil(left / HOUR_MS));
  return COPY.p8_expires_minutes(Math.max(1, Math.ceil(left / MINUTE_MS)));
}

/**
 * 状态行：一条链接的一辈子。daemon 的 status 是细分状态，phase 只回答「有没有人在取」；
 * 这里把细分状态翻成一句话 —— 等待访客 / 访客已加入 / 传输中 xx% / 已结束（+ 原因）。
 */
function shareStatusLabel(share) {
  if (share.status === ShareStatus.WAITING) return COPY.p8_waiting;
  if (share.status === ShareStatus.CONNECTING) return COPY.p8_incoming;
  if (share.status === ShareStatus.TRANSFERRING) return COPY.p8_sending(share.progressPercent);
  if (share.status === ShareStatus.DONE) return COPY.p8_finished;
  if (share.status === ShareStatus.FAILED) return COPY.p8_finished_failed;
  if (share.status === ShareStatus.REVOKED) return COPY.p8_finished_revoked;
  if (share.status === ShareStatus.EXPIRED) return COPY.p8_finished_expired;
  if (share.status === ShareStatus.FILE_MISSING) return COPY.p8_finished_missing;
  return COPY.p8_finished;
}

function shareTerminal(share) {
  return share.status === ShareStatus.DONE || share.status === ShareStatus.FAILED ||
    share.status === ShareStatus.REVOKED || share.status === ShareStatus.EXPIRED ||
    share.status === ShareStatus.FILE_MISSING;
}

/** 创建 / 撤销失败的 code → 一句话；不认识的码也不把 daemon 的英文原因甩到界面上。 */
function shareFailureLabel(code, fallback) {
  if (code === 'E_LINK_UNAVAILABLE') return COPY.p8_not_configured;
  if (code === 'E_LINK_REGISTRY') return COPY.p8_server_unreachable;
  if (code === 'E_LINK_TOO_MANY') return COPY.p8_too_many;
  if (code === 'E_NO_LOCAL_FILE') return COPY.p8_no_local;
  if (code === 'E_FILE_CHANGED') return COPY.p8_file_changed;
  if (code === 'E_NO_FILE') return COPY.p8_no_file;
  return fallback || COPY.p8_create_failed;
}

/**
 * P8：二维码 + 链接 + 复制 + 有效期 + 状态行。二维码用 qrText 本地画（js/qrcode.js），
 * 不调用任何在线二维码服务；二维码 = 链接 = 凭据，撤销不在这页（撤销是 P9 的动作）。
 */
function viewShare() {
  const share = State.shareToken ? Store.share(State.shareToken) : null;
  let body = '';

  if (share) {
    const link = share.link || share.qrText;
    const revoked = share.status === ShareStatus.REVOKED;
    const transfer = share.status === ShareStatus.TRANSFERRING;
    const qr = !revoked && (share.qrText || link) ? QRCode.svg(share.qrText || link) : '';

    body =
      (qr ? '<div class="qr-box">' + qr + '</div>' : '') +
      (revoked
        ? ''
        : '<p class="share-link" title="' + esc(link) + '">' + esc(link) + '</p>' +
          '<div class="share-actions">' +
            '<button class="btn btn-primary" data-act="copy-link">' + COPY.p8_copy + '</button>' +
            (State.shareCopied ? '<span class="copied">' + COPY.p6_copied + '</span>' : '') +
          '</div>') +
      '<div class="share-state">' +
        '<p class="share-status' + (shareTerminal(share) ? ' is-done' : '') + '">' +
          shareStatusLabel(share) + '</p>' +
        (transfer
          ? '<div class="progress share-progress"><i style="width:' +
            Math.max(0, Math.min(100, share.progressPercent)) + '%"></i></div>'
          : '') +
        '<p class="share-expiry">' + expiryLabel(share.expiresAt, share.createdAt) + '</p>' +
      '</div>';
  } else if (State.shareCreating) {
    body = '<p class="share-status share-creating">' + COPY.p8_creating + '</p>';
  } else if (State.shareError) {
    body = '<p class="share-status is-error">' + esc(State.shareError) + '</p>' +
      (State.shareFileId
        ? '<button class="btn btn-primary" data-act="retry-share">' + COPY.p0_retry + '</button>'
        : '');
  } else {
    body = '<p class="share-status is-error">' + COPY.p8_not_found + '</p>';
  }

  return '<div class="screen">' +
    topbar(COPY.p8_title, { back: Nav.stack.length > 1 }) +
    '<div class="content"><div class="page share-page">' + body + '</div></div>' +
    '</div>';
}

/* -------------------------------------------------- P9 分享管理（列表） */
/** 列表口径：仍有效的链接才出现；撤销 / 过期 / 文件不见了直接移出，不做「已撤销」chip。 */
function shareRow(share) {
  return '<div class="share-row">' +
    '<button class="share-open" data-act="open-share" data-id="' + esc(share.token) + '">' +
      '<span class="share-name">' + esc(share.fileName) + '</span>' +
      '<span class="share-meta">' + expiryLabel(share.expiresAt, share.createdAt) + ' · ' +
        COPY.p9_visits(share.transfers) + '</span>' +
    '</button>' +
    '<button class="btn btn-text is-danger share-revoke" data-act="revoke-share" data-id="' +
      esc(share.token) + '">' + COPY.p9_revoke + '</button>' +
    '</div>';
}

/** 撤销是不可逆动作，给一次确认；点空白处 / 取消 / Esc 都退回去。 */
function revokeConfirm() {
  if (!State.revokeToken) return '';
  const share = Store.share(State.revokeToken);
  return '<div class="scrim" data-act="close-revoke"></div>' +
    '<div class="modal" role="dialog" aria-label="' + COPY.p9_revoke_title + '">' +
      '<h2 class="modal-title">' + COPY.p9_revoke_title + '</h2>' +
      '<p class="line">' + COPY.p9_revoke_confirm + '</p>' +
      (share ? '<p class="field-note">' + esc(share.fileName) + '</p>' : '') +
      '<div class="modal-actions">' +
        '<button class="btn btn-text" data-act="close-revoke">' + COPY.p9_cancel + '</button>' +
        '<button class="btn btn-primary" data-act="confirm-revoke">' + COPY.p9_revoke + '</button>' +
      '</div>' +
    '</div>';
}

function viewShareList() {
  const data = Store.shares();
  const items = Store.activeShares();
  let body;

  if (data.error) {
    body = '<p class="empty">' + COPY.p8_read_failed + '</p>' +
      '<button class="btn btn-text" data-act="retry-shares">' + COPY.p0_retry + '</button>';
  } else if (!data.loaded && !items.length) {
    body = '<p class="empty">' + COPY.p9_loading + '</p>';
  } else if (!items.length) {
    body = '<p class="empty">' + COPY.p9_empty + '</p>';
  } else {
    body = '<div class="list share-list">' + items.map(shareRow).join('') + '</div>';
  }

  return '<div class="screen">' +
    topbar(COPY.p9_title, { back: Nav.stack.length > 1 }) +
    '<div class="content"><div class="page">' +
      body +
      (State.revokeError
        ? '<p class="field-note is-error">' + esc(State.revokeError) + '</p>'
        : '') +
    '</div></div>' +
    revokeConfirm() +
    '</div>';
}

/**
 * 取证别名 p8-share / p9-list 的前置步骤：确保 daemon 里有一条有效分享。
 * 已有就用最近一条；没有就用本群第一个私有文件建一条（7 天）。
 * 只在 ?route=p8-share / ?route=p9-list 时走 —— 正常导航不会凭空建链接。
 */
function ensureShareForDemo() {
  return Store.loadShares(true).then(function () {
    const existing = Store.activeShares()[0];
    if (existing) return existing.token;
    const group = Store.groups()[0];
    if (!group) throw new Error('no group');
    const privates = group.files.filter(function (file) { return !isPublicFile(file); });
    const file = privates[0] || group.files[0];
    if (!file) throw new Error('no file');
    return Store.createShare(file.id, 7).then(function (share) {
      return share.token;
    });
  });
}

/** P4「分享链接」：先落 P8 显示「创建中」，POST /api/shares 回来后换成二维码。 */
function startShareCreate(fileId) {
  const id = fileId || State.fileId;
  if (!id) return;
  State.p4Menu = false;
  State.shareFileId = id;
  State.shareToken = '';
  State.shareError = '';
  State.shareCopied = false;
  State.shareCreating = true;
  Nav.go('p8');
  Store.createShare(id, 7).then(function (share) {
    State.shareCreating = false;
    State.shareToken = share.token;
    if (State.route === 'p8') Nav.replace('p8', share.token);
  }, function (error) {
    State.shareCreating = false;
    State.shareError = shareFailureLabel(error && error.code);
    render();
  });
}

function copyShareLink() {
  const share = State.shareToken ? Store.share(State.shareToken) : null;
  if (!share || !share.link) return;
  copyText(share.link, function () {
    State.shareCopied = true;
    render();
    if (State.shareCopiedTimer) clearTimeout(State.shareCopiedTimer);
    State.shareCopiedTimer = setTimeout(function () {
      State.shareCopied = false;
      render();
    }, 1600);
  });
}

function confirmRevokeShare() {
  const token = State.revokeToken;
  if (!token) return;
  State.revokeToken = '';
  Store.revokeShare(token).then(function () {
    State.revokeError = '';
    render();
  }, function (error) {
    State.revokeError = shareFailureLabel(error && error.code, COPY.p9_revoke_failed);
    render();
  });
}

/* -------------------------------------------------------------- 事件 */
document.addEventListener('click', function (event) {
  const target = event.target.closest('[data-act]');

  // P4「…」菜单：点菜单外面就收起。点的是别的动作时先收起再继续分派，不吞掉那次点击。
  if (State.p4Menu && !(target && (target.dataset.act === 'more' || target.dataset.act === 'share-link'))) {
    State.p4Menu = false;
    if (!target) {
      render();
      return;
    }
  }

  if (!target) return;
  const act = target.dataset.act;

  switch (act) {
    case 'retry':
      Store.boot().then(function () {
        render();
      });
      break;

    case 'back':
      Nav.back();
      break;

    case 'open-group':
      openGroup(target.dataset.id);
      break;

    case 'open-file':
      State.fileId = target.dataset.id;
      State.draftComment = '';
      State.replyTo = null;
      Nav.go('p4', target.dataset.id);
      break;

    case 'members':
      Nav.go('p6');
      break;

    case 'settings':
      Nav.go('p7');
      break;

    case 'more':
      State.p4Menu = !State.p4Menu;
      render();
      break;

    case 'share-link':
      startShareCreate(target.dataset.id);
      break;

    case 'copy-link':
      copyShareLink();
      break;

    case 'retry-share':
      startShareCreate(State.shareFileId);
      break;

    case 'open-share':
      State.revokeError = '';
      Nav.go('p8', target.dataset.id);
      break;

    case 'revoke-share':
      State.revokeToken = target.dataset.id;
      State.revokeError = '';
      render();
      break;

    case 'close-revoke':
      State.revokeToken = '';
      render();
      break;

    case 'confirm-revoke':
      confirmRevokeShare();
      break;

    case 'share-manage':
      Nav.go('p9');
      break;

    case 'retry-shares':
      Store.loadShares(true);
      break;

    case 'close-settings':
      if (State.route === 'p7') Nav.back();
      break;

    case 'new-group':
      State.createOpen = false;
      Nav.go('p5');
      break;

    case 'add-file':
      openAddSheet('menu');
      break;

    case 'close-add':
      closeAddSheet();
      break;

    case 'add-local':
      pickLocalFile();
      break;

    case 'add-magnet':
      State.addMode = 'magnet';
      State.magnetNote = '';
      State.magnetNoteError = false;
      render();
      break;

    case 'import-magnet':
      importMagnet();
      break;

    case 'copy-magnet':
      copyMagnet(target.dataset.id);
      break;

    case 'remove-file': {
      const group = Store.group(State.groupId);
      const file = group && Store.file(group.id, target.dataset.id || State.fileId);
      if (!group || !file) break;
      // 移除后条目会从列表消失：先回 P3，免得 P4 退回列表里的第一条。
      // 只藏本机列表，不广播、不删磁盘文件（docs/daemon-api.md §3.3）。
      Nav.back();
      Store.removeFile(group.id, file.id).catch(function (error) {
        setAddNote((error && error.message) || COPY.p3_add_failed);
      });
      break;
    }

    case 'save-name':
      saveUserName();
      break;

    case 'toggle-active': {
      const group = Store.group(State.groupId);
      const file = group && Store.file(group.id, State.fileId);
      if (!file) break;
      Store.setFileActive(group.id, file.id, file.state === FileState.SLEEPING);
      render();
      break;
    }

    case 'reply':
      State.replyTo = target.dataset.id;
      updateCommentPlaceholder();
      break;

    case 'send-comment':
      sendComment();
      break;

    case 'copy-invite':
      copyInviteCode();
      break;

    case 'join':
      joinGroup();
      break;

    case 'create-group':
      State.createOpen = true;
      render();
      break;

    case 'close-create':
      State.createOpen = false;
      render();
      break;

    case 'confirm-create': {
      const input = document.getElementById('group-name');
      const fallback = COPY.p5_group_name_default(localName());
      const name = (input && input.value.trim()) || fallback;
      State.createOpen = false;
      render();
      Store.createGroup(name).then(function (created) {
        Nav.reset('p2');
        Nav.go('p3', created.id);
      }, function () {
        // 建群失败：留在 P5，让用户再点一次（不编造成功）
        render();
      });
      break;
    }

    case 'notify': {
      Store.setNotificationLevel(target.dataset.id, target.dataset.level);
      render();
      break;
    }

    case 'toggle-unlimited': {
      const device = Store.device();
      Store.setUnlimited(!device.unlimited);
      render();
      break;
    }

    case 'toggle-tracker': {
      const device = Store.device();
      Store.setTrackerEnabled(!device.trackerEnabled);
      render();
      break;
    }

    default:
      break;
  }
});

document.addEventListener('input', function (event) {
  const el = event.target;

  switch (el.id) {
    case 'search':
      State.search = el.value;
      renderGroupRows();
      break;

    case 'invite-code': {
      const code = el.value.toUpperCase().replace(/[^A-Z0-9]/g, '').slice(0, 8);
      el.value = code;
      State.inviteCode = code;
      const note = document.getElementById('invite-note');
      if (note) note.hidden = true;
      const button = app.querySelector('[data-act="join"]');
      if (button) button.disabled = false;
      break;
    }

    case 'user-name': {
      const note = document.getElementById('user-name-note');
      if (note && note.classList.contains('is-error')) {
        note.textContent = COPY.p1_max;
        note.className = 'field-note';
      }
      break;
    }

    case 'comment-input': {
      State.draftComment = el.value;
      const button = app.querySelector('[data-act="send-comment"]');
      if (button) button.disabled = !State.draftComment.trim();
      break;
    }

    case 'magnet-input': {
      State.magnetText = el.value;
      const button = app.querySelector('[data-act="import-magnet"]');
      if (button) button.disabled = !State.magnetText.trim() || State.magnetBusy;
      break;
    }

    case 'upload-limit': {
      const group = Store.group(State.groupId);
      const file = group && Store.file(group.id, State.fileId);
      const digits = digitsOnly(el.value, 6);
      el.value = digits;
      if (file) Store.setFileUploadLimit(group.id, file.id, parseInt(digits, 10) || 0);
      break;
    }

    case 'settings-name': {
      Store.saveUserName(el.value);
      break;
    }

    case 'limit-up':
    case 'limit-down': {
      const digits = digitsOnly(el.value, 6);
      el.value = digits;
      if (el.id === 'limit-up') Store.setUploadLimit(parseInt(digits, 10) || 0);
      else Store.setDownloadLimit(parseInt(digits, 10) || 0);
      break;
    }

    default:
      break;
  }
});

document.addEventListener('keydown', function (event) {
  if (event.key === 'Escape') {
    if (State.p4Menu) {
      State.p4Menu = false;
      render();
      return;
    }
    if (State.revokeToken) {
      State.revokeToken = '';
      render();
      return;
    }
    if (State.addOpen) {
      closeAddSheet();
      return;
    }
    if (State.createOpen) {
      State.createOpen = false;
      render();
      return;
    }
    if (State.route === 'p7') {
      Nav.back();
      return;
    }
    if (State.route !== 'p0' && State.route !== 'p1' && State.route !== 'p2') {
      Nav.back();
    }
    return;
  }

  if (event.altKey && event.key === 'ArrowLeft') {
    Nav.back();
    return;
  }

  if (event.key !== 'Enter') return;
  const id = event.target && event.target.id;
  if (id === 'user-name') {
    event.preventDefault();
    saveUserName();
  } else if (id === 'invite-code') {
    event.preventDefault();
    joinGroup();
  } else if (id === 'group-name') {
    event.preventDefault();
    const button = app.querySelector('[data-act="confirm-create"]');
    if (button) button.click();
  } else if (id === 'comment-input') {
    event.preventDefault();
    sendComment();
  } else if (id === 'magnet-input') {
    event.preventDefault();
    importMagnet();
  } else if (!id && State.route === 'p3' && !State.addOpen &&
             !(event.target && event.target.closest &&
               event.target.closest('[data-act="add-file"]'))) {
    // P3 没有输入框：Enter 打开「添加文件」弹层（按钮自己聚焦时浏览器会补一次 click，跳过）
    event.preventDefault();
    openAddSheet('menu');
  }
});

function updateCommentPlaceholder() {
  const input = document.getElementById('comment-input');
  if (!input) return;
  const group = Store.group(State.groupId);
  const file = group && Store.file(group.id, State.fileId);
  const comments = (file && file.comments) || [];
  const target = State.replyTo
    ? comments.find(function (comment) { return comment.id === State.replyTo; })
    : null;
  input.placeholder = target ? COPY.p4_reply(target.author) : COPY.p4_comment_hint;
  input.focus();
}

function sendComment() {
  const input = document.getElementById('comment-input');
  const group = Store.group(State.groupId);
  const file = group && Store.file(group.id, State.fileId);
  if (!input || !file) return;
  const text = input.value.trim();
  if (!text) return;
  Store.addComment(group.id, file.id, text, State.replyTo).catch(function () {
    // 发失败保留草稿，用户可以直接再按一次（镜像刷新后页面会回到真实状态）
  });
  State.draftComment = '';
  State.replyTo = null;
  render();
  const next = document.getElementById('comment-input');
  if (next) next.focus();
}

/* -------------------------------------------------------------- 启动 */
(function boot() {
  // 主题跟随系统；?theme= 已在 index.html 的头部脚本里处理过，这里只接管系统切换。
  if (!Params.get('theme') && window.matchMedia) {
    const query = window.matchMedia('(prefers-color-scheme: dark)');
    const apply = function () {
      document.documentElement.dataset.theme = query.matches ? 'dark' : 'light';
    };
    if (query.addEventListener) query.addEventListener('change', apply);
  }

  Shell.listen();   // P3 的「添加文件」要靠壳回传路径

  const initial = Params.get('route') || 'p0';
  const alias = RouteAlias[initial] || { page: initial };
  // 先把 daemon 的快照拿下来再进开屏：P0 的自检判据就是「daemon 在不在」。
  Store.boot().then(function () {
    const first = Store.groups()[0];
    State.groupId = first ? first.id : '';
    Store.onChange(onDataChanged);
    // 链接分享的入口（P4「…」/ P7「分享管理」）先探一次可用性：不可用时两处都不出现。
    // 这条请求同时会让 daemon 加载 WebRTC 原生库，失败会固定降级（见 docs/daemon-api.md §3.6）。
    Store.loadShares(true);
    // 取证别名 p4-public：直接把 P4 指向本群第一条公开条目（正常深链仍由 ?file= 指定）。
    let seedFile = Params.get('file') || null;
    if (!seedFile && alias.public && first) {
      const pub = first.files.find(isPublicFile);
      if (pub) seedFile = pub.id;
    }
    if (alias.add === 'magnet') {
      State.addOpen = true;
      State.addMode = 'magnet';
    }
    // 取证别名 p8-share / p9-list：先确保有一条有效分享，再落页（见 docs/windows-ui.md §6.4）。
    if (alias.ensureShare) {
      ensureShareForDemo().then(function (token) {
        if (token) State.shareToken = token;
        seedStack(alias.page, token || null);
      }, function () {
        seedStack(alias.page, null);
      });
      return;
    }
    seedStack(alias.page, seedFile);
    if (Dev.pick === '1') {
      // 无人值守地验证「页面 → 壳 → 系统选择器 → 回传」这条链：等页面稳定后自动弹选择器。
      setTimeout(function () {
        if (State.route === 'p3') pickFile();
      }, 1200);
    }
  });
})();

/**
 * daemon 的快照变了就重绘。开屏阶段不打断它自己的节奏（P0 有自己的定时器）。
 * render() 会保住焦点与光标，所以每秒推一次也不会打断正在输入的人。
 */
function onDataChanged() {
  if (State.route === 'p0') return;
  render();
}
