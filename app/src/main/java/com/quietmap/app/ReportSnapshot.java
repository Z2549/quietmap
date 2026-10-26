package com.quietmap.app;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 诊断快照的纯 Java JSON 编解码，格式固定为：
 * {"v":1,"updated_at":<毫秒>,"session":"<uuid>","data":{"reg.xxx":"..."}}。
 *
 * session 标识目标进程的一次运行（进程启动时生成 UUID）；跨进程写同一文件时，
 * 只有同一 session 才合并，旧 session 的数据整体替换，避免旧 skip/reg 被盖上
 * 当前时间戳后继续显示。旧版本文件没有 session 字段，解析为 null。
 *
 * 不依赖 android.* / org.json，JVM 单测可直接覆盖序列化、反序列化与损坏输入。
 * 只做结构校验，键白名单/长度校验在 {@link ReportProtocol}，读取侧由
 * {@link ReportFileStore#sanitize(Map)} 再过滤一次。
 */
public final class ReportSnapshot {

    public static final int VERSION = 1;

    /** 快照最多 1000 个键；留足余量，同时防止损坏文件把内存撑爆。 */
    public static final int MAX_ENTRIES = 1000;

    private static final int MAX_INPUT_CHARS = 256 * 1024;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_SESSION_CHARS = 128;

    private final long updatedAt;
    private final String session;
    private final Map<String, String> data;

    public ReportSnapshot(long updatedAt, Map<String, String> data) {
        this(updatedAt, null, data);
    }

    public ReportSnapshot(long updatedAt, String session, Map<String, String> data) {
        this.updatedAt = updatedAt;
        this.session = session;
        this.data = Collections.unmodifiableMap(copyWithoutNulls(data));
    }

    public long updatedAt() {
        return updatedAt;
    }

    /** 进程运行标识；旧文件/占位文件可能为 null。 */
    public String session() {
        return session;
    }

    public Map<String, String> data() {
        return data;
    }

    /** 空占位文件内容；updated_at=0 会被读取侧当作无数据。 */
    public static String emptyJson() {
        return toJson(0L, Collections.emptyMap());
    }

    /** 稳定输出（键按字典序），同一份快照生成的字节可直接比较。 */
    public static String toJson(long updatedAt, Map<String, String> data) {
        return toJson(updatedAt, null, data);
    }

    /** 带 session 的稳定输出；session 为 null/空时不写该字段（兼容旧格式）。 */
    public static String toJson(long updatedAt, String session, Map<String, String> data) {
        Map<String, String> sorted = copyWithoutNulls(data);
        StringBuilder sb = new StringBuilder(160 + sorted.size() * 48);
        sb.append("{\"v\":").append(VERSION)
                .append(",\"updated_at\":").append(Math.max(0L, updatedAt));
        if (session != null && !session.isEmpty()) {
            sb.append(",\"session\":");
            writeString(sb, session);
        }
        sb.append(",\"data\":{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, e.getKey());
            sb.append(':');
            writeString(sb, e.getValue());
        }
        return sb.append("}}").toString();
    }

    private static Map<String, String> copyWithoutNulls(Map<String, String> data) {
        Map<String, String> out = new TreeMap<>();
        if (data == null) {
            return out;
        }
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    /**
     * 解析快照；任何结构问题（截断、类型错误、未知版本、超长）一律返回 null，
     * 不向外抛异常。未知字段会被跳过，便于以后加字段时旧版本仍可读。
     */
    public static ReportSnapshot parse(String json) {
        if (json == null || json.isEmpty() || json.length() > MAX_INPUT_CHARS) {
            return null;
        }
        try {
            Parser parser = new Parser(json);
            Object root = parser.value(0);
            parser.skipWhitespace();
            if (!parser.atEnd() || !(root instanceof Map)) {
                return null;
            }
            Map<?, ?> object = (Map<?, ?>) root;
            Object version = object.get("v");
            if (!(version instanceof Long) || (Long) version != VERSION) {
                return null;
            }
            Object updated = object.get("updated_at");
            if (!(updated instanceof Long)) {
                return null;
            }
            long updatedAt = (Long) updated;
            if (updatedAt < 0L) {
                return null;
            }
            String session = null;
            Object sessionObject = object.get("session");
            if (sessionObject != null) {
                if (!(sessionObject instanceof String)
                        || ((String) sessionObject).length() > MAX_SESSION_CHARS) {
                    return null;
                }
                session = (String) sessionObject;
            }
            Object dataObject = object.get("data");
            if (!(dataObject instanceof Map)) {
                return null;
            }
            Map<String, String> data = new TreeMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) dataObject).entrySet()) {
                if (!(e.getKey() instanceof String) || !(e.getValue() instanceof String)) {
                    return null;
                }
                data.put((String) e.getKey(), (String) e.getValue());
            }
            if (data.size() > MAX_ENTRIES) {
                return null;
            }
            return new ReportSnapshot(updatedAt, session, data);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 跨进程写同一文件时的合并规则，与 StatusProvider 的 prefs 语义一致：
     * hit 取历史最大值，reg/skip 由本次写入覆盖，未出现在本次快照里的键保留。
     */
    public static Map<String, String> merge(Map<String, String> existing,
                                            Map<String, String> incoming) {
        Map<String, String> out = new TreeMap<>();
        if (existing != null) {
            out.putAll(existing);
        }
        if (incoming == null) {
            return out;
        }
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String key = e.getKey();
            String value = e.getValue();
            if (key == null || value == null) {
                continue;
            }
            if (key.startsWith(ReportProtocol.PREFIX_HIT)) {
                Long next = ReportProtocol.parseHit(value);
                if (next == null) {
                    continue;
                }
                Long prev = ReportProtocol.parseHit(out.get(key));
                out.put(key, String.valueOf(
                        ReportProtocol.mergeHit(prev == null ? 0L : prev, next)));
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            sb.append('0');
                        }
                        sb.append(hex);
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    /** 只解析 JSON 子集：object / array / string / integer / true / false / null。 */
    private static final class Parser {

        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWhitespace() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw fail("depth");
            }
            skipWhitespace();
            if (atEnd()) {
                throw fail("eof");
            }
            char c = s.charAt(pos);
            switch (c) {
                case '{':
                    return object(depth + 1);
                case '[':
                    return array(depth + 1);
                case '"':
                    return string();
                case 't':
                    literal("true");
                    return Boolean.TRUE;
                case 'f':
                    literal("false");
                    return Boolean.FALSE;
                case 'n':
                    literal("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw fail("unexpected " + c);
            }
        }

        private Map<String, Object> object(int depth) {
            expect('{');
            Map<String, Object> out = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw fail("key");
                }
                String key = string();
                skipWhitespace();
                expect(':');
                out.put(key, value(depth));
                if (out.size() > MAX_ENTRIES) {
                    throw fail("too many entries");
                }
                skipWhitespace();
                char c = next();
                if (c == '}') {
                    return out;
                }
                if (c != ',') {
                    throw fail("object");
                }
            }
        }

        private List<Object> array(int depth) {
            expect('[');
            List<Object> out = new java.util.ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(value(depth));
                if (out.size() > MAX_ENTRIES) {
                    throw fail("too many array items");
                }
                skipWhitespace();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw fail("array");
                }
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw fail("string eof");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    if (c < 0x20) {
                        throw fail("control char");
                    }
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw fail("escape eof");
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        int cp = 0;
                        for (int i = 0; i < 4; i++) {
                            if (atEnd()) {
                                throw fail("unicode eof");
                            }
                            int digit = Character.digit(s.charAt(pos++), 16);
                            if (digit < 0) {
                                throw fail("unicode digit");
                            }
                            cp = (cp << 4) | digit;
                        }
                        sb.append((char) cp);
                        break;
                    default:
                        throw fail("escape " + e);
                }
            }
        }

        private Long number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            int digits = pos;
            while (pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '9') {
                pos++;
            }
            if (pos == digits) {
                throw fail("number");
            }
            if (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == '.' || c == 'e' || c == 'E') {
                    throw fail("non-integer");
                }
            }
            try {
                return Long.parseLong(s.substring(start, pos));
            } catch (NumberFormatException e) {
                throw fail("number overflow");
            }
        }

        private void literal(String literal) {
            if (!s.startsWith(literal, pos)) {
                throw fail("literal");
            }
            pos += literal.length();
        }

        private void expect(char expected) {
            skipWhitespace();
            char c = next();
            if (c != expected) {
                throw fail("expected " + expected);
            }
        }

        private char peek() {
            if (atEnd()) {
                throw fail("eof");
            }
            return s.charAt(pos);
        }

        private char next() {
            if (atEnd()) {
                throw fail("eof");
            }
            return s.charAt(pos++);
        }

        private IllegalArgumentException fail(String message) {
            return new IllegalArgumentException(message + " at " + pos);
        }
    }
}
