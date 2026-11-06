import { onRequest as __api___path___js_onRequest } from "E:\\agentHome\\bt-share-app\\pages\\functions\\api\\[[path]].js"
import { onRequest as __announce_js_onRequest } from "E:\\agentHome\\bt-share-app\\pages\\functions\\announce.js"
import { onRequest as __health_js_onRequest } from "E:\\agentHome\\bt-share-app\\pages\\functions\\health.js"
import { onRequest as __signal_js_onRequest } from "E:\\agentHome\\bt-share-app\\pages\\functions\\signal.js"
import { onRequest as __ws_js_onRequest } from "E:\\agentHome\\bt-share-app\\pages\\functions\\ws.js"

export const routes = [
    {
      routePath: "/api/:path*",
      mountPath: "/api",
      method: "",
      middlewares: [],
      modules: [__api___path___js_onRequest],
    },
  {
      routePath: "/announce",
      mountPath: "/",
      method: "",
      middlewares: [],
      modules: [__announce_js_onRequest],
    },
  {
      routePath: "/health",
      mountPath: "/",
      method: "",
      middlewares: [],
      modules: [__health_js_onRequest],
    },
  {
      routePath: "/signal",
      mountPath: "/",
      method: "",
      middlewares: [],
      modules: [__signal_js_onRequest],
    },
  {
      routePath: "/ws",
      mountPath: "/",
      method: "",
      middlewares: [],
      modules: [__ws_js_onRequest],
    },
  ]