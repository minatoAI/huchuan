/**
 * 本地测试夹具：内存版 Durable Object 命名空间、DO 存储、WebSocketPair。
 *
 * 目标是让 worker/src 下的真实代码原样跑起来——DO 类直接实例化，
 * 只替换 Cloudflare 在运行时提供的东西：
 *   - Durable Object storage（KV 接口）
 *   - Durable Object 的 acceptWebSocket / getWebSockets / serializeAttachment
 *   - 全局 WebSocketPair
 *   - fetch(request, env, ctx) 里的 ctx.waitUntil
 *
 * 不是模拟器：没有网络、没有隔离、没有休眠。它只保证同一份逻辑能被断言。
 */

export class MemoryStorage {
  constructor() {
    this.map = new Map();
  }

  async get(key) {
    return this.map.has(key) ? structuredClone(this.map.get(key)) : undefined;
  }

  async put(key, value) {
    this.map.set(key, structuredClone(value));
  }

  async delete(key) {
    return this.map.delete(key);
  }

  async list(options = {}) {
    const prefix = options.prefix || "";
    const limit = options.limit || 1000;
    const out = new Map();
    for (const [key, value] of this.map) {
      if (!key.startsWith(prefix)) continue;
      out.set(key, structuredClone(value));
      if (out.size >= limit) break;
    }
    return out;
  }

  async deleteAll() {
    this.map.clear();
  }
}

/** 一端 socket：DO 侧由 acceptWebSocket 接管，测试侧用 next() 读帧。 */
export class FakeSocket {
  constructor() {
    this.readyState = 1;
    this._peer = null;
    this._tags = [];
    this._state = null;
    this._attachment = null;
    this._inbox = [];
    this._waiters = [];
    this._closeWaiters = [];
    this._closedInfo = null;
    this._listeners = new Map();
    this._closedPromise = new Promise((resolve) => {
      this._resolveClosed = resolve;
    });
  }

  accept() {
    this.readyState = 1;
  }

  addEventListener(type, fn) {
    if (!this._listeners.has(type)) this._listeners.set(type, []);
    this._listeners.get(type).push(fn);
  }

  send(data) {
    if (this.readyState === 3) throw new Error("socket closed");
    if (!this._peer) return;
    this._peer._receive(data);
  }

  close(code = 1000, reason = "") {
    if (this.readyState === 3) return;
    this.readyState = 3;
    this._closedInfo = { code, reason };
    const peer = this._peer;
    if (peer && peer.readyState !== 3) {
      peer.readyState = 3;
      peer._closedInfo = { code, reason };
      if (peer._state) {
        peer._state._socketClosed(peer, code, reason);
      } else {
        peer._resolveClosed({ code, reason });
        this._rejectWaiters(peer, code, reason);
      }
    }
    // DO 自己关掉的 socket 不再出现在 getWebSockets() 里，但不会触发自己的 webSocketClose
    if (this._state) this._state.sockets.delete(this);
    if (!this._state) this._resolveClosed({ code, reason });
    this._rejectWaiters(this, code, reason);
  }

  serializeAttachment(value) {
    this._attachment = value;
  }

  deserializeAttachment() {
    return this._attachment;
  }

  /* ------------------------------------------------ 测试侧读口 */

  /** 下一条消息（原始字符串/字节）；连接关闭或超时会 reject。 */
  next(timeoutMs = 2000) {
    if (this._inbox.length > 0) return Promise.resolve(this._inbox.shift());
    if (this.readyState === 3) {
      return Promise.reject(new Error("socket closed: " + JSON.stringify(this._closedInfo)));
    }
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("timeout waiting for message")), timeoutMs);
      this._waiters.push((message) => {
        clearTimeout(timer);
        resolve(message);
      });
      this._closeWaiters.push((info) => {
        clearTimeout(timer);
        reject(new Error("socket closed: " + JSON.stringify(info)));
      });
    });
  }

  /** 下一条消息，按 JSON 解。 */
  async nextJson(timeoutMs = 2000) {
    const raw = await this.next(timeoutMs);
    return typeof raw === "string" ? JSON.parse(raw) : JSON.parse(new TextDecoder().decode(raw));
  }

  /** 等到对端关闭。 */
  closed() {
    if (this.readyState === 3) return Promise.resolve(this._closedInfo);
    return this._closedPromise;
  }

  _receive(data) {
    if (this._state) {
      this._state._deliver(this, data);
      return;
    }
    this._inbox.push(data);
    while (this._waiters.length > 0 && this._inbox.length > 0) {
      const waiter = this._waiters.shift();
      this._closeWaiters.shift();
      waiter(this._inbox.shift());
    }
  }

  _queue(message) {
    this._inbox.push(message);
    const waiter = this._waiters.shift();
    if (waiter) {
      this._closeWaiters.shift();
      waiter(message);
    }
  }

  _rejectWaiters(target, code, reason) {
    const waiters = target._closeWaiters.splice(0);
    target._waiters.length = 0;
    for (const waiter of waiters) waiter({ code, reason });
  }
}

export class FakeWebSocketPair {
  constructor() {
    const left = new FakeSocket();
    const right = new FakeSocket();
    left._peer = right;
    right._peer = left;
    this[0] = left;
    this[1] = right;
  }
}

export class FakeDurableObjectState {
  constructor(storage) {
    this.storage = storage;
    this.sockets = new Set();
    this._instance = null;
    this._chain = Promise.resolve();
  }

  bind(instance) {
    this._instance = instance;
  }

  acceptWebSocket(ws, tags = []) {
    ws._tags = [...tags];
    ws._state = this;
    ws.readyState = 1;
    this.sockets.add(ws);
  }

  getWebSockets(tag) {
    const sockets = [...this.sockets];
    if (tag === undefined) return sockets;
    return sockets.filter((ws) => (ws._tags || []).includes(tag));
  }

  _deliver(ws, data) {
    this._chain = this._chain
      .then(() => this._instance.webSocketMessage(ws, data))
      .catch((error) => {
        console.error("webSocketMessage failed:", error);
      });
    return this._chain;
  }

  _socketClosed(ws, code, reason) {
    if (!this.sockets.has(ws)) return this._chain;
    this.sockets.delete(ws);
    this._chain = this._chain
      .then(() => this._instance.webSocketClose && this._instance.webSocketClose(ws, code, reason, true))
      .catch((error) => {
        console.error("webSocketClose failed:", error);
      });
    return this._chain;
  }

  /** 等所有排队的 WS 消息处理完（测试里 await 它来消除竞态）。 */
  async settled() {
    await this._chain;
  }
}

/**
 * 造一个 env：三个 DO 命名空间都指向内存实现，DO 类直接用 src 里的真类。
 * @param {object} workerModule import * as workerModule from "../src/index.js"
 */
export function createTestEnv(workerModule, options = {}) {
  globalThis.WebSocketPair = FakeWebSocketPair;
  const env = { ...(options.vars || {}) };
  const namespaces = {};

  const makeNamespace = (Klass) => {
    const instances = new Map();
    namespaces[Klass.name] = instances;
    return {
      idFromName(name) {
        return String(name);
      },
      idFromString(value) {
        return String(value);
      },
      newUniqueId() {
        return "unique-" + instances.size + "-" + Math.random().toString(16).slice(2);
      },
      get(idOrName) {
        const key = String(idOrName);
        return {
          id: key,
          fetch: async (input, init) => {
            const request = input instanceof Request ? input : new Request(input, init);
            let instance = instances.get(key);
            if (!instance) {
              const state = new FakeDurableObjectState(new MemoryStorage());
              instance = new Klass(state, env);
              state.bind(instance);
              instances.set(key, instance);
            }
            return instance.fetch(request);
          },
        };
      },
    };
  };

  env.SWARM = makeNamespace(workerModule.TrackerSwarm);
  env.REGISTRY = makeNamespace(workerModule.ShareRegistry);
  env.ROOM = makeNamespace(workerModule.SignalRoom);
  env.__namespaces = namespaces;
  return env;
}

/** 等所有 DO 排队的 WS 消息 / 关闭回调处理完。 */
export async function settle(env) {
  for (const instances of Object.values(env.__namespaces || {})) {
    for (const instance of instances.values()) {
      if (instance.state && typeof instance.state.settled === "function") await instance.state.settled();
    }
  }
}

/** 假的 ctx：waitUntil 收集起来，drain() 等它们跑完。 */
export function createCtx() {
  const tasks = [];
  return {
    tasks,
    waitUntil(promise) {
      tasks.push(Promise.resolve(promise));
    },
    passThroughOnException() {},
    async drain() {
      while (tasks.length > 0) await Promise.allSettled(tasks.splice(0));
    },
  };
}

export function makeRequest(path, options = {}) {
  const { method = "GET", headers = {}, body, origin = "https://huchuan.test" } = options;
  return new Request(origin + path, { method, headers, body });
}

/** 发起一次带 Upgrade 的请求（不经过 route 之外的任何东西）。 */
export async function upgradeRequest(workerModule, env, path, ctx) {
  return workerModule.route(
    makeRequest(path, { headers: { Upgrade: "websocket", Connection: "Upgrade" } }),
    env,
    ctx,
  );
}
