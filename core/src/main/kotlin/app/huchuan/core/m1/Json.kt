package app.huchuan.core.m1

/**
 * 协议 §5 的 JSON 层：禁浮点、字符串 UTF-8。
 * 自己写而不是引库，理由见 docs/core.md：桌面 JDK 没有 org.json，kotlinx.serialization
 * 要加编译器插件，而本协议只需要「对象/数组/字符串/整数/布尔」五种值。
 */
sealed class J {
    object Null : J()
    data class Bool(val value: Boolean) : J()
    data class Num(val value: Long) : J()
    data class Str(val value: String) : J()
    data class Arr(val values: List<J>) : J()
    data class Obj(val fields: Map<String, J>) : J()

    fun asObj(): Obj = this as? Obj ?: throw ProtocolException("expected object, got " + kind())

    fun kind(): String = when (this) {
        is Null -> "null"
        is Bool -> "bool"
        is Num -> "number"
        is Str -> "string"
        is Arr -> "array"
        is Obj -> "object"
    }

    // ---- 宽松读取（未知字段一律忽略，缺失返回 null） ----
    operator fun get(key: String): J? = (this as? Obj)?.fields?.get(key)
    fun str(key: String): String? = (get(key) as? Str)?.value
    fun num(key: String): Long? = (get(key) as? Num)?.value
    fun bool(key: String): Boolean? = (get(key) as? Bool)?.value
    fun arr(key: String): List<J>? = (get(key) as? Arr)?.values
    fun obj(key: String): Obj? = get(key) as? Obj

    // ---- 必需字段（缺失/类型错 → E_PROTO，协议 §5） ----
    fun reqStr(key: String): String = str(key) ?: throw ProtocolException("missing string field '" + key + "'")
    fun reqNum(key: String): Long = num(key) ?: throw ProtocolException("missing number field '" + key + "'")
}

class ProtocolException(message: String) : Exception(message)

class JsonParseException(message: String) : Exception(message)

fun jobj(vararg pairs: Pair<String, J>): J.Obj {
    val m = LinkedHashMap<String, J>(pairs.size)
    for ((k, v) in pairs) m[k] = v
    return J.Obj(m)
}

fun jstr(s: String?): J.Str = J.Str(s ?: "")
fun jnum(v: Long): J.Num = J.Num(v)
fun jbool(v: Boolean): J.Bool = J.Bool(v)
fun jarr(values: List<J>): J.Arr = J.Arr(values)

object Json {
    fun write(v: J): String {
        val sb = StringBuilder(256)
        writeValue(sb, v)
        return sb.toString()
    }

    fun parse(text: String): J {
        val p = Parser(text)
        val v = p.parseValue(0)
        p.skipWs()
        if (!p.atEnd()) throw JsonParseException("trailing data at offset " + p.pos)
        return v
    }

    private fun writeValue(sb: StringBuilder, v: J) {
        when (v) {
            is J.Null -> sb.append("null")
            is J.Bool -> sb.append(if (v.value) "true" else "false")
            is J.Num -> sb.append(v.value)
            is J.Str -> writeString(sb, v.value)
            is J.Arr -> {
                sb.append('[')
                for (i in v.values.indices) {
                    if (i > 0) sb.append(',')
                    writeValue(sb, v.values[i])
                }
                sb.append(']')
            }
            is J.Obj -> {
                sb.append('{')
                var first = true
                for ((k, value) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeValue(sb, value)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000c' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWs() {
            while (pos < s.length) {
                when (s[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        fun parseValue(depth: Int): J {
            if (depth > 32) throw JsonParseException("nesting too deep")
            skipWs()
            if (atEnd()) throw JsonParseException("unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> parseObj(depth)
                '[' -> parseArr(depth)
                '"' -> J.Str(parseString())
                't' -> parseLiteral("true", J.Bool(true))
                'f' -> parseLiteral("false", J.Bool(false))
                'n' -> parseLiteral("null", J.Null)
                else -> if (c == '-' || c in '0'..'9') parseNum() else throw JsonParseException("unexpected char '" + c + "' at " + pos)
            }
        }

        private fun parseLiteral(text: String, value: J): J {
            if (!s.startsWith(text, pos)) throw JsonParseException("bad literal at " + pos)
            pos += text.length
            return value
        }

        private fun parseObj(depth: Int): J {
            pos++ // {
            val fields = LinkedHashMap<String, J>()
            skipWs()
            if (!atEnd() && s[pos] == '}') {
                pos++
                return J.Obj(fields)
            }
            while (true) {
                skipWs()
                if (atEnd() || s[pos] != '"') throw JsonParseException("expected key at " + pos)
                val key = parseString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw JsonParseException("expected ':' at " + pos)
                pos++
                fields[key] = parseValue(depth + 1)
                skipWs()
                if (atEnd()) throw JsonParseException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return J.Obj(fields)
                    }
                    else -> throw JsonParseException("expected ',' or '}' at " + pos)
                }
            }
        }

        private fun parseArr(depth: Int): J {
            pos++ // [
            val values = ArrayList<J>()
            skipWs()
            if (!atEnd() && s[pos] == ']') {
                pos++
                return J.Arr(values)
            }
            while (true) {
                values.add(parseValue(depth + 1))
                skipWs()
                if (atEnd()) throw JsonParseException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return J.Arr(values)
                    }
                    else -> throw JsonParseException("expected ',' or ']' at " + pos)
                }
            }
        }

        private fun parseString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonParseException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonParseException("unterminated escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonParseException("bad \\u escape")
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw JsonParseException("bad escape '\\" + e + "'")
                        }
                    }
                    else -> {
                        if (c.code < 0x20) throw JsonParseException("raw control char in string")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNum(): J {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && s[pos] in '0'..'9') pos++
            if (pos < s.length && (s[pos] == '.' || s[pos] == 'e' || s[pos] == 'E')) {
                throw JsonParseException("floats are forbidden by protocol (protocol.md §5)")
            }
            val text = s.substring(start, pos)
            if (text.isEmpty() || text == "-") throw JsonParseException("bad number at " + start)
            return try {
                J.Num(text.toLong())
            } catch (e: NumberFormatException) {
                throw JsonParseException("number out of range: " + text)
            }
        }
    }
}
