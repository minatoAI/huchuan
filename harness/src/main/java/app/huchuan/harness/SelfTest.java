package app.huchuan.harness;

import app.huchuan.core.m1.B32;
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
import app.huchuan.core.m1.MemberNameMsg;
import app.huchuan.core.m1.SignBytes;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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

    public static void main(String[] args) {
        // 同一轮 runId 前缀：selftest 的每一行也要能被断言认出是本轮产物。
        RunLog.install();
        try {
            b32();
            hkdfRfc5869();
            inviteCode();
            identity();
            joinAndFileSigning();
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
