'use strict';

/**
 * 互传 Windows 壳 · 八页 Web UI（纯静态，无框架、无 CDN、无网络请求）。
 *
 * 信息架构与 Android 壳同构：P0 自检 → P1 用户名 → P2 首页 → P3 群组 → P4 文件详情，
 * P5 新群组、P6 成员、P7 设置抽屉。数据来自 data.js 的内存 store。
 *
 * 路由：#/p0 … #/p7。#/p7 是 P2 之上的设置抽屉（Android 里是 Drawer，没有独立目的地）。
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
  p3_add_need_shell: '只能在互传窗口里选文件',
  p3_add_failed: '添加失败',
  p3_members: function (n) { return '成员（' + n + ' 人）'; },
  p3_seeding: '做种中',
  p3_sleeping: '休眠',
  p3_downloading: '下载中',
  p3_team: function (n) { return '组队 ' + n + ' 人'; },
  p3_seeders: function (n) { return '做种 ' + n; },
  p3_empty: '还没有文件',

  // P4 文件详情
  p4_activate: '激活',
  p4_sleep: '休眠',
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

function fileStateLabel(file) {
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

  sync: function () {
    const item = this.current();
    State.route = item.page;
    // 栈里的 id 在 P3/P6 是群组、在 P4 是文件；其余页忽略。
    if (item.page === 'p3' || item.page === 'p6') {
      if (item.id) State.groupId = item.id;
    } else if (item.page === 'p4') {
      if (item.id) State.fileId = item.id;
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
  }[route] || [];
  Nav.stack = parent.map(function (page) { return { page: page, id: null }; });
  Nav.stack.push({ page: route, id: id || null });
  Nav.sync();
}

/* -------------------------------------------------------------- 渲染 */
function render() {
  clearSplash();

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
    default: html = viewHome();
  }

  app.innerHTML = html;
  document.body.dataset.page = State.route;

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
  const chips = [
    '<span class="chip' + (file.state === FileState.SLEEPING ? '' : ' is-on') + '">' +
      fileStateLabel(file) + '</span>',
  ];
  if (fileInTeam(file)) {
    chips.push('<span class="chip is-on">' + COPY.p3_team(fileTeamSize(file)) + '</span>');
  }
  const progress = file.state === FileState.DOWNLOADING
    ? '<span class="cell-progress"><span class="progress"><i style="width:' + file.progressPercent +
      '%"></i></span><span class="cell-pct">' + file.progressPercent + '%</span></span>'
    : '';

  return '<button class="trow" data-act="open-file" data-id="' + file.id + '">' +
    '<span class="cell-main"><span class="cell-title">' + esc(file.name) + '</span>' + progress + '</span>' +
    '<span class="cell-size">' + formatSize(file.sizeBytes) + '</span>' +
    '<span class="cell-seeders">' + COPY.p3_seeders(file.seeders) + '</span>' +
    '<span class="cell-chips">' + chips.join('') + '</span>' +
    '</button>';
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

  return '<div class="screen">' +
    topbar(file.name, { back: true }) +
    '<div class="content"><div class="page page-wide">' +
      '<div class="file-meta">' +
        '<span class="chip' + (file.state === FileState.SLEEPING ? '' : ' is-on') + '">' +
          fileStateLabel(file) + '</span>' +
        '<span>' + formatSize(file.sizeBytes) + '</span>' +
        '<span>' + COPY.p4_source(file.sourceMember) + '</span>' +
      '</div>' +
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

function copyInviteCode() {
  const group = Store.group(State.groupId);
  if (!group) return;
  const done = function () {
    State.copied = true;
    render();
    setTimeout(function () {
      State.copied = false;
      render();
    }, 1600);
  };
  // WebView2 里 navigator.clipboard 可能被拒；退回 execCommand，两条都失败就不假装复制成功。
  const fallback = function () {
    const area = document.createElement('textarea');
    area.value = group.inviteCode;
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
    navigator.clipboard.writeText(group.inviteCode).then(done, fallback);
  } else {
    fallback();
  }
}

/* ------------------------------------------------------------ P7 设置 */
function viewSettings() {
  const device = Store.device();
  const groups = Store.groups();

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

        '<div class="kv"><span class="field-label">' + COPY.p7_network_status + '</span>' +
          '<span>' + networkLabel() + '</span></div>' +
      '</div>' +
    '</aside>';
}

/* -------------------------------------------------------------- 事件 */
document.addEventListener('click', function (event) {
  const target = event.target.closest('[data-act]');
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

    case 'close-settings':
      if (State.route === 'p7') Nav.back();
      break;

    case 'new-group':
      State.createOpen = false;
      Nav.go('p5');
      break;

    case 'add-file':
      pickFile();
      break;

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
  } else if (!id && State.route === 'p3' &&
             !(event.target && event.target.closest &&
               event.target.closest('[data-act="add-file"]'))) {
    // P3 没有输入框了：Enter 就是这一页的主操作（按钮自己聚焦时浏览器会补一次 click，跳过）
    event.preventDefault();
    pickFile();
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
  // 先把 daemon 的快照拿下来再进开屏：P0 的自检判据就是「daemon 在不在」。
  Store.boot().then(function () {
    const first = Store.groups()[0];
    State.groupId = first ? first.id : '';
    Store.onChange(onDataChanged);
    seedStack(initial, Params.get('file') || null);
    if (Dev.pick === '1') {
      // 无人值守地验证「页面 → 壳 → 系统选择器 → 回传」这条链：等页面稳定后自动点一次。
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
