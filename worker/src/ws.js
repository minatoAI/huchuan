/**
 * WebSocket 升级应答。
 *
 * 正式运行时 workerd 允许 new Response(null, { status: 101, webSocket })；
 * Node 的 fetch 实现按规范只接受 200–599，会抛错。测试里退化成普通对象
 * （带 status 与 webSocket），等价于 101 应答，调用方不需要区分。
 */
export function upgradeResponse(webSocket) {
  try {
    return new Response(null, { status: 101, webSocket });
  } catch {
    return { status: 101, webSocket, headers: new Headers(), webSocketUpgradeFallback: true };
  }
}
