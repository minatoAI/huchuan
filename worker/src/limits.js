/**
 * 全部上限与时间常数集中在这里（link-share.md §6/§10.2、protocol.md §3.2）。
 * 这些值都有文档出处，改之前先改文档。
 */

/** tracker：announce 间隔（protocol.md §3.2：interval=600，不改） */
export const ANNOUNCE_INTERVAL_SEC = 600;
export const ANNOUNCE_MIN_INTERVAL_SEC = 300;
/** 降频档（link-share.md §10.2）允许把 interval 调大，但不超过这个上限 */
export const ANNOUNCE_MAX_INTERVAL_SEC = 3600;

/** numwant 上限（protocol.md §3.2 写 numwant=32） */
export const ANNOUNCE_NUMWANT_DEFAULT = 32;
export const ANNOUNCE_NUMWANT_MAX = 32;

/** 一个 peer 多久没 announce 就从 swarm 里清掉（3 个 interval） */
export const PEER_TTL_SEC = 1800;
/** 单个伪 swarm 的 peer 上限（群规模上限 32 人，512 是防空转的兜底） */
export const MAX_PEERS_PER_SWARM = 512;

/** 分享：有效期 7 天（link-share.md §12-1） */
export const SHARE_TTL_DEFAULT_SEC = 7 * 24 * 3600;
export const SHARE_TTL_MAX_SEC = 7 * 24 * 3600;
export const SHARE_TTL_MIN_SEC = 60;
/** 同机有效分享上限（link-share.md §6：≥20 回 429） */
export const MAX_ACTIVE_SHARES_PER_DEVICE = 20;

/** 分享清单：单文件 1 GiB、最多 10 个（link-share.md §12-4 / §12-8） */
export const MAX_SHARE_FILES = 10;
export const MAX_FILE_NAME_BYTES = 255;
export const MAX_FILE_BYTES = 1 << 30;

/** 信令：单帧上限（只转发 SDP/ICE，正常几 KB） */
export const MAX_SIGNAL_FRAME_BYTES = 64 * 1024;
/** WS 签名 ts 允许的偏移（protocol.md 用 ±300 s） */
export const SIGNAL_CLOCK_SKEW_SEC = 300;
/** 同一分享的 share-join 限速（link-share.md §7：3 次/分钟） */
export const SIGNAL_JOIN_WINDOW_MS = 60_000;
export const SIGNAL_MAX_JOINS_PER_WINDOW = 3;
/** 房间最多 1 个访客（link-share.md §12-3） */
export const SIGNAL_ROOM_MAX_GUESTS = 1;

/** HTTP 请求体上限：分享注册只带元信息 */
export const MAX_JSON_BODY_BYTES = 64 * 1024;
