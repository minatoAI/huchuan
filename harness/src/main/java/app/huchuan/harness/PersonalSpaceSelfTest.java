package app.huchuan.harness;

import app.huchuan.core.m1.B32;
import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.GroupRuntime;
import app.huchuan.core.m1.InviteCode;
import app.huchuan.core.m1.LanDiscovery;
import app.huchuan.core.m1.LanEntry;
import app.huchuan.core.m1.M1Kernel;
import app.huchuan.core.m1.MemberRecord;
import app.huchuan.core.m1.OpRecord;
import app.huchuan.core.m1.TrackerProtocol;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 个人空间（docs/design/personal-space.md）的验收：创建、隔离、复用、重启往返。
 *
 * 六组断言，每组都有一个能把它打红的现实缺陷：
 *
 *   1. 创建与派生（created）：首次 start 之后个人空间就在，name 固定、isPersonal=true、
 *      gid 是 32 位 B32 且等于按 identity.txt 里的公钥独立重算的 HKDF 值。派生值改一个字节、
 *      或者哪天有人把它换回随机生成，这一组立刻红。
 *   2. mDNS（mdns）：个人空间不在广播名单里（内核读口 + 一条独立 LanDiscovery 的现场浏览），
 *      同时普通群必须在名单里 —— 后者是一条对照断言：广播整体坏掉时不能靠「都没广播」蒙混过关。
 *   3. tracker（tracker）：本地 mock tracker 只收到普通群那一次注册 / announce，个人空间零请求。
 *   4. 不可加入（joinRejected）：第二个内核拿个人空间的码去 join，必须拿到明确的
 *      E_JOIN_PERSONAL（对端 err 帧 + 本端 join_failed 的 reason 两条都要）。
 *   5. 复用（reusesFilePaths）：加文件 / 做种 / 评论 / 限速 / 休眠 / 再激活走的是同一套代码，
 *      所以个人空间与普通群在同一串动作之后的读数必须一模一样。
 *   6. 重启与旧库（restartRoundTrip / oldStateBackfill）：state.json 里带 isPersonal，重启后
 *      个人空间与文件都在、做种续上、不重复创建；另起一份没有个人空间的旧库，启动后补建且
 *      已有数据不受影响。
 */
final class PersonalSpaceSelfTest {

    private PersonalSpaceSelfTest() {
    }

    /** 固定名称（设计文档「名称 | 我的分享（固定，不可改）」）。 */
    private static final String EXPECTED_NAME = "我的分享";

    /** 派生 gid 用的 HKDF info（与 kernel 的 PersonalSpace.DOMAIN 同一个值）。 */
    private static final String HKDF_INFO_GID = "huchuan/personal/v1";

    static void run(BiConsumer<String, Boolean> check) {
        int[] counts = new int[2];
        BiConsumer<String, Boolean> counting = (name, ok) -> {
            counts[0]++;
            if (!ok) {
                counts[1]++;
            }
            check.accept(name, ok);
        };
        File root = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-personal-" + System.nanoTime());
        root.mkdirs();
        try {
            File dir = new File(root, "main");
            dir.mkdirs();
            TrackerSelfTest.Recorder events = new TrackerSelfTest.Recorder();
            M1Kernel kernel = new M1Kernel(dir, "personal-A", 47710, 0, events);
            GroupRuntime personal;
            GroupRuntime normal;
            FileEntry personalFile;
            try {
                kernel.start();
                personal = created(counting, kernel, events);
                normal = mdns(counting, kernel, personal);
                personalFile = reusesFilePaths(counting, kernel, personal, normal);
            } finally {
                kernel.close();
            }
            restartRoundTrip(counting, dir, personal.getGroupId(), personalFile,
                    normal.getGroupId(), normal.files().get(0).getFileId());
            oldStateBackfill(counting, new File(root, "old"));
            tracker(counting, new File(root, "tracker"));
            joinRejected(counting, new File(root, "join-host"), new File(root, "join-guest"));
        } catch (Throwable t) {
            counting.accept("personal.exception", false);
            t.printStackTrace(System.out);
        } finally {
            deleteTree(root);
        }
        System.out.println("selftest.personal checks=" + counts[0] + " failures=" + counts[1]);
    }

    // ---------------------------------------------------------------- 1. 创建与派生

    /**
     * 首次 start 之后：个人空间存在、名字固定、isPersonal=true、只有本机一个成员，
     * 而且 gid 就是「identity.txt 里的公钥 -> HKDF(personal/v1) -> 20 字节 -> Crockford Base32」。
     *
     * 期望值全部用 JDK 原语在本类里独立算（HMAC-SHA256 + 自己实现的位序编码），
     * 所以内核那边只要把 domain、ikm、字节数、位序改掉一个，这里立刻对不上。
     */
    private static GroupRuntime created(BiConsumer<String, Boolean> check, M1Kernel kernel,
                                        TrackerSelfTest.Recorder events) throws Exception {
        GroupRuntime personal = kernel.personalSpace();
        check.accept("personal.created_on_first_start",
                personal != null && kernel.groups().size() == 1);
        if (personal == null) {
            throw new IllegalStateException("personal space missing after start()");
        }
        check.accept("personal.is_personal_true", personal.isPersonal());
        check.accept("personal.name_is_fixed (" + personal.getName() + ")",
                EXPECTED_NAME.equals(personal.getName()));
        check.accept("personal.gid_is_32_b32_chars (" + personal.getGroupId() + ")",
                personal.getGroupId().length() == 32
                        && personal.getGroupId().chars().allMatch(c -> B32.ALPHABET.indexOf(c) >= 0));

        String expectedGid = derivedGid(new File(kernel.getDataDir(), "identity.txt"));
        check.accept("personal.gid_derived_from_identity (got " + personal.getGroupId()
                + " expected " + expectedGid + ")", personal.getGroupId().equals(expectedGid));

        List<MemberRecord> members = personal.members();
        check.accept("personal.single_member_is_self (members=" + members.size() + ")",
                members.size() == 1 && kernel.getDeviceId().equals(members.get(0).getDev()));
        check.accept("personal.no_online_peers", personal.onlinePeers().isEmpty());

        // 「不生成邀请码」在内核这一层的可证伪口径：派生码只在 GroupMeta 里当结构用，
        // 一条日志都不许带它出来（group_created 那条会带码，但它不该出现在个人空间上）。
        String code = personal.getInviteCode();
        check.accept("personal.invite_code_is_16_chars_structurally", code != null && code.length() == 16);
        check.accept("personal.invite_code_never_logged", !events.contains(code));
        check.accept("personal.ready_logged",
                events.has("personal_space_ready") && events.contains("created=true"));
        return personal;
    }

    // ---------------------------------------------------------------- 2. mDNS

    /**
     * 个人空间不广播，普通群照常广播。
     *
     * 两条独立的证据：① 内核的广播名单读口（M1Kernel.mdnsBroadcastGroupIds）；
     * ② 一个独立的 LanDiscovery 现场浏览 —— 它看到普通群、看不到个人空间。
     * 第二条是「真的没发到网上」而不是「名单里没写」，第一条是「连注册都没做」。
     */
    private static GroupRuntime mdns(BiConsumer<String, Boolean> check, M1Kernel kernel,
                                     GroupRuntime personal) throws Exception {
        GroupRuntime normal = kernel.createGroup("普通群");
        check.accept("mdns.normal_gid_is_16_chars (" + normal.getGroupId() + ")",
                normal.getGroupId().length() == 16);
        check.accept("mdns.personal_gid_differs_from_normal", !personal.getGroupId().equals(normal.getGroupId()));
        check.accept("mdns.normal_not_personal", !normal.isPersonal());

        List<String> broadcast = new ArrayList<>(kernel.mdnsBroadcastGroupIds());
        check.accept("mdns.broadcast_list_contains_normal (" + String.join(",", broadcast) + ")",
                broadcast.contains(normal.getGroupId()));
        check.accept("mdns.broadcast_list_excludes_personal (personal=" + personal.getGroupId() + ")",
                !broadcast.contains(personal.getGroupId()));

        // 现场浏览：起一个独立的 LanDiscovery（与内核用的是同一个组件），只收不发。
        LanDiscovery browser = new LanDiscovery();
        try {
            boolean started = browser.start();
            check.accept("mdns.browser_started", started);
            if (started) {
                List<LanEntry> entries = browser.browseEntries(4_000L);
                List<String> gids = new ArrayList<>();
                for (LanEntry entry : entries) {
                    gids.add(entry.getGid());
                }
                check.accept("mdns.browser_sees_normal (gids=" + String.join(",", gids) + ")",
                        gids.contains(normal.getGroupId()));
                check.accept("mdns.browser_never_sees_personal", !gids.contains(personal.getGroupId()));
            }
        } finally {
            try {
                browser.close();
            } catch (Throwable ignored) {
                // 关不掉不影响结论
            }
        }
        return normal;
    }

    // ---------------------------------------------------------------- 3. tracker

    /**
     * 自带 tracker：只有普通群注册 / announce，个人空间一条请求都没有。
     *
     * mock 记的是「客户端到底发了什么」，所以「零请求」是真的零：注册数必须是 1（普通群那一份），
     * 而且那一条的 gid / info_hash 必须都对上普通群 —— 个人空间要是偷偷注册了，注册数会先变成它。
     */
    private static void tracker(BiConsumer<String, Boolean> check, File dir) throws Exception {
        dir.mkdirs();
        try (MockTracker mock = new MockTracker()) {
            TrackerSelfTest.Recorder events = new TrackerSelfTest.Recorder();
            M1Kernel kernel = new M1Kernel(dir, "personal-tracker", 47720, 0, events);
            kernel.setTrackerBaseUrl(mock.baseUrl());
            kernel.start();
            try {
                check.accept("tracker.personal_skip_logged",
                        events.lines().stream().anyMatch(l -> l.startsWith("personal_space_tracker_skip")));
                GroupRuntime normal = kernel.createGroup("tracker-普通群");
                boolean arrived = await(() -> mock.registerCount() >= 1 && mock.announceCount() >= 1, 20_000L);
                check.accept("tracker.normal_group_registered", arrived);
                check.accept("tracker.exactly_one_register (" + mock.registerCount() + ")",
                        mock.registerCount() == 1);
                check.accept("tracker.register_is_normal_group",
                        mock.lastRegisterBody().contains("\"gid\":\"" + normal.getGroupId() + "\""));
                String expectedHash = hex(TrackerProtocol.INSTANCE.groupHash20(
                        B32.INSTANCE.decode(normal.getInviteCode())));
                List<String> queries = mock.announceQueries();
                boolean onlyNormal = !queries.isEmpty();
                for (String query : queries) {
                    byte[] announced = MockTracker.bytesParam(query, "info_hash");
                    if (announced == null || !expectedHash.equals(hex(announced))) {
                        onlyNormal = false;
                    }
                }
                check.accept("tracker.announce_only_normal (n=" + queries.size() + ")", onlyNormal);
            } finally {
                kernel.close();
            }
        }
    }

    // ---------------------------------------------------------------- 4. 不可加入

    /**
     * 另一个内核拿个人空间的码去加入：必须被明确拒绝（E_JOIN_PERSONAL），不是沉默超时。
     *
     * 现场：A 只有个人空间，所以它的 hello 会报出这个 gid（singleGroupOrNull）；B 连上之后
     * 按生产路径发出 join.req —— 正好复现「对端知道 gid 还来加入」这件事（旧数据 / 手动地址）。
     */
    private static void joinRejected(BiConsumer<String, Boolean> check, File hostDir, File guestDir)
            throws Exception {
        hostDir.mkdirs();
        guestDir.mkdirs();
        TrackerSelfTest.Recorder hostEvents = new TrackerSelfTest.Recorder();
        TrackerSelfTest.Recorder guestEvents = new TrackerSelfTest.Recorder();
        M1Kernel host = new M1Kernel(hostDir, "personal-host", 47730, 0, hostEvents);
        M1Kernel guest = new M1Kernel(guestDir, "personal-guest", 47740, 0, guestEvents);
        host.start();
        guest.start();
        try {
            GroupRuntime hostPersonal = host.personalSpace();
            check.accept("join.host_has_personal", hostPersonal != null);
            if (hostPersonal == null) {
                return;
            }
            String tail8 = hostPersonal.getInviteCode().substring(8);
            List<String> manual = new ArrayList<>();
            manual.add("127.0.0.1:" + host.localMessagePort());
            GroupRuntime joined = guest.joinByTail8(tail8, manual, 25_000L);

            check.accept("join.returned_null", joined == null);
            check.accept("join.req_targeted_personal_gid",
                    guestEvents.contains("gid=" + hostPersonal.getGroupId()));
            check.accept("join.host_logged_rejection",
                    hostEvents.contains("personal_space_join_rejected")
                            && hostEvents.contains("gid=" + hostPersonal.getGroupId()));
            check.accept("join.guest_got_explicit_code",
                    guestEvents.contains("code=E_JOIN_PERSONAL")
                            && guestEvents.contains("reason=E_JOIN_PERSONAL"));
            check.accept("join.guest_kernel_error_is_personal",
                    "E_JOIN_PERSONAL".equals(guest.lastJoinError()));

            // 两边都没有因为这次加入长出第二个群：guest 只剩自己的个人空间，host 的成员表没变。
            check.accept("join.guest_groups_unchanged (" + guest.groups().size() + ")",
                    guest.groups().size() == 1
                            && guest.groups().get(0).isPersonal()
                            && !guest.groups().get(0).getGroupId().equals(hostPersonal.getGroupId()));
            check.accept("join.host_members_unchanged (" + hostPersonal.members().size() + ")",
                    hostPersonal.members().size() == 1 && hostPersonal.onlinePeers().isEmpty());
        } finally {
            try {
                guest.close();
            } catch (Throwable ignored) {
                // 关不掉不影响结论
            }
            try {
                host.close();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    // ---------------------------------------------------------------- 5. 复用现有代码路径

    /**
     * 文件这条路没有第二套实现：个人空间就是普通 GroupRuntime，所以同一串动作之后
     * 两边的读数必须逐个相等（角色 / 内核状态 / 完整度 / 做种数 / 队友数 / 评论数）。
     *
     * 一旦有人给个人空间写一条「特殊分支」（比如跳过做种集合、或者评论不落 oplog），
     * 这里签名字符串立刻不一样。
     */
    private static FileEntry reusesFilePaths(BiConsumer<String, Boolean> check, M1Kernel kernel,
                                             GroupRuntime personal, GroupRuntime normal) throws Exception {
        File personalFile = new File(kernel.getDataDir(), "personal-payload.bin");
        File normalFile = new File(kernel.getDataDir(), "normal-payload.bin");
        File personalToggle = new File(kernel.getDataDir(), "personal-toggle.bin");
        File normalToggle = new File(kernel.getDataDir(), "normal-toggle.bin");
        M1Harness.createRandomFile(personalFile, 256 * 1024);
        M1Harness.createRandomFile(normalFile, 256 * 1024);
        M1Harness.createRandomFile(personalToggle, 128 * 1024);
        M1Harness.createRandomFile(normalToggle, 128 * 1024);

        FileEntry a = personal.addFile(personalFile);
        FileEntry b = normal.addFile(normalFile);
        FileEntry a2 = personal.addFile(personalToggle);
        FileEntry b2 = normal.addFile(normalToggle);
        check.accept("reuse.file_added_personal", a != null && a.getSize() == personalFile.length());
        check.accept("reuse.file_added_normal", b != null && b.getSize() == normalFile.length());

        String afterAddA = signature(personal, a.getFileId());
        String afterAddB = signature(normal, b.getFileId());
        check.accept("reuse.add_state_matches (" + afterAddA + ")",
                afterAddA.equals(afterAddB) && afterAddA.contains("role=SEEDING")
                        && afterAddA.contains("seeds=2"));

        // 评论：同一条 oplog 路径，个人空间的评论同样进 visibleOps。
        OpRecord opA = personal.comment(a.getFileId(), "个人空间里的一条评论", null);
        OpRecord opB = normal.comment(b.getFileId(), "普通群里的一条评论", null);
        check.accept("reuse.comment_personal", opA != null && personal.visibleOps(a.getFileId()).size() == 1);
        check.accept("reuse.comment_matches_normal",
                opB != null && normal.visibleOps(b.getFileId()).size() == 1
                        && signature(personal, a.getFileId()).equals(signature(normal, b.getFileId())));

        // 单文件限速：同一个 setUploadLimitKbps，不抛、状态不变。
        personal.setUploadLimitKbps(a.getFileId(), 128);
        normal.setUploadLimitKbps(b.getFileId(), 128);
        check.accept("reuse.upload_limit_matches_normal",
                signature(personal, a.getFileId()).equals(signature(normal, b.getFileId())));

        // 休眠 -> 再激活：做种集合的进出与普通群完全同一套判定（用第二条文件，第一条继续做种）。
        personal.stopFile(a2.getFileId());
        normal.stopFile(b2.getFileId());
        String hibernatedA = signature(personal, a2.getFileId());
        String hibernatedB = signature(normal, b2.getFileId());
        check.accept("reuse.hibernate_matches (" + hibernatedA + ")",
                hibernatedA.equals(hibernatedB) && hibernatedA.contains("role=NONE")
                        && hibernatedA.contains("seeds=1"));

        boolean reactivatedA = personal.startDownload(a2.getFileId());
        boolean reactivatedB = normal.startDownload(b2.getFileId());
        String reactivatedSigA = signature(personal, a2.getFileId());
        String reactivatedSigB = signature(normal, b2.getFileId());
        check.accept("reuse.reactivate_matches_normal (personal=" + reactivatedA + " normal=" + reactivatedB + ")",
                reactivatedA == reactivatedB && reactivatedSigA.equals(reactivatedSigB));
        // 第一条文件始终在做种：个人空间的文件还是要能分享出去（重启续种在 restart 那一组验）。
        check.accept("reuse.personal_still_seeding", personal.localRole(a.getFileId())
                == GroupRuntime.LocalRole.SEEDING);
        return a;
    }

    /** 一个群的状态签名：同一串动作之后个人空间与普通群必须完全一样。 */
    private static String signature(GroupRuntime group, String fileId) {
        return "role=" + group.localRole(fileId)
                + " state=" + group.kernelState(fileId)
                + " complete=" + group.isComplete(fileId)
                + " seeds=" + group.seedingFileCount()
                + " teammates=" + group.teammates(fileId)
                + " ops=" + group.visibleOps(fileId).size()
                + " files=" + group.files().size();
    }

    // ---------------------------------------------------------------- 6. 重启往返 / 旧库补建

    /**
     * 重启：同一份 state.json 起来之后，个人空间与它的文件都在、做种续上、不会建出第二个。
     * 同时验证 state.json 里真的写了 isPersonal（不是靠内存里的标记蒙过去的）。
     */
    private static void restartRoundTrip(BiConsumer<String, Boolean> check, File dir,
                                         String personalGid, FileEntry personalFile,
                                         String normalGid, String normalFileId) throws Exception {
        String stateText = new String(Files.readAllBytes(new File(dir, "state.json").toPath()),
                StandardCharsets.UTF_8);
        check.accept("restart.state_has_is_personal", stateText.contains("\"isPersonal\":true"));
        check.accept("restart.state_has_personal_gid", stateText.contains(personalGid));

        TrackerSelfTest.Recorder events = new TrackerSelfTest.Recorder();
        M1Kernel kernel = new M1Kernel(dir, "personal-A", 47710, 0, events);
        kernel.start();
        try {
            GroupRuntime personal = kernel.personalSpace();
            check.accept("restart.personal_present", personal != null
                    && personal.getGroupId().equals(personalGid)
                    && personal.isPersonal());
            if (personal == null) {
                return;
            }
            long personalGroups = kernel.groups().stream().filter(GroupRuntime::isPersonal).count();
            check.accept("restart.no_duplicate_personal (" + personalGroups + ")", personalGroups == 1L);
            check.accept("restart.file_still_listed", personal.file(personalFile.getFileId()) != null);
            check.accept("restart.seeding_resumed (role=" + personal.localRole(personalFile.getFileId()) + ")",
                    personal.localRole(personalFile.getFileId()) == GroupRuntime.LocalRole.SEEDING);
            check.accept("restart.comment_survives", personal.visibleOps(personalFile.getFileId()).size() == 1);
            GroupRuntime normalAgain = kernel.group(normalGid);
            check.accept("restart.normal_group_intact", normalAgain != null
                    && normalAgain.file(normalFileId) != null
                    && !normalAgain.isPersonal());
            check.accept("restart.ready_logged_as_reused",
                    events.contains("personal_space_ready") && events.contains("created=false"));
        } finally {
            kernel.close();
        }
    }

    /**
     * 旧库（state.json 里根本没有个人空间）：启动后补建，已有群一动不动。
     *
     * 这份 state.json 是手写的旧格式：只认 gid / code / keySeed 这几个必填字段，
     * 没有 isPersonal、没有个人空间 —— 正是升级现场的样子。
     */
    private static void oldStateBackfill(BiConsumer<String, Boolean> check, File dir) throws Exception {
        dir.mkdirs();
        InviteCode code = InviteCode.Companion.create(new SecureRandom());
        byte[] keySeed = new byte[32];
        new SecureRandom().nextBytes(keySeed);
        String oldGid = "0123456789ABCDEF";
        String state = "{\"format\":\"huchuan-m1-state/1\","
                + "\"device\":{\"dev\":\"0000000000000000\",\"name\":\"老库\"},"
                + "\"keys\":{},"
                + "\"groups\":[{\"gid\":\"" + oldGid + "\","
                + "\"gHandle\":\"HANDLE01\","
                + "\"name\":\"升级前就有的群\","
                + "\"code\":\"" + code.getCode() + "\","
                + "\"keySeed\":\"" + hex(keySeed) + "\","
                + "\"keySeedProt\":\"plain\","
                + "\"epoch\":0,\"joinOpen\":true,\"createdAt\":1762381200000,"
                + "\"members\":[],\"files\":[]}]}";
        Files.write(new File(dir, "state.json").toPath(), state.getBytes(StandardCharsets.UTF_8));

        TrackerSelfTest.Recorder events = new TrackerSelfTest.Recorder();
        M1Kernel kernel = new M1Kernel(dir, "personal-legacy", 47750, 0, events);
        kernel.start();
        try {
            GroupRuntime old = kernel.group(oldGid);
            check.accept("legacy.existing_group_intact", old != null
                    && "升级前就有的群".equals(old.getName()) && !old.isPersonal());
            GroupRuntime personal = kernel.personalSpace();
            check.accept("legacy.personal_backfilled", personal != null && personal.isPersonal()
                    && kernel.groups().size() == 2);
            check.accept("legacy.backfill_logged", events.has("personal_space_ready"));
            String rewritten = new String(Files.readAllBytes(new File(dir, "state.json").toPath()),
                    StandardCharsets.UTF_8);
            check.accept("legacy.flag_written_back", rewritten.contains("\"isPersonal\":true"));
        } finally {
            kernel.close();
        }
    }

    // ---------------------------------------------------------------- 派生值的独立重算

    /**
     * 从 identity.txt 取出 64 字节公钥，按 HKDF-SHA256(pub64, salt="", info="huchuan/personal/v1", 20)
     * 独立重算个人空间 gid，再按 Crockford Base32 的位序（大端、末组右补 0）编码。
     *
     * 期望值一律用 JDK 的 HMAC 与本类自己的位循环算 —— 内核那边挪动任何一处都会对不上。
     */
    private static String derivedGid(File identityFile) throws Exception {
        String pubBase64 = "";
        for (String line : Files.readAllLines(identityFile.toPath(), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            pubBase64 = trimmed;
        }
        byte[] der = Base64.getDecoder().decode(pubBase64);
        // SubjectPublicKeyInfo 末尾是 BIT STRING：03 42 00 04 || x(32) || y(32)
        if (der.length < 68 || (der[der.length - 68] & 0xff) != 0x03
                || (der[der.length - 67] & 0xff) != 0x42 || (der[der.length - 66] & 0xff) != 0x00
                || (der[der.length - 65] & 0xff) != 0x04) {
            throw new IllegalStateException("unexpected EC public key encoding in " + identityFile);
        }
        byte[] pub64 = Arrays.copyOfRange(der, der.length - 64, der.length);
        byte[] gid = hkdf(pub64, new byte[0], HKDF_INFO_GID.getBytes(StandardCharsets.UTF_8), 20);
        return base32(gid);
    }

    /** RFC 5869 HKDF-SHA256：extract(salt, ikm) -> expand(info, len)，只用 JDK 的 Mac。 */
    private static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        byte[] effectiveSalt = salt.length == 0 ? new byte[32] : salt;
        mac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] block = new byte[0];
        int counter = 1;
        while (out.size() < length) {
            mac.reset();
            mac.update(block);
            mac.update(info);
            mac.update((byte) counter);
            block = mac.doFinal();
            out.write(block, 0, block.length);
            counter++;
        }
        byte[] all = out.toByteArray();
        return Arrays.copyOf(all, length);
    }

    /** 协议 §2.1 的 Crockford Base32：大端位串，末组右补 0。 */
    private static String base32(byte[] bytes) {
        int totalBits = bytes.length * 8;
        int outLen = (totalBits + 4) / 5;
        StringBuilder sb = new StringBuilder(outLen);
        for (int i = 0; i < outLen; i++) {
            int value = 0;
            for (int j = 0; j < 5; j++) {
                int bitIndex = i * 5 + j;
                int bit = 0;
                if (bitIndex < totalBits) {
                    int b = bytes[bitIndex / 8] & 0xff;
                    bit = (b >>> (7 - bitIndex % 8)) & 1;
                }
                value = (value << 1) | bit;
            }
            sb.append(B32.ALPHABET.charAt(value));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 工具

    private static boolean await(java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException e) {
                return condition.getAsBoolean();
            }
        }
        return condition.getAsBoolean();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(java.util.Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        // Windows 上刚停掉的会话 / mDNS 可能还占着句柄：删不掉就算了，临时目录不参与断言。
        file.delete();
    }
}
