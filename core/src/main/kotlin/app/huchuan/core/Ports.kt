package app.huchuan.core

/**
 * 领域接口。M1 只有内存假实现；传输内核（BitTorrent）日后从这里接线，本模块不引用任何网络 API。
 */

/** 群组与群内文件。 */
interface GroupStore {
    fun groups(): List<Group>

    fun group(groupId: String): Group?

    fun createGroup(name: String): Group

    /** 只收邀请码后 8 位；不匹配返回 null。 */
    fun joinGroup(tail8: String): Group?

    fun setNotificationLevel(groupId: String, level: NotificationLevel)

    /** P4 激活/休眠开关；按 spec §5 状态机推导新状态。 */
    fun setFileActive(groupId: String, fileId: String, active: Boolean)

    fun setFileUploadLimit(groupId: String, fileId: String, kbps: Int)

    fun addComment(groupId: String, fileId: String, body: String, parentId: String?)
}

/** 本机设置：用户名、全局限速。 */
interface LocalDeviceStore {
    fun device(): LocalDevice

    fun saveUserName(name: String)

    fun setUploadLimit(kbps: Int)

    fun setDownloadLimit(kbps: Int)

    fun setUnlimited(unlimited: Boolean)
}

/** P0 网络检测。 */
interface NetworkProbe {
    fun check(): NetworkReport
}
