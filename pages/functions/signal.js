/**
 * GET /signal —— WebRTC 信令，转给 Worker。
 *
 * 这里的 WebSocket 升级靠的就是「原样透传」：Upgrade 头一旦丢掉就升级不成，
 * 客户端会一直卡在连接中。不要重建 Request。
 *
 * 原样透传整个 Request：方法、路径、查询串、请求体、请求头都不动。
 * 走 service binding，请求不出 Cloudflare 网络，也不经过公网。
 * 鉴权（签名、token、passkey）由 Worker 自己验，这一层不做任何业务逻辑。
 */
export const onRequest = ({ request, env }) => env.RELAY.fetch(request);
