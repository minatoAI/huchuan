package app.huchuan.core.m1

/**
 * 个人空间（docs/design/personal-space.md）：一个**不可加入**的本地群，专门放要分享链接的文件。
 *
 * 它不需要用户先建群、也不需要群里有别人 —— 打开 P3 就能加文件、生成分享链接。
 * 为了不另写一套文件 / 做种 / 评论 / 限速逻辑，它在内核里就是一个普通的 [GroupRuntime]，
 * 区别只有四条隔离（同样见设计文档「与普通群的区别」）：
 *
 *  1. 名字固定、不可改；不显示成员数；
 *  2. **不广播 mDNS**、**不向自带 tracker 注册 / announce**（见 M1Kernel 的注册路径与 TrackerService）；
 *  3. **不接受任何对端**：hello / join.req 只要带这个 gid 一律回 E_JOIN_PERSONAL（见 PeerLink）；
 *  4. 不生成、不显示邀请码 —— 下面的派生码只是 [GroupMeta] 的结构需要（kGrp / fp / proof），
 *     它不作为准入凭据：个人空间的 join 在验码之前就被拒了。
 *
 * ## 为什么 gid 是派生的
 *
 * gid 必须**固定不变**：它是本机文件与分享记录的归属，重启、重装应用（同一份 identity.txt）都不能变。
 * 所以全部从设备身份派生（HKDF-SHA256），而不是随机生成后落盘：
 *
 *     gid   = B32(HKDF(ikm = pub64, info = "huchuan/personal/v1",        20 字节))
 *     邀请码 = InviteCode(秘密段 = HKDF(..., "huchuan/personal/v1/invite", 5 字节))
 *     keySeed = HKDF(..., "huchuan/personal/v1/key",                      32 字节)
 *     gHandle = B32(HKDF(..., "huchuan/personal/v1/handle",               5 字节))
 *
 * 20 字节 = 160 bit = **32 个 Crockford Base32 字符**，与普通群的 10 字节 / 16 字符长度不同，
 * 所以个人空间的 gid 永远不可能跟某个随机群 id 撞上。
 *
 * 派生用的 IKM 是**公钥**（不是私钥）：这几样东西只要求「唯一且稳定」，不承担保密职责 ——
 * 个人空间不接受任何对端，密钥不会用于对外准入；换成私钥只会把 identity 的存储形态
 * （PKCS8 落文件 / Android Keystore 包裹）绑进来，没有收益。这一条在 docs/core.md §3.13 里也写了。
 */
object PersonalSpace {

    /** 固定名称（spec：名称固定，不可改）。 */
    const val NAME = "我的分享"

    /** gid 的字节数：20 字节 → 32 个 B32 字符。 */
    const val GID_BYTES = 20

    /** HKDF 的 info 前缀；gid 自己用的就是这个域名（与任务口径一致）。 */
    const val DOMAIN = "huchuan/personal/v1"

    /** 派生出的 gid（32 字符，Crockford Base32）。 */
    fun groupIdOf(identity: DeviceIdentity): String =
        B32.encode(derive(identity, DOMAIN, GID_BYTES))

    /** 结构用的邀请码：带合法的校验段（§2.1），但从不显示、也从不作为准入凭据。 */
    fun inviteOf(identity: DeviceIdentity): InviteCode =
        InviteCode.ofSecret(derive(identity, DOMAIN + "/invite", 5))

    /** 群密钥的种子（kGrp 由它派生）。同样固定，重启后 fp / proof 不变。 */
    fun keySeedOf(identity: DeviceIdentity): ByteArray = derive(identity, DOMAIN + "/key", 32)

    /** 群句柄：只给 mDNS 实例名用（个人空间不广播），保留它是为了 [GroupMeta] 的形状与普通群一致。 */
    fun handleOf(identity: DeviceIdentity): String = B32.encode(derive(identity, DOMAIN + "/handle", 5))

    /**
     * 建一条个人空间的群元数据。
     *
     * joinOpen = false 是**纵深防御**：真正的拒绝在 PeerLink 里（E_JOIN_PERSONAL，码更明确），
     * 万一以后有人把那条判断挪走了，关着的加入口也还能兜住。
     */
    fun meta(identity: DeviceIdentity, createdAt: Long): GroupMeta = GroupMeta(
        groupId = groupIdOf(identity),
        gHandle = handleOf(identity),
        name = NAME,
        invite = inviteOf(identity),
        keySeed = keySeedOf(identity),
        epoch = 0,
        joinOpen = false,
        createdAt = createdAt,
    )

    /** HKDF-SHA256：salt 为空（RFC 5869 的零盐），info 做域分离。 */
    private fun derive(identity: DeviceIdentity, info: String, length: Int): ByteArray =
        Hkdf.derive(identity.pub64, ByteArray(0), info, length)
}
