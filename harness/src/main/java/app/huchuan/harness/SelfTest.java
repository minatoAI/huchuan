package app.huchuan.harness;

import app.huchuan.core.m1.B32;
import app.huchuan.core.m1.BtSession;
import app.huchuan.core.m1.DeviceIdentity;
import app.huchuan.core.m1.Domains;
import app.huchuan.core.m1.FileEntry;
import app.huchuan.core.m1.Frames;
import app.huchuan.core.m1.Frame;
import app.huchuan.core.m1.Hkdf;
import app.huchuan.core.m1.InviteCode;
import app.huchuan.core.m1.J;
import app.huchuan.core.m1.Json;
import app.huchuan.core.m1.JsonParseException;
import app.huchuan.core.m1.EntryCheck;
import app.huchuan.core.m1.MemberNameMsg;
import app.huchuan.core.m1.OpRecord;
import app.huchuan.core.m1.PeerAnnounceMsg;
import app.huchuan.core.m1.Proto;
import app.huchuan.core.m1.PubRegistry;
import app.huchuan.core.m1.Candidate;
import app.huchuan.core.m1.ReadCursor;
import app.huchuan.core.m1.MemberDialPolicy;
import app.huchuan.core.m1.ReconnectPolicy;
import app.huchuan.core.m1.SeedRestore;
import app.huchuan.core.m1.SeedingLedger;
import app.huchuan.core.m1.SignBytes;
import app.huchuan.core.m1.Unread;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 字节层的自检：B32 / HKDF(RFC 5869) / 邀请码 / P-256 签名 / signBytes / JSON / 帧。
 * 这些是「两端能互通」的地基，跑一次成本 1 秒，必须在每次 M1 验收前过一遍。
 */
public final class SelfTest {

    private static int checks = 0;
    private static final List<String> failures = new ArrayList<>();

    /** 会话级限速那组真流量用例的体积：8 MiB 在 1024 KB/s 的限额下标称要 8 s，足够把「没限住」暴露出来。 */
    private static final int RATE_TRANSFER_BYTES = 8 * 1024 * 1024;

    public static void main(String[] args) {
        // 同一轮 runId 前缀：selftest 的每一行也要能被断言认出是本轮产物。
        RunLog.install();
        try {
            b32();
            hkdfRfc5869();
            inviteCode();
            identity();
            joinAndFileSigning();
            creatorEntryVerification();
            peerAnnounceFields();
            unreadCursor();
            seedingLedger();
            reconnectPolicy();
            memberDialPolicy();
            sessionRateLimits();
            publicSwarm();
            tracker();
            signBytes();
            json();
            framing();
        } catch (Throwable t) {
            failures.add("exception: " + t);
            t.printStackTrace(System.out);
        }
        System.out.println("selftest.checks=" + checks + " failures=" + failures.size());
        for (String f : failures) {
            System.out.println("selftest.FAIL " + f);
        }
        if (failures.isEmpty()) {
            System.out.println("RESULT=PASS");
            System.exit(0);
        }
        System.out.println("RESULT=FAIL");
        System.exit(1);
    }

    /**
     * 跨网发现（自建 tracker）：groupHash20 / passkey 公式、注册与 announce 参数、peer 并入候选表、
     * baseUrl 为空时零请求。装置是一个本机最小 BEP 3 tracker（[MockTracker]），
     * 断言全在 [TrackerSelfTest] 里，避免把一个已经很长的字节层自检撑爆。
     */
    private static void tracker() {
        TrackerSelfTest.run(SelfTest::check);
    }

    /**
     * 公开种子社区（public-swarm）：公开会话 DHT 开 / LSD 关 / uTP 开 / 端口 6881 起、
     * per-torrent flags 显式清零、会话归属（公开拒 private、私有拒 public）、
     * 磁力加种进公开会话 + 五种失败各一句话、同 infohash 两种 scope 的去重（正反两种顺序）。
     * 会真起两个 libtorrent 会话，断言全在 [PublicSwarmSelfTest] 里。
     */
    private static void publicSwarm() {
        PublicSwarmSelfTest.run(SelfTest::check);
    }

    private static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            failures.add(name);
            System.out.println("selftest.CHECK FAIL " + name);
        }
    }

    // ---------------------------------------------------------------- B32

    private static void b32() {
        Random random = new Random(7);
        for (int size : new int[]{5, 10, 16, 20}) {
            byte[] raw = new byte[size];
            random.nextBytes(raw);
            String encoded = B32.INSTANCE.encode(raw);
            int expectedLength = (size * 8 + 4) / 5;
            check("b32.length." + size, encoded.length() == expectedLength);
            check("b32.roundtrip." + size, Arrays.equals(raw, B32.INSTANCE.decode(encoded)));
        }
        check("b32.zero5", "00000000".equals(B32.INSTANCE.encode(new byte[5])));
        check("b32.normalize", "01".equals(B32.INSTANCE.normalize("O-I")));
        boolean threw = false;
        try {
            // 9 个字符 = 45 bit，末 5 bit 有 3 bit 补位；如果非 0 必须被判非法
            B32.INSTANCE.decode("00000000Z");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check("b32.padding_bits_rejected", threw);
    }

    // ---------------------------------------------------------------- HKDF

    private static void hkdfRfc5869() {
        byte[] ikm = new byte[22];
        Arrays.fill(ikm, (byte) 0x0b);
        byte[] salt = hex("000102030405060708090a0b0c");
        byte[] info = hex("f0f1f2f3f4f5f6f7f8f9");
        byte[] prk = Hkdf.INSTANCE.extract(salt, ikm);
        check("hkdf.rfc5869.prk",
                "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5".equals(hex(prk)));
        byte[] okm = Hkdf.INSTANCE.expand(prk, info, 42);
        check("hkdf.rfc5869.okm",
                "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"
                        .equals(hex(okm)));
    }

    // ---------------------------------------------------------------- 邀请码

    private static void inviteCode() {
        // Kotlin 的默认参数对 Java 不可见，这里显式给 SecureRandom
        InviteCode code = InviteCode.Companion.create(new java.security.SecureRandom());
        check("invite.code_len16", code.getCode().length() == 16);
        check("invite.tail8_len8", code.getTail8().length() == 8);
        check("invite.pretty", code.getPretty().length() == 19);
        check("invite.pretty_matches_code", code.getCode().equals(code.getPretty().replace("-", "")));
        check("invite.pretty_4_4_4_4", code.getPretty().split("-").length == 4);
        check("invite.kjoin_len32", code.getKJoin().length == 32);

        InviteCode fromTail = InviteCode.Companion.fromTail8(code.getTail8());
        check("invite.tail8_rebuilds_full_code",
                fromTail != null && code.getCode().equals(fromTail.getCode()));
        check("invite.tail8_same_kjoin",
                fromTail != null && Arrays.equals(code.getKJoin(), fromTail.getKJoin()));

        InviteCode fromFull = InviteCode.Companion.fromFull(code.getPretty());
        check("invite.full_code_accepted", fromFull != null && code.getCode().equals(fromFull.getCode()));

        // 改一个字符 → 校验位对不上 → null（§2.1）
        char first = code.getCode().charAt(0);
        char swapped = first == 'Z' ? 'Y' : 'Z';
        String tampered = swapped + code.getCode().substring(1);
        check("invite.tampered_rejected", InviteCode.Companion.fromFull(tampered) == null);

        check("invite.bad_charset_rejected", InviteCode.Companion.fromTail8("UUUUUUUU") == null);
        check("invite.short_rejected", InviteCode.Companion.fromTail8("ABC") == null);
    }

    // ---------------------------------------------------------------- 身份

    private static void identity() {
        DeviceIdentity identity = DeviceIdentity.Companion.generate();
        check("identity.pub64_len64", identity.getPub64().length == 64);
        check("identity.device_id_len16", identity.getDeviceId().length() == 16);
        check("identity.device_id_derivable",
                identity.getDeviceId().equals(DeviceIdentity.Companion.deviceIdOf(identity.getPub64())));

        List<byte[]> fields = new ArrayList<>();
        fields.add(SignBytes.INSTANCE.str("file-1"));
        fields.add(SignBytes.INSTANCE.u64(1234567890123L));
        String sig = identity.sign(Domains.FILE, fields);
        check("identity.sig_len128", sig.length() == 128);
        check("identity.verify_ok", DeviceIdentity.Companion.verify(identity.getPub64(), sig, Domains.FILE, fields));

        // 低-S：s ≤ n/2（§1.1）
        BigInteger s = new BigInteger(1, hex(sig.substring(64)));
        BigInteger n = new BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
        check("identity.low_s", s.compareTo(n.shiftRight(1)) <= 0);

        // 换个 domain / 改一个字段 / 换一把钥匙都必须验不过
        check("identity.wrong_domain_rejected",
                !DeviceIdentity.Companion.verify(identity.getPub64(), sig, Domains.OP, fields));
        List<byte[]> altered = new ArrayList<>();
        altered.add(SignBytes.INSTANCE.str("file-2"));
        altered.add(SignBytes.INSTANCE.u64(1234567890123L));
        check("identity.altered_field_rejected",
                !DeviceIdentity.Companion.verify(identity.getPub64(), sig, Domains.FILE, altered));
        DeviceIdentity other = DeviceIdentity.Companion.generate();
        check("identity.other_key_rejected",
                !DeviceIdentity.Companion.verify(other.getPub64(), sig, Domains.FILE, fields));

        // r=0 / s=0 必须拒（§1.1）
        check("identity.zero_sig_rejected",
                !DeviceIdentity.Companion.verify(identity.getPub64(), "0".repeat(128), Domains.FILE, fields));
    }

    // ---------------------------------------------------------------- join.ok / file.list / member.name 字段

    /**
     * 这三处签名口径是 M1.1 的收敛点：join.ok 换 joinok1 domain、file.list 条目自带原 sig、
     * member.name 带 pub 且由 deviceId 钉死。字段错一个，两端就会静默不认，所以钉在自检里。
     */
    private static void joinAndFileSigning() {
        check("joinok.domain", "huchuan/joinok1/v1".equals(Domains.JOIN_OK));
        check("joinok.domain_distinct_from_join", !Domains.JOIN_OK.equals(Domains.JOIN));

        DeviceIdentity identity = DeviceIdentity.Companion.generate();
        List<byte[]> fields = new ArrayList<>();
        fields.add(SignBytes.INSTANCE.str(identity.getDeviceId()));
        fields.add(SignBytes.INSTANCE.str("7Q2F9C4M1XZ8KD3V"));
        fields.add(SignBytes.INSTANCE.str("4e21".repeat(16)));
        fields.add(SignBytes.INSTANCE.str("K2M9QXDP"));
        fields.add(SignBytes.INSTANCE.str("0d7f".repeat(8)));
        fields.add(SignBytes.INSTANCE.u64(1762381210400L));
        String joinOkSig = identity.sign(Domains.JOIN_OK, fields);
        check("joinok.sign_verifies",
                DeviceIdentity.Companion.verify(identity.getPub64(), joinOkSig, Domains.JOIN_OK, fields));
        check("joinok.join_domain_rejected",
                !DeviceIdentity.Companion.verify(identity.getPub64(), joinOkSig, Domains.JOIN, fields));

        FileEntry entry = new FileEntry(
                "8b3f0c0e1d2a4b6c8d0e2f4a6b8c0d1e2f3a4b5c",
                "发布会素材.zip",
                734003200L,
                262144,
                identity.getDeviceId(),
                1762381300000L,
                "magnet:?xt=urn:btih:8b3f",
                "",
                "");
        String entrySig = identity.sign(Domains.FILE, entry.signFields());
        FileEntry signed = new FileEntry(
                entry.getFileId(), entry.getName(), entry.getSize(), entry.getPieceSize(),
                entry.getAddedBy(), entry.getAddedAt(), entry.getMagnet(), entry.getTorrent(), entrySig);
        FileEntry parsed = FileEntry.Companion.fromJson(Json.INSTANCE.parse(Json.INSTANCE.write(signed.toJson())));
        check("filelist.entry_sig_roundtrip", entrySig.equals(parsed.getSig()));
        check("filelist.entry_sig_verifies", DeviceIdentity.Companion.verify(
                identity.getPub64(), parsed.getSig(), Domains.FILE, parsed.signFields()));
        FileEntry tampered = new FileEntry(
                entry.getFileId(), "别的名字.zip", entry.getSize(), entry.getPieceSize(),
                entry.getAddedBy(), entry.getAddedAt(), entry.getMagnet(), entry.getTorrent(), entrySig);
        check("filelist.tampered_entry_rejected", !DeviceIdentity.Companion.verify(
                identity.getPub64(), tampered.getSig(), Domains.FILE, tampered.signFields()));

        MemberNameMsg msg = MemberNameMsg.Companion.fromJson(Json.INSTANCE.parse(
                "{\"dev\":\"" + identity.getDeviceId() + "\",\"name\":\"老板的笔记本\",\"nameSeq\":3,"
                        + "\"ts\":1762381200000,\"sig\":\"" + joinOkSig + "\",\"pub\":\""
                        + hex(identity.getPub64()) + "\"}"));
        check("membername.pub_roundtrip", msg.getPub().equals(hex(identity.getPub64())));
        check("membername.pub_pins_device_id",
                DeviceIdentity.Companion.deviceIdOf(hex(msg.getPub())).equals(msg.getDev()));
    }

    // ---------------------------------------------------------------- signBytes

    private static void signBytes() {
        List<byte[]> a = new ArrayList<>();
        a.add(SignBytes.INSTANCE.str("dev"));
        a.add(SignBytes.INSTANCE.str(""));
        a.add(SignBytes.INSTANCE.u64(1L));
        byte[] one = SignBytes.INSTANCE.of(Domains.HELLO, a);
        byte[] two = SignBytes.INSTANCE.of(Domains.HELLO, a);
        check("signbytes.deterministic", Arrays.equals(one, two));
        check("signbytes.domain_prefix", hex(one).startsWith(hex("huchuan/hello1/v1".getBytes())));

        List<byte[]> b = new ArrayList<>();
        b.add(SignBytes.INSTANCE.str(""));
        b.add(SignBytes.INSTANCE.str("dev"));
        b.add(SignBytes.INSTANCE.u64(1L));
        check("signbytes.order_matters", !Arrays.equals(one, SignBytes.INSTANCE.of(Domains.HELLO, b)));

        // 整数恒 8 字节：u64(1) 的字段长度前缀必须是 0x0008
        byte[] onlyInt = SignBytes.INSTANCE.of("d", List.of(SignBytes.INSTANCE.u64(1L)));
        String hexed = hex(onlyInt);
        check("signbytes.int_is_8_bytes", hexed.endsWith("00080000000000000001"));
        // 空值只写长度 0，禁止 "null" 字面量
        check("signbytes.empty_is_len0", hex(SignBytes.INSTANCE.of("d", List.of(SignBytes.INSTANCE.str(null)))).endsWith("0000"));
    }

    // ---------------------------------------------------------------- JSON

    private static void json() {
        String text = "{\"a\":1,\"b\":\"x\\u00e9\\n\",\"c\":[true,null],\"d\":{\"e\":-2}}";
        J parsed = Json.INSTANCE.parse(text);
        String rewritten = Json.INSTANCE.write(parsed);
        J again = Json.INSTANCE.parse(rewritten);
        check("json.roundtrip", Json.INSTANCE.write(again).equals(rewritten));
        check("json.number", numField(objField(parsed, "d"), "e") == -2L);
        check("json.bool_null", ((J.Arr) field(parsed, "c")).getValues().size() == 2);

        boolean rejected = false;
        try {
            Json.INSTANCE.parse("{\"x\":1.5}");
        } catch (Exception e) {
            rejected = e.getClass().getSimpleName().equals("JsonParseException");
        }
        check("json.floats_rejected", rejected);

        boolean badJson = false;
        try {
            Json.INSTANCE.parse("{\"x\":}");
        } catch (Exception e) {
            badJson = e.getClass().getSimpleName().equals("JsonParseException");
        }
        check("json.malformed_rejected", badJson);

        String unicode = "老板的群·K2M9";
        Map<String, J> fields = new LinkedHashMap<>();
        fields.put("gname", new J.Str(unicode));
        fields.put("n", new J.Num(33554432L));
        J back = Json.INSTANCE.parse(Json.INSTANCE.write(new J.Obj(fields)));
        J.Str name = (J.Str) field(back, "gname");
        check("json.utf8_roundtrip", name != null && unicode.equals(name.getValue()));
    }

    // ---------------------------------------------------------------- 帧

    private static void framing() throws Exception {
        Map<String, J> bodyFields = new LinkedHashMap<>();
        bodyFields.put("t", new J.Str("ping"));
        bodyFields.put("ts", new J.Num(1762381200000L));
        J.Obj body = new J.Obj(bodyFields);
        byte[] encoded = Frames.INSTANCE.encode(0x40, body);
        check("framing.len_prefix", ((encoded[0] & 0xff) << 24 | (encoded[1] & 0xff) << 16
                | (encoded[2] & 0xff) << 8 | (encoded[3] & 0xff)) == encoded.length - 4);
        check("framing.ver_and_type", encoded[4] == 1 && encoded[5] == 0x40);

        Frame frame = Frames.INSTANCE.read(new DataInputStream(new ByteArrayInputStream(encoded)));
        check("framing.roundtrip_type", frame != null && frame.getType() == 0x40);
        check("framing.roundtrip_ts", frame != null && numField(frame.getBody(), "ts") == 1762381200000L);

        boolean tooLarge = false;
        try {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 70_000; i++) {
                sb.append('x');
            }
            Map<String, J> big = new LinkedHashMap<>();
            big.put("t", new J.Str(sb.toString()));
            Frames.INSTANCE.encode(0x40, new J.Obj(big));
        } catch (IllegalArgumentException e) {
            tooLarge = true;
        }
        check("framing.max_65536_enforced", tooLarge);
    }

    // ---------------------------------------------------------------- 创建者验自己的条目（缺陷回归）

    /**
     * 「创建者能验自己的条目」——M1 的缺陷回归断言。
     *
     * 缺陷现场：createGroup 只把公钥写进成员表、没写进公钥库，于是同一个进程里，别人把创建者发布的
     * 条目从 file.list 回传时，创建者按 addedBy 取不到公钥，全按 reason=no_pub 丢掉
     * （证据 artifacts/m1/log-catchup-a.log，232 条）。
     *
     * 这里钉的是修好之后必须成立的两件事，用生产代码（PubRegistry + EntryCheck）而不是重写一遍逻辑：
     *   1. 创建者把自己的 pub64 登记进库后，自己签的 file1 条目必须验得过；
     *   2. 没登记时 reason 必须是 no_pub —— 也就是原缺陷的确切症状，防止有人把验签放宽成「找不到公钥就放过」。
     */
    private static void creatorEntryVerification() {
        DeviceIdentity creator = DeviceIdentity.Companion.generate();
        PubRegistry registry = new PubRegistry();
        FileEntry unsigned = new FileEntry(
                "c0ffee00c0ffee00c0ffee00c0ffee00c0ffee00",
                "创建者自己发的文件.bin",
                1048576L,
                65536,
                creator.getDeviceId(),
                1762381400000L,
                "magnet:?xt=urn:btih:c0ffee00",
                "",
                "");
        String sig = creator.sign(Domains.FILE, unsigned.signFields());
        FileEntry signed = new FileEntry(
                unsigned.getFileId(), unsigned.getName(), unsigned.getSize(), unsigned.getPieceSize(),
                unsigned.getAddedBy(), unsigned.getAddedAt(), unsigned.getMagnet(), unsigned.getTorrent(), sig);

        // 没登记 = 原缺陷：条目好端端的，就是没人认得作者的公钥
        check("creator.unregistered_entry_is_no_pub",
                "no_pub".equals(EntryCheck.INSTANCE.reason(registry.of(creator.getDeviceId()), signed)));

        // createGroup 现在做的就是这一句
        check("creator.self_pub_register_changed", registry.register(creator.getDeviceId(), creator.getPub64()));
        check("creator.self_pub_idempotent", !registry.register(creator.getDeviceId(), creator.getPub64()));
        check("creator.self_entry_verifies",
                EntryCheck.INSTANCE.accepts(registry.of(creator.getDeviceId()), signed));
        check("creator.self_entry_reason_empty",
                "".equals(EntryCheck.INSTANCE.reason(registry.of(creator.getDeviceId()), signed)));

        // 换一把钥匙仍然必须拒：修的是「取不到公钥」，不是放宽验签
        DeviceIdentity other = DeviceIdentity.Companion.generate();
        check("creator.other_key_entry_bad_sig",
                "bad_sig".equals(EntryCheck.INSTANCE.reason(other.getPub64(), signed)));

        // 条目的 sig 被清掉时必须报 no_sig（file.list 的原样携带要求）
        FileEntry noSig = new FileEntry(
                signed.getFileId(), signed.getName(), signed.getSize(), signed.getPieceSize(),
                signed.getAddedBy(), signed.getAddedAt(), signed.getMagnet(), signed.getTorrent(), "");
        check("creator.entry_without_sig_no_sig",
                "no_sig".equals(EntryCheck.INSTANCE.reason(creator.getPub64(), noSig)));
    }

    // ---------------------------------------------------------------- peer.announce 补的两个字段（G3）

    /**
     * 协议 §10 的 0x30 只补两个可选字段：plat（android/windows/unknown）与 seeds（自报做种数）。
     * 三件事必须同时成立，否则两端会静默不认：
     *   1. JSON 往返保真；2. 缺字段有默认值；3. 认不出的平台归一成 unknown（不拒绝整帧）；
     *   4. **它们不进 peer1 签名域** —— 只补字段，不改既有签名口径（老实现的 sig 仍然验得过）。
     */
    private static void peerAnnounceFields() {
        String body = "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                + "\"plat\":\"android\",\"seeds\":3,\"ts\":1762381200500,\"sig\":\"1c2d\"}";
        PeerAnnounceMsg parsed = PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(body));
        check("peerannounce.plat_roundtrip", "android".equals(parsed.getPlatform()));
        check("peerannounce.seeds_roundtrip", parsed.getSeeds() == 3);
        check("peerannounce.endpoint_unchanged", parsed.getBtPort() == 51413 && "1c2d".equals(parsed.getSig()));

        PeerAnnounceMsg bare = PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                        + "\"ts\":1762381200500,\"sig\":\"1c2d\"}"));
        check("peerannounce.missing_plat_is_unknown", "unknown".equals(bare.getPlatform()));
        check("peerannounce.missing_seeds_is_zero", bare.getSeeds() == 0);
        check("peerannounce.negative_seeds_clamped",
                PeerAnnounceMsg.Companion.fromJson(Json.INSTANCE.parse(
                        "{\"gid\":\"7Q2F9C4M1XZ8KD3V\",\"dev\":\"7Q2F9C4M1XZ8KD3V\",\"btPort\":51413,"
                                + "\"seeds\":-5,\"ts\":1762381200500,\"sig\":\"1c2d\"}")).getSeeds() == 0);

        check("peerannounce.plat_unknown_value_normalized",
                "unknown".equals(Proto.INSTANCE.normalizePlatform("Linux")));
        check("peerannounce.plat_windows_normalized",
                "windows".equals(Proto.INSTANCE.normalizePlatform("Windows")));
        check("peerannounce.plat_android_normalized",
                "android".equals(Proto.INSTANCE.normalizePlatform(" Android ")));
        String detected = Proto.INSTANCE.detectPlatform();
        check("proto.platform_detected_one_of_three",
                "android".equals(detected) || "windows".equals(detected) || "unknown".equals(detected));

        // 签名域不含 plat / seeds：改了这两个字段，signBytes 的字节必须一模一样
        PeerAnnounceMsg a = new PeerAnnounceMsg("7Q2F9C4M1XZ8KD3V", "7Q2F9C4M1XZ8KD3V", 51413, "",
                1762381200500L, "", "android", 3);
        PeerAnnounceMsg b = new PeerAnnounceMsg("7Q2F9C4M1XZ8KD3V", "7Q2F9C4M1XZ8KD3V", 51413, "",
                1762381200500L, "", "windows", 9);
        boolean sameFields = true;
        List<byte[]> fa = a.signFields();
        List<byte[]> fb = b.signFields();
        if (fa.size() != fb.size()) {
            sameFields = false;
        } else {
            for (int i = 0; i < fa.size(); i++) {
                if (!Arrays.equals(fa.get(i), fb.get(i))) sameFields = false;
            }
        }
        check("peerannounce.plat_seeds_outside_signature", sameFields);
        check("peerannounce.sign_fields_count", fa.size() == 4);
    }

    // ---------------------------------------------------------------- 未读游标（G4）

    /**
     * 未读数 G4 的记账口径：游标记「上次读到哪条 op」，未读 = 键严格大于游标的可见 op 条数。
     * 排序键就是协议 §6 的 (lamport, author 字典序, seq)，所以这里连「同 lamport 不同作者」
     * 的并列情况一起钉住 —— 顺序错一位，未读数就会差一条。
     */
    private static void unreadCursor() {
        OpRecord op1 = new OpRecord("OP1", "F1", "AAAA", 1L, 10L, 1000L, 0, "", "第一条", "", "sig1");
        OpRecord op2 = new OpRecord("OP2", "F1", "AAAA", 2L, 11L, 2000L, 0, "", "第二条", "OP1", "sig2");
        OpRecord op3 = new OpRecord("OP3", "F1", "AAAA", 3L, 12L, 3000L, 0, "", "第三条", "OP2", "sig3");
        List<OpRecord> ops = new ArrayList<>();
        ops.add(op1);
        ops.add(op2);
        ops.add(op3);

        check("unread.no_cursor_counts_all", Unread.INSTANCE.count(ops, null) == 3);
        check("unread.zero_cursor_counts_all",
                Unread.INSTANCE.count(ops, ReadCursor.Companion.getZERO()) == 3);

        ReadCursor afterFirst = ReadCursor.Companion.of(op1);
        check("unread.after_first_has_two", Unread.INSTANCE.count(ops, afterFirst) == 2);
        check("unread.after_last_is_zero",
                Unread.INSTANCE.count(ops, ReadCursor.Companion.of(op3)) == 0);
        check("unread.newest_is_last_op", ReadCursor.Companion.of(op3).equals(Unread.INSTANCE.newest(ops)));
        check("unread.empty_newest_is_zero",
                ReadCursor.Companion.getZERO().equals(Unread.INSTANCE.newest(new ArrayList<>())));
        check("unread.cursor_is_strictly_greater",
                Unread.INSTANCE.count(ops, ReadCursor.Companion.of(op2)) == 1);

        // 同 lamport、不同作者：作者字典序决定先后，未读数必须跟着这个全序走
        OpRecord opX = new OpRecord("OPX", "F1", "AAAA", 4L, 20L, 4000L, 0, "", "x", "OP3", "sigx");
        OpRecord opY = new OpRecord("OPY", "F1", "BBBB", 1L, 20L, 4001L, 0, "", "y", "", "sigy");
        List<OpRecord> tied = new ArrayList<>();
        tied.add(opY);
        tied.add(opX);
        check("unread.author_tiebreak_smaller_author_first",
                ReadCursor.Companion.of(opX).compareTo(ReadCursor.Companion.of(opY)) < 0);
        check("unread.author_tiebreak_leaves_one", Unread.INSTANCE.count(tied, ReadCursor.Companion.of(opX)) == 1);

        // 游标落盘往返：state.json 里读回来的游标必须还能用
        ReadCursor back = ReadCursor.Companion.fromJson(Json.INSTANCE.parse(Json.INSTANCE.write(afterFirst.toJson())));
        check("unread.cursor_json_roundtrip", afterFirst.equals(back));
        check("unread.cursor_roundtrip_still_counts", Unread.INSTANCE.count(ops, back) == 2);
    }

    // ---------------------------------------------------------------- 做种集合 + 重启续种（缺陷回归 / G2）

    /**
     * 缺陷回归 + 重启续种，四件事全部走生产代码（SeedingLedger / SeedRestore，不在这里重写一遍逻辑）：
     *   ① 下载完成 → 做种数从 0 变 1
     *   ② 休眠 → 变回 0
     *   ③ 重启恢复（state.json 里的做种集合）→ 仍是 1
     *   ④ 文件缺失 → 判定为不恢复（降级为休眠），计数保持 0，不抛异常
     *
     * 真机现场（docs/m1-device.md §6-6）：一台设备下载完并且正在做种，自报 seeds=0，P6 上显示「做种 0」。
     * 根因是 seedingFileCount() 只数 addFile 加进去的那一份、下载完成（markCompleted）不进集合 ——
     * 完成判定与做种集合是两套口径。①③ 就是原来错的那两格。
     */
    private static void seedingLedger() {
        // GroupRuntime.files 的键集：做种数只数清单里还有的那些
        List<String> manifest = List.of("F1");
        SeedingLedger ledger = new SeedingLedger();
        check("seeding.count_zero_when_nothing_seeding", ledger.count(manifest) == 0);

        // ① 下载完成 → 0 变 1
        check("seeding.enter_on_download_complete", ledger.enter("F1", "/data/downloads/payload.bin"));
        check("seeding.count_after_complete_is_1", ledger.count(manifest) == 1);
        check("seeding.is_seeding_after_complete", ledger.isSeeding("F1"));
        check("seeding.local_path_kept",
                "/data/downloads/payload.bin".equals(ledger.localPath("F1")));
        // 幂等：同一份数据重复完成（重连 / 重复事件）不能把做种数算成两份
        check("seeding.enter_idempotent",
                !ledger.enter("F1", "/data/downloads/payload.bin") && ledger.count(manifest) == 1);
        // 只数清单里还有的：集合里有 F2，清单里没有 F2
        ledger.enter("F2", "/data/downloads/other.bin");
        check("seeding.count_only_manifest_entries", ledger.count(manifest) == 1);
        ledger.leave("F2");

        // ② 休眠 → 变回 0
        check("seeding.leave_on_hibernate", ledger.leave("F1"));
        check("seeding.count_after_hibernate_is_0", ledger.count(manifest) == 0);
        check("seeding.leave_unknown_is_noop", !ledger.leave("F1"));

        // ③ 重启恢复：state.json 里落盘的做种集合在新一轮里重建（GroupRuntime.resumeSeeds 的成功分支）
        ledger.enter("F1", "/data/downloads/payload.bin");
        List<kotlin.Pair<String, String>> persisted = ledger.entries();
        check("seeding.persisted_file_id_and_path",
                persisted.size() == 1
                        && "F1".equals(persisted.get(0).getFirst())
                        && "/data/downloads/payload.bin".equals(persisted.get(0).getSecond()));
        check("seedrestore.decide_resume",
                SeedRestore.INSTANCE.decide(true, true, true, true) == SeedRestore.Decision.RESUME);
        SeedingLedger restarted = new SeedingLedger();
        check("seeding.restore_after_restart",
                restarted.enter(persisted.get(0).getFirst(), persisted.get(0).getSecond()));
        check("seeding.count_after_restart_is_1", restarted.count(manifest) == 1);

        // ④ 文件缺失 → 不恢复（降级为休眠）：计数保持 0，不抛异常
        SeedRestore.Decision missing = SeedRestore.INSTANCE.decide(true, true, false, false);
        check("seedrestore.file_missing_is_not_resume",
                missing == SeedRestore.Decision.FILE_MISSING && !missing.getResume());
        check("seedrestore.file_missing_reason", "file_missing".equals(missing.getReason()));
        check("seedrestore.size_mismatch",
                SeedRestore.INSTANCE.decide(true, true, true, false) == SeedRestore.Decision.SIZE_MISMATCH);
        check("seedrestore.no_entry",
                SeedRestore.INSTANCE.decide(false, false, true, true) == SeedRestore.Decision.NO_ENTRY);
        check("seedrestore.no_torrent",
                SeedRestore.INSTANCE.decide(true, false, true, true) == SeedRestore.Decision.NO_TORRENT);
        SeedingLedger afterMissing = new SeedingLedger();
        check("seeding.count_after_missing_file_is_0",
                afterMissing.count(manifest) == 0 && afterMissing.isEmpty());
    }

    /**
     * 缺口一的退避与候选编排（ReconnectPolicy）：冷启动重连的「什么时候拨、拨哪几个」就是这两条纯函数。
     * 全部走生产代码 —— 不在这里重写一遍退避表，否则脚本绿了也不代表内核用的是这张表。
     */
    private static void reconnectPolicy() {
        long[] EXPECTED = {1000L, 2000L, 4000L, 8000L, 16000L, 30000L};
        boolean scheduleOk = true;
        for (int i = 0; i < EXPECTED.length; i++) {
            scheduleOk &= ReconnectPolicy.INSTANCE.backoffMs(i) == EXPECTED[i];
            check("reconnect.backoff." + i + "_is_" + EXPECTED[i] + "ms",
                    ReconnectPolicy.INSTANCE.backoffMs(i) == EXPECTED[i]);
        }
        check("reconnect.backoff_table", scheduleOk);
        // 超出表长按 30 s 封顶：连续失败很久之后不能无限涨（也不可能变成 0 或负数）
        check("reconnect.backoff_caps_at_30s",
                ReconnectPolicy.INSTANCE.backoffMs(6) == 30000L
                        && ReconnectPolicy.INSTANCE.backoffMs(99) == 30000L);
        check("reconnect.backoff_negative_is_first", ReconnectPolicy.INSTANCE.backoffMs(-3) == 1000L);
        check("reconnect.max_parallel_is_3", ReconnectPolicy.INSTANCE.MAX_PARALLEL_DIALS == 3);

        List<Candidate> candidates = List.of(
                new Candidate("192.168.1.10", 47100, "peer"),
                new Candidate("192.168.1.10", 47100, "manual"),   // 同一端点：去重
                new Candidate("192.168.1.11", 47101, "manual"),
                new Candidate("192.168.1.12", 47102, "lan"),
                new Candidate("192.168.1.13", 47103, "lan"));
        List<Candidate> planned = ReconnectPolicy.INSTANCE.plan(candidates, List.of(), 3);
        check("reconnect.plan_budget_is_3", planned.size() == 3);
        check("reconnect.plan_dedup_same_endpoint",
                planned.size() == 3 && "192.168.1.10:47100".equals(planned.get(0).getKey()));
        check("reconnect.plan_keeps_order",
                "192.168.1.10:47100".equals(planned.get(0).getKey())
                        && "192.168.1.11:47101".equals(planned.get(1).getKey())
                        && "192.168.1.12:47102".equals(planned.get(2).getKey()));

        // 已经在拨的端点不重拨：否则每 250 ms 一拍会把同一个地址连炸
        List<Candidate> skipBusy = ReconnectPolicy.INSTANCE.plan(candidates, List.of("192.168.1.10:47100"), 3);
        check("reconnect.plan_skips_in_flight",
                skipBusy.size() == 3 && "192.168.1.11:47101".equals(skipBusy.get(0).getKey()));
        // 并发上限用满时不再派新拨号
        check("reconnect.plan_zero_budget",
                ReconnectPolicy.INSTANCE.plan(candidates, List.of(), 0).isEmpty());
        // 四个不同端点全部在拨 → 本轮没有可派的（并发上限 3 时不会走到「全都在拨」，
        // 但预算算错、把预算和数据源搞混时会在这里露出来）
        check("reconnect.plan_all_in_flight",
                ReconnectPolicy.INSTANCE.plan(candidates,
                        List.of("192.168.1.10:47100", "192.168.1.11:47101",
                                "192.168.1.12:47102", "192.168.1.13:47103"),
                        3).isEmpty());
        // 端口非法的候选直接丢掉（手动地址解析出来的 0 端口之类）
        check("reconnect.plan_drops_invalid_port",
                ReconnectPolicy.INSTANCE.plan(
                        List.of(new Candidate("192.168.1.20", 0, "manual")), List.of(), 3).isEmpty());
        check("reconnect.plan_empty_input",
                ReconnectPolicy.INSTANCE.plan(List.of(), List.of(), 3).isEmpty());
    }

    /**
     * 成员间 BT 拨号（MemberDialPolicy）：修的是「已有 1 条 peer 就整轮不拨」的短路。
     *
     * 这里的每条断言都对应缺陷现场的一个后果：只要「有 peer 就跳过」回来，
     * per_member_dial（单做种者先连上后仍要拨第二个成员）立刻 FAIL。
     */
    private static void memberDialPolicy() {
        MemberDialPolicy.Target s = new MemberDialPolicy.Target("dev-s", "192.168.1.10", 51413);
        MemberDialPolicy.Target c2 = new MemberDialPolicy.Target("dev-c2", "192.168.1.11", 51513);
        MemberDialPolicy.Target c3 = new MemberDialPolicy.Target("dev-c3", "192.168.1.12", 51613);
        List<MemberDialPolicy.Target> all = List.of(s, c2, c3);

        // 核心：已经连上做种者（s）之后，剩下的成员仍然各拨一次 —— 不是「有 peer 就整轮放弃」
        List<MemberDialPolicy.Target> afterSeed = MemberDialPolicy.INSTANCE.plan(
                all, List.of("dev-s"), List.of(), 3);
        check("dial.plan_per_member_not_has_peers", afterSeed.size() == 2);
        check("dial.plan_skips_only_connected_member",
                afterSeed.size() == 2
                        && "dev-c2".equals(afterSeed.get(0).getDev())
                        && "dev-c3".equals(afterSeed.get(1).getDev()));

        // 幂等：所有成员都互连之后不再拨任何人（重复调用 connectSeeders 不会重拨）
        check("dial.plan_idempotent_when_all_connected",
                MemberDialPolicy.INSTANCE.plan(all, List.of("dev-s", "dev-c2", "dev-c3"), List.of(), 3)
                        .isEmpty());

        // 退避：还在窗口里的候选不提前重拨，窗口外的照常拨
        check("dial.plan_skips_throttled",
                MemberDialPolicy.INSTANCE.plan(all, List.of(),
                        List.of(s.getKey(), c2.getKey()), 3).size() == 1);
        check("dial.plan_throttled_key_carries_dev",
                s.getKey().startsWith("dev-s@") && s.getKey().endsWith(":51413"));

        // 预算：每轮最多 MAX_PARALLEL_DIALS 个，且按给定顺序截断
        List<MemberDialPolicy.Target> budgeted = MemberDialPolicy.INSTANCE.plan(all, List.of(), List.of(), 2);
        check("dial.plan_budget_truncates", budgeted.size() == 2
                && "dev-s".equals(budgeted.get(0).getDev())
                && "dev-c2".equals(budgeted.get(1).getDev()));
        check("dial.plan_zero_budget", MemberDialPolicy.INSTANCE.plan(all, List.of(), List.of(), 0).isEmpty());
        check("dial.plan_empty_targets",
                MemberDialPolicy.INSTANCE.plan(List.of(), List.of(), List.of(), 3).isEmpty());

        // 端点还没自报全（端口 0）的成员没有可拨的地址，跳过而不是拨 0 端口
        MemberDialPolicy.Target noPort = new MemberDialPolicy.Target("dev-x", "192.168.1.13", 0);
        check("dial.plan_drops_invalid_port",
                MemberDialPolicy.INSTANCE.plan(List.of(noPort), List.of(), List.of(), 3).isEmpty());

        // 同一台机器上的两个成员（同 host、不同端口）是两条独立候选：去重键带 dev，不能按地址合并
        MemberDialPolicy.Target a = new MemberDialPolicy.Target("dev-a", "127.0.0.1", 51513);
        MemberDialPolicy.Target b = new MemberDialPolicy.Target("dev-b", "127.0.0.1", 51513);
        check("dial.plan_same_host_two_members",
                MemberDialPolicy.INSTANCE.plan(List.of(a, b), List.of(), List.of(), 3).size() == 2);
        check("dial.plan_same_host_keys_differ", !a.getKey().equals(b.getKey()));

        // 退避表直接复用 ReconnectPolicy（不另立一套），这里钉一次两者是同一个数
        check("dial.backoff_is_reconnect_table",
                ReconnectPolicy.INSTANCE.backoffMs(0) == 1000L
                        && ReconnectPolicy.INSTANCE.backoffMs(5) == 30000L);
    }

    // ---------------------------------------------------------------- 会话级限速（P7 全局限速）

    /**
     * 会话级（P7 全局）限速，三件事全部走生产代码（BtSession，真起一个 libtorrent 会话）：
     *
     *   ① 读回口径：设 512 → 读回 512（KB/s）；设 0 → 读回 0；连续改两次以最后一次为准。
     *      读回读的是会话里**生效**的值（session::get_settings() 同步到会话线程再返回），不是我们的缓存。
     *   ② 私网 / 回环对端必须也在限速范围内。libtorrent 默认把局域网与回环对端放进 local peer class，
     *      而 upload_rate_limit / download_rate_limit 只作用在 global class 上 —— 只设这两个值时，
     *      读数照样是「已设置」，本产品的全部流量（局域网 + 回环）却一点没慢。
     *      实测（2.1.0-39，16 MiB loopback）：设 2048 KB/s 之后仍按 31 MB/s 跑完。
     *   ③ 真流量：torrent 已经在会话里（不是新建会话才生效），限速后 loopback 传输必须真的慢下来。
     */
    private static void sessionRateLimits() {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "huchuan-selftest-ratelimit-" + System.nanoTime());
        root.mkdirs();
        BtSession bt = null;
        try {
            bt = new BtSession(0, new File(root, "bt"), true);
            int port = bt.start();
            check("ratelimit.session_started (port=" + port + ")", port > 0);
            check("ratelimit.local_peers_in_global_class", bt.localPeersRateLimited());

            bt.setSessionUploadLimit(512);
            check("ratelimit.upload_512_readback (got " + bt.sessionUploadLimitKbps() + ")",
                    bt.sessionUploadLimitKbps() == 512);
            bt.setSessionDownloadLimit(256);
            check("ratelimit.download_256_readback (got " + bt.sessionDownloadLimitKbps() + ")",
                    bt.sessionDownloadLimitKbps() == 256);
            check("ratelimit.upload_survives_download_set (got " + bt.sessionUploadLimitKbps() + ")",
                    bt.sessionUploadLimitKbps() == 512);

            bt.setSessionUploadLimit(0);
            check("ratelimit.upload_zero_readback (got " + bt.sessionUploadLimitKbps() + ")",
                    bt.sessionUploadLimitKbps() == 0);
            bt.setSessionDownloadLimit(0);
            check("ratelimit.download_zero_readback (got " + bt.sessionDownloadLimitKbps() + ")",
                    bt.sessionDownloadLimitKbps() == 0);

            bt.setSessionUploadLimit(1024);
            bt.setSessionUploadLimit(64);
            check("ratelimit.last_write_wins (got " + bt.sessionUploadLimitKbps() + ")",
                    bt.sessionUploadLimitKbps() == 64);
            bt.setSessionDownloadLimit(3072);
            bt.setSessionDownloadLimit(256);
            check("ratelimit.last_write_wins_download (got " + bt.sessionDownloadLimitKbps() + ")",
                    bt.sessionDownloadLimitKbps() == 256);
        } catch (Throwable t) {
            failures.add("ratelimit.readback_exception: " + t);
            t.printStackTrace(System.out);
        } finally {
            if (bt != null) {
                try {
                    bt.stop();
                } catch (Throwable ignored) {
                    // 停不掉不影响断言结论
                }
            }
        }

        // ③ 真流量：不设限额 vs 设 1024 KB/s。8 MiB 在 1 MB/s 的限额下至少要 5 s（标称 8 s）。
        try {
            long baseline = transferMillis(root, "baseline", 0);
            long limited = transferMillis(root, "limited", 1024);
            check("ratelimit.baseline_transfer_completes (ms=" + baseline + ")", baseline > 0);
            check("ratelimit.limit_slows_existing_torrent (baseline=" + baseline + "ms limited=" + limited + "ms)",
                    limited >= 5000 && limited >= baseline);
        } catch (Throwable t) {
            failures.add("ratelimit.transfer_exception: " + t);
            t.printStackTrace(System.out);
        } finally {
            deleteTree(root);
        }
    }

    /** 一轮两内核 loopback 传输，返回从 connect_peer 到下载完成的毫秒数；limitKbps=0 表示不设限。 */
    private static long transferMillis(File root, String tag, int limitKbps) throws Exception {
        File share = new File(root, tag + "-share");
        File save = new File(root, tag + "-save");
        share.mkdirs();
        save.mkdirs();
        File payload = new File(share, "payload.bin");
        writeRandom(payload, RATE_TRANSFER_BYTES);

        BtSession seed = new BtSession(0, new File(root, tag + "-bt-seed"), true);
        BtSession leech = new BtSession(0, new File(root, tag + "-bt-leech"), true);
        try {
            seed.start();
            leech.start();
            BtSession.SeededTorrent seeded = seed.createPrivateTorrent(payload);
            seed.addSeed(seeded, share);
            // 等做种就绪：否则片上校验的时间会被算进「传输耗时」，把不设限那一轮也拖慢。
            long ready = System.currentTimeMillis();
            while (!"finished".equals(seed.seedState(seeded.getFileId()))) {
                if (System.currentTimeMillis() - ready > 20_000) {
                    throw new IllegalStateException("seed not ready");
                }
                Thread.sleep(20);
            }
            leech.addDownload(seeded.getFileId(), seeded.getTorrentBytes(), save);
            if (limitKbps > 0) {
                // 限速在连 peer 之前设：torrent 已经在会话里（证明「对已存在的 torrent 生效」），
                // 连接建立后带宽通道一开始就带着限额。
                leech.setSessionDownloadLimit(limitKbps);
                seed.setSessionUploadLimit(limitKbps);
            }
            long start = System.nanoTime();
            leech.connectPeer(seeded.getFileId(), "127.0.0.1", seed.getListenPort());
            while (!leech.isFinished(seeded.getFileId())) {
                if (System.nanoTime() - start > 60_000_000_000L) {
                    throw new IllegalStateException("transfer timeout (tag=" + tag + ")");
                }
                Thread.sleep(10);
            }
            return (System.nanoTime() - start) / 1_000_000L;
        } finally {
            try {
                seed.stop();
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                leech.stop();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    private static void writeRandom(File file, int bytes) throws Exception {
        byte[] chunk = new byte[64 * 1024];
        Random random = new Random(11);
        try (FileOutputStream out = new FileOutputStream(file)) {
            int written = 0;
            while (written < bytes) {
                random.nextBytes(chunk);
                int n = Math.min(chunk.length, bytes - written);
                out.write(chunk, 0, n);
                written += n;
            }
        }
    }

    private static void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteTree(child);
        }
        // Windows 上刚停掉的会话可能还占着句柄：删不掉就算了，临时目录不参与断言。
        file.delete();
    }

    private static J field(J value, String key) {
        return ((J.Obj) value).getFields().get(key);
    }

    private static J.Obj objField(J value, String key) {
        return (J.Obj) field(value, key);
    }

    private static long numField(J value, String key) {
        J.Num n = (J.Num) field(value, key);
        return n == null ? Long.MIN_VALUE : n.getValue();
    }

    // ---------------------------------------------------------------- 工具

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
