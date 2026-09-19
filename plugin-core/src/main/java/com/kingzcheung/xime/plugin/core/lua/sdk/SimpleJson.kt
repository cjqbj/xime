package com.kingzcheung.xime.plugin.core.lua.sdk

/**
 * 轻量 JSON 编解码（纯 Kotlin，无 Android org.json 依赖）。
 *
 * 用于 Lua 插件 `host.json.encode/decode`：插件用 Lua table 组装 dashscope 协议，
 * 宿主把它编码成 JSON 字符串发给 WebSocket，收到的 JSON 解码回 Lua table。
 *
 * 支持类型：Map、List、String、Number、Boolean、null（嵌套任意深度）。
 */
object SimpleJson {

    fun encode(value: Any?): String = buildString { appendValue(value) }

    fun decode(json: String): Any? = Parser(json).parseValue()

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(if (value) "true" else "false")
            is Number -> append(value.toString())
            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, v) ->
                    if (!first) append(',')
                    first = false
                    appendQuoted(key.toString())
                    append(':')
                    appendValue(v)
                }
                append('}')
            }
            is List<*> -> {
                append('[')
                var first = true
                value.forEach { v ->
                    if (!first) append(',')
                    first = false
                    appendValue(v)
                }
                append(']')
            }
            else -> appendQuoted(value.toString())
        }
    }

    private fun StringBuilder.appendQuoted(s: String) {
        append('"')
        s.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') {
                    append("\\u%04x".format(c.code))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private class Parser(private val s: String) {
        private var pos = 0

        fun parseValue(): Any? {
            skipWhitespace()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(): LinkedHashMap<String, Any?> {
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                map[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> throw IllegalArgumentException("JSON 对象缺少 , 或 } at $pos")
                }
            }
            return map
        }

        private fun parseArray(): MutableList<Any?> {
            expect('[')
            val list = mutableListOf<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return list }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> throw IllegalArgumentException("JSON 数组缺少 , 或 ] at $pos")
                }
            }
            return list
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < s.length) {
                val c = s[pos]
                when (c) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("非法转义 \\$e")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
            throw IllegalArgumentException("JSON 字符串未闭合")
        }

        private fun parseBoolean(): Boolean {
            if (s.startsWith("true", pos)) { pos += 4; return true }
            if (s.startsWith("false", pos)) { pos += 5; return false }
            throw IllegalArgumentException("非法布尔值 at $pos")
        }

        private fun parseNull(): Any? {
            if (s.startsWith("null", pos)) { pos += 4; return null }
            throw IllegalArgumentException("非法 null at $pos")
        }

        private fun parseNumber(): Any {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".-+eE")) pos++
            if (pos == start) throw IllegalArgumentException("非法数字 at $start")
            val token = s.substring(start, pos)
            return if (token.contains('.') || token.contains('e') || token.contains('E')) {
                token.toDouble()
            } else {
                token.toLong()
            }
        }

        private fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        private fun peek(): Char = if (pos < s.length) s[pos] else throw IllegalArgumentException("JSON 提前结束")

        private fun expect(c: Char) {
            if (pos >= s.length || s[pos] != c) {
                throw IllegalArgumentException("期望 '$c' 但遇到 '${if (pos < s.length) s[pos] else "EOF"}' at $pos")
            }
            pos++
        }
    }
}
