'use strict';

/**
 * Windows Web UI 的数据层：唯一的后端是 daemon（docs/daemon-api.md）。
 *
 * 分工：
 *   - Api   只有一层 fetch。所有 REST 端点在这里点名，别的地方不拼 URL。
 *   - Store 页面看到的同步门面：读接口同步返回本地镜像，写接口发请求后刷新镜像。
 *
 * 为什么读是同步的：页面代码（js/app.js）是按同步 store 写的，几十处渲染不能改成 async。
 * 所以 daemon 是唯一事实源，本地镜像只是它的最近一次快照；任何写操作之后都重新拉一次
 * /api/state，WS 推送（state / tick）也会触发重新拉取 —— 镜像永远收敛到 daemon。
 *
 * 页面来源有两种，两种都支持：
 *   1. 浏览器直接打开 http://127.0.0.1:47200/     —— 同源，最省事（推荐）
 *   2. 壳的内嵌资源 https://huchuan.invalid/      —— 跨源，靠 daemon 的 CORS + PNA 响应头
 * 另外可以用 ?api=http://127.0.0.1:47200 显式指定 daemon 地址（换端口调试时用）。
 */

/** 文件状态：与 core / daemon 的 state 字段同名同义。 */
const FileState = {
  SEEDING: 'seeding',
  DOWNLOADING: 'downloading',
  SLEEPING: 'sleeping',
};

/** 通知档：与 daemon 的 notificationLevel 同名同义。 */
const NotificationLevel = {
  SYSTEM_BUBBLE: 'bubble',
  IN_APP_ONLY: 'in_app',
  MUTED: 'muted',
};

const DEFAULT_API_BASE = 'http://127.0.0.1:47200';

/** REST 端点表。daemon-api.md 里每一条都在这里出现一次。 */
const Api = (function () {
  const params = new URLSearchParams(location.search);
  const override = params.get('api');
  const sameOrigin = location.hostname === '127.0.0.1' || location.hostname === 'localhost';
  const base = (override || (sameOrigin ? location.origin : DEFAULT_API_BASE)).replace(/\/+$/, '');

  function url(path) {
    return base + path;
  }

  /** 请求失败时抛出带 code 的 Error：{ code, message, status }。 */
  function call(method, path, body) {
    const init = { method: method, headers: {}, cache: 'no-store' };
    if (body !== undefined && body !== null) {
      init.headers['Content-Type'] = 'application/json';
      init.body = JSON.stringify(body);
    }
    return fetch(url(path), init).then(function (response) {
      return response.text().then(function (text) {
        let payload = null;
        try {
          payload = text ? JSON.parse(text) : null;
        } catch (error) {
          payload = null;
        }
        if (!response.ok || !payload || payload.ok !== true) {
          const err = new Error((payload && payload.error) || ('HTTP ' + response.status));
          err.code = (payload && payload.code) || 'E_HTTP';
          err.status = response.status;
          throw err;
        }
        return payload.data;
      });
    });
  }

  return {
    base: base,
    health: function () { return call('GET', '/api/health'); },
    state: function () { return call('GET', '/api/state'); },
    groups: function () { return call('GET', '/api/groups'); },
    group: function (gid) { return call('GET', '/api/groups/' + encodeURIComponent(gid)); },
    files: function (gid) { return call('GET', '/api/groups/' + encodeURIComponent(gid) + '/files'); },
    file: function (gid, fid) {
      return call('GET', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid));
    },
    comments: function (gid, fid) {
      return call('GET', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid) + '/comments');
    },
    createGroup: function (name) { return call('POST', '/api/groups', { name: name }); },
    join: function (tail8, manual) {
      return call('POST', '/api/join', { tail8: tail8, manual: manual || [], timeoutMs: 25000 });
    },
    addFile: function (gid, path) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files', { path: path });
    },
    /**
     * 磁力导入：data 里是内核的结果 { ok, fileId, code, message, file }。
     * data.ok=false 不是请求失败 —— 条目已经建好，只是文件信息还没到手（no_source / timeout），
     * 列表里会出现「等待来源」，内核继续找源。非法链接 / 重复导入会 reject，err.code 是
     * invalid_link / duplicate（见 docs/daemon-api.md 3.3）。
     */
    importMagnet: function (gid, magnet, name, timeoutMs) {
      const body = { magnet: String(magnet || '') };
      const trimmed = name === undefined || name === null ? '' : String(name).trim();
      if (trimmed) body.name = trimmed;
      if (timeoutMs !== undefined && timeoutMs !== null) body.timeoutMs = Math.max(0, timeoutMs | 0);
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files/import-magnet', body);
    },
    /** 本地移除（只从本机列表消失，不广播、不删文件）。公开条目专属。 */
    removeFile: function (gid, fid) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid) + '/remove', {});
    },
    setActive: function (gid, fid, active) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid) + '/active', { active: !!active });
    },
    setFileUploadLimit: function (gid, fid, kbps) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid) + '/upload-limit', { kbps: kbps | 0 });
    },
    addComment: function (gid, fid, body, parent) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/files/' + encodeURIComponent(fid) + '/comments', {
        body: body,
        parent: parent || '',
      });
    },
    setNotification: function (gid, level) {
      return call('POST', '/api/groups/' + encodeURIComponent(gid) + '/notification', { level: level });
    },
    settings: function () { return call('GET', '/api/settings'); },
    patchSettings: function (patch) { return call('POST', '/api/settings', patch); },
    limits: function () { return call('GET', '/api/settings/limits'); },
    setLimits: function (patch) { return call('POST', '/api/settings/limits', patch); },
    tracker: function () { return call('GET', '/api/settings/tracker'); },
    setTracker: function (patch) { return call('POST', '/api/settings/tracker', patch); },
    shutdown: function () { return call('POST', '/api/shutdown', {}); },
    wsUrl: function () { return base.replace(/^http/, 'ws') + '/ws'; },
  };
})();

/* ------------------------------------------------------------ 展示用的换算 */

function pad2(value) {
  return value < 10 ? '0' + value : String(value);
}

/** 评论时间：刚发的说「刚刚」，一天内说小时分，再早说日期。 */
function formatCommentTime(ts) {
  const now = Date.now();
  const at = Number(ts) || 0;
  if (!at) return '';
  const diff = now - at;
  if (diff >= 0 && diff < 60 * 1000) return '刚刚';
  if (diff >= 0 && diff < 60 * 60 * 1000) return Math.floor(diff / 60000) + ' 分钟前';
  const date = new Date(at);
  const stamp = pad2(date.getHours()) + ':' + pad2(date.getMinutes());
  const today = new Date(now);
  if (date.toDateString() === today.toDateString()) return stamp;
  const yesterday = new Date(now - 24 * 60 * 60 * 1000);
  if (date.toDateString() === yesterday.toDateString()) return '昨天 ' + stamp;
  return (date.getMonth() + 1) + '-' + date.getDate() + ' ' + stamp;
}

/** daemon 的群 JSON → 页面用的形状（字段名与旧的内存 store 保持一致）。 */
function toComment(json) {
  return {
    id: json.id || json.opId,
    author: json.author || '',
    body: json.body || '',
    time: formatCommentTime(json.ts),
    parentId: json.parentId || null,
  };
}

function toFile(json) {
  return {
    id: json.id,
    name: json.name,
    sizeBytes: json.sizeBytes || 0,
    sourceMember: json.sourceMember || '',
    state: json.state === FileState.SEEDING || json.state === FileState.DOWNLOADING
      ? json.state
      : FileState.SLEEPING,
    progressPercent: json.progressPercent || 0,
    seeders: json.seeders || 0,
    teammates: json.teammates || 0,
    uploadLimitKbps: json.uploadLimitKbps || 0,
    uploadLimitInForce: !!json.uploadLimitInForce,
    downloadRateBps: json.downloadRateBps || 0,
    uploadRateBps: json.uploadRateBps || 0,
    savedPath: json.savedPath || '',
    comments: (json.comments || []).map(toComment),
  };
}

function toMember(json) {
  return {
    name: json.name || '',
    online: !!json.online,
    seedingCount: json.seedingCount || 0,
    platform: json.platform || '',
  };
}

function toGroup(json) {
  return {
    id: json.id,
    name: json.name || '',
    inviteCode: json.inviteCode || '',
    notificationLevel: json.notificationLevel || NotificationLevel.SYSTEM_BUBBLE,
    unreadCount: json.unreadCount || 0,
    members: (json.members || []).map(toMember),
    files: (json.files || []).map(toFile),
  };
}

/* -------------------------------------------------------------- Store */

const Store = (function () {
  const device = {
    deviceName: '',
    userName: '',
    uploadLimitKbps: 0,
    downloadLimitKbps: 0,
    unlimited: true,
    // 内核读回：会话里真正生效的值（0 = 不限），与上面两个「用户设置」是两回事
    sessionUploadLimitKbps: 0,
    sessionDownloadLimitKbps: 0,
    // 跨网发现：enabled 是落盘设置，active 是内核此刻有没有在跑（两者差一次进程重启）
    trackerEnabled: true,
    trackerBaseUrl: '',
    trackerActive: false,
    trackerRestartRequired: false,
  };

  let groups = [];
  let groupIndex = {};
  let online = false;
  let daemon = null;
  let revision = 0;

  const listeners = [];
  let ws = null;
  let wsRetry = null;
  let refreshTimer = null;
  let refreshing = false;
  let refreshPending = false;

  const pendingTimers = {};

  function notify() {
    listeners.slice().forEach(function (fn) {
      try {
        fn();
      } catch (error) {
        // 一个监听器出错不该拖垮其余渲染
      }
    });
  }

  function applyState(data) {
    daemon = data.daemon || null;
    revision = (data.daemon && data.daemon.rev) || 0;
    device.deviceName = (data.device && data.device.deviceName) || '';
    device.userName = (data.settings && data.settings.userName) || '';
    device.uploadLimitKbps = (data.settings && data.settings.uploadLimitKbps) || 0;
    device.downloadLimitKbps = (data.settings && data.settings.downloadLimitKbps) || 0;
    device.unlimited = data.settings ? data.settings.unlimited !== false : true;
    device.sessionUploadLimitKbps = (data.settings && data.settings.sessionUploadLimitKbps) || 0;
    device.sessionDownloadLimitKbps = (data.settings && data.settings.sessionDownloadLimitKbps) || 0;
    device.trackerEnabled = data.settings ? data.settings.trackerEnabled !== false : true;
    device.trackerBaseUrl = (data.settings && data.settings.trackerBaseUrl) || '';
    device.trackerActive = !!(data.settings && data.settings.trackerActive);
    device.trackerRestartRequired = !!(data.settings && data.settings.trackerRestartRequired);

    groups = (data.groups || []).map(toGroup);
    groupIndex = {};
    groups.forEach(function (group) {
      groupIndex[group.id] = group;
    });
    online = true;
  }

  function markOffline() {
    online = false;
  }

  /** 拉一次 /api/state 覆盖镜像。同一时刻只允许一个请求在飞，多余的合并成一次补拉。 */
  function refresh(loud) {
    if (refreshing) {
      refreshPending = true;
      return Promise.resolve();
    }
    refreshing = true;
    return Api.state()
      .then(function (data) {
        applyState(data);
      }, function () {
        markOffline();
      })
      .then(function () {
        refreshing = false;
        if (refreshPending) {
          refreshPending = false;
          return refresh(loud);
        }
        if (loud) notify();
        return undefined;
      });
  }

  function scheduleRefresh() {
    if (refreshTimer) return;
    refreshTimer = setTimeout(function () {
      refreshTimer = null;
      refresh(true);
    }, 120);
  }

  function connect() {
    if (ws || typeof WebSocket === 'undefined') return;
    let socket;
    try {
      socket = new WebSocket(Api.wsUrl());
    } catch (error) {
      return;
    }
    ws = socket;
    socket.onmessage = function (event) {
      let message = null;
      try {
        message = JSON.parse(event.data);
      } catch (error) {
        return;
      }
      // state = 有东西变了；tick = 传输速率/进度变了。两种都值得重新拉一次快照。
      if (message && (message.type === 'state' || message.type === 'tick')) {
        scheduleRefresh();
      }
    };
    socket.onclose = function () {
      ws = null;
      if (wsRetry) return;
      wsRetry = setTimeout(function () {
        wsRetry = null;
        connect();
      }, 1000);
    };
    socket.onerror = function () {
      // onclose 会跟着来，重连交给它
    };
  }

  /** 拖尾防抖：输入框每敲一个字都会调到这里，只让最后一次真正落库。 */
  function later(key, ms, fn) {
    if (pendingTimers[key]) clearTimeout(pendingTimers[key]);
    pendingTimers[key] = setTimeout(function () {
      delete pendingTimers[key];
      fn();
    }, ms);
  }

  function silent(promise) {
    promise.catch(function () {
      markOffline();
      notify();
    });
    return promise;
  }

  function groupById(id) {
    return groupIndex[id] || null;
  }

  return {
    /* ---- 生命周期 ---- */

    /** 首次连接：拉快照 + 打开 WS。失败不抛，页面据此显示「网络不可用」。 */
    boot: function () {
      return Api.health()
        .then(function () {
          connect();
          return refresh(false);
        })
        .then(function () {
          return online;
        }, function () {
          markOffline();
          return false;
        });
    },

    refresh: function () {
      return refresh(true);
    },

    onChange: function (fn) {
      listeners.push(fn);
    },

    online: function () {
      return online;
    },

    daemon: function () {
      return daemon;
    },

    revision: function () {
      return revision;
    },

    /* ---- 读（同步，读本地镜像） ---- */

    device: function () {
      return device;
    },

    groups: function () {
      return groups;
    },

    group: function (id) {
      return groupById(id);
    },

    file: function (groupId, fileId) {
      const group = groupById(groupId);
      if (!group) return null;
      for (let i = 0; i < group.files.length; i += 1) {
        if (group.files[i].id === fileId) return group.files[i];
      }
      return null;
    },

    /* ---- 写（发给 daemon，然后用它的快照重新对齐） ---- */

    saveUserName: function (name) {
      const value = String(name || '').trim();
      device.userName = value;
      return silent(Api.patchSettings({ userName: value }).then(function () { return refresh(true); }));
    },

    /**
     * 全局限速走 POST /api/settings/limits：daemon 会把它下发内核会话（applySettings，运行期生效），
     * 回包里的 session*Kbps 是内核读回值。镜像里的 device.* 只是「用户想要的值」，
     * 界面上「内核读回」那一行读的是 session*Kbps。
     */
    setUploadLimit: function (kbps) {
      const value = Math.max(0, kbps | 0);
      device.uploadLimitKbps = value;
      later('upload', 400, function () {
        silent(Api.setLimits({ uploadKbps: device.uploadLimitKbps }).then(function () { return refresh(true); }));
      });
    },

    setDownloadLimit: function (kbps) {
      const value = Math.max(0, kbps | 0);
      device.downloadLimitKbps = value;
      later('download', 400, function () {
        silent(Api.setLimits({ downloadKbps: device.downloadLimitKbps }).then(function () { return refresh(true); }));
      });
    },

    setUnlimited: function (unlimited) {
      device.unlimited = !!unlimited;
      return silent(Api.setLimits({ unlimited: device.unlimited }).then(function () { return refresh(true); }));
    },

    /**
     * 跨网发现开关。daemon 只把它落盘：tracker 在进程启动时装配，改完要重启才生效。
     * 镜像里的 trackerActive / trackerRestartRequired 是 daemon 给的内核现状，
     * 所以开关点了之后页面能同时说清「设置已关」和「本次进程里还没关」。
     */
    setTrackerEnabled: function (enabled) {
      device.trackerEnabled = !!enabled;
      return silent(Api.setTracker({ enabled: device.trackerEnabled }).then(function () { return refresh(true); }));
    },

    createGroup: function (name) {
      return Api.createGroup(name).then(function (data) {
        const created = toGroup(data.group);
        return refresh(true).then(function () {
          return groupById(created.id) || created;
        });
      });
    },

    /** 只收后 8 位。加入失败时 reject（页面显示「邀请码不对」）。 */
    joinGroup: function (tail8) {
      const params = new URLSearchParams(location.search);
      const manual = (params.get('manual') || '').split(',').map(function (item) {
        return item.trim();
      }).filter(function (item) {
        return item.length > 0;
      });
      return Api.join(String(tail8 || '').trim().toUpperCase(), manual).then(function (data) {
        const joined = toGroup(data.group);
        return refresh(true).then(function () {
          return groupById(joined.id) || joined;
        });
      });
    },

    setNotificationLevel: function (groupId, level) {
      return silent(Api.setNotification(groupId, level).then(function () { return refresh(true); }));
    },

    setFileActive: function (groupId, fileId, active) {
      return silent(Api.setActive(groupId, fileId, active).then(function () { return refresh(true); }));
    },

    setFileUploadLimit: function (groupId, fileId, kbps) {
      const value = Math.max(0, kbps | 0);
      const file = this.file(groupId, fileId);
      if (file) file.uploadLimitKbps = value;
      later('upload:' + fileId, 400, function () {
        silent(Api.setFileUploadLimit(groupId, fileId, value).then(function () { return refresh(true); }));
      });
    },

    /** 新评论由 daemon 排进日志，镜像刷新后自然出现在顶部（spec 流程 7）。 */
    addComment: function (groupId, fileId, body, parentId) {
      const text = String(body || '').trim();
      if (!text) return Promise.reject(new Error('评论不能为空'));
      return Api.addComment(groupId, fileId, text, parentId || '').then(function () {
        return refresh(true);
      });
    },

    /** 本机绝对路径：由壳的系统文件选择器给出（app.js 的 Shell 桥），浏览器里拿不到。 */
    addFile: function (groupId, path) {
      return Api.addFile(groupId, path).then(function (data) {
        const created = data.file;
        return refresh(true).then(function () {
          return created ? (Store.file(groupId, created.id) || created) : null;
        });
      });
    },

    /**
     * 公开种子（磁力）导入。回包里的 ok 是内核判定：false = 条目已建好、文件信息还没到手
     * （no_source / timeout / bad_metadata），条目仍在列表里等来源；非法链接 / 重复导入会
     * reject，err.code 分别是 invalid_link / duplicate。
     */
    importMagnet: function (groupId, magnet, name, timeoutMs) {
      return Api.importMagnet(groupId, magnet, name, timeoutMs).then(function (result) {
        return refresh(true).then(function () {
          return result;
        });
      });
    },

    /** 本地移除：只影响本机（不广播、不删文件），刷新后这个条目就不再出现在列表里。 */
    removeFile: function (groupId, fileId) {
      return Api.removeFile(groupId, fileId).then(function (data) {
        return refresh(true).then(function () {
          return data;
        });
      });
    },
  };
})();

/** 群组派生量：做种数 = 成员做种数之和；组队人数 = 正在组队的文件里最大的队。 */
function groupSeedingCount(group) {
  return group.members.reduce(function (sum, member) { return sum + member.seedingCount; }, 0);
}

function groupTeamSize(group) {
  return group.files.reduce(function (max, file) {
    return fileInTeam(file) ? Math.max(max, fileTeamSize(file)) : max;
  }, 0);
}

/**
 * 组队是否该出现在界面上（P3 的 chip 与 P4 的「正在组队」行共用这一个判定）。
 * 口径同 Android 的 GroupFile.showsTeam()：内核给出的队友数 ≥ 1，且本机还在「部分」。
 * 本机到 100% 时内核已经把 teammates 归零，这里再卡一次进度是给「进度满了、状态还没翻页」
 * 的那一拍兜底，保证这一行立刻消失。
 */
function fileInTeam(file) {
  return file.teammates > 0 && file.progressPercent < 100;
}

/** 界面上的「n 人」= 队友数 + 1（含本机，docs/design/product-spec.md:306）。 */
function fileTeamSize(file) {
  return file.teammates + 1;
}
