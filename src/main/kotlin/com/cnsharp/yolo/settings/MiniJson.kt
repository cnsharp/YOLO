package com.cnsharp.yolo.settings

/**
 * Minimal, dependency-free JSON parser + pretty serializer used by [AgentConfigInjector] to merge provider
 * entries into existing agent config files without clobbering user-added entries. Supports the JSON subset
 * we need: objects, arrays, strings, numbers, booleans, null. Throws on malformed input (callers catch).
 */
object MiniJson {

    fun parse(text: String): Any? = Parser(text).parseValue()

    fun stringify(value: Any?, indent: String = "  "): String {
        val sb = StringBuilder()
        writeValue(sb, value, indent, 0)
        return sb.toString()
    }

    // ---- parser ----

    private class Parser(private val s: String) {
        private var i = 0

        fun parseValue(): Any? {
            skipWs()
            return when (peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> { expect("null"); null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): MutableMap<String, Any?> {
            expect("{")
            val map = linkedMapOf<String, Any?>()
            skipWs()
            if (peek() == '}') { i++; return map }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(":")
                val v = parseValue()
                map[key] = v
                skipWs()
                val c = next()
                if (c == '}') break
                require(c == ',') { "expected , or } at $i" }
            }
            return map
        }

        private fun parseArray(): MutableList<Any?> {
            expect("[")
            val list = mutableListOf<Any?>()
            skipWs()
            if (peek() == ']') { i++; return list }
            while (true) {
                val v = parseValue()
                list.add(v)
                skipWs()
                val c = next()
                if (c == ']') break
                require(c == ',') { "expected , or ] at $i" }
            }
            return list
        }

        private fun parseString(): String {
            expect("\"")
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        val e = s[i++]
                        sb.append(when (e) {
                            '"' -> '"'; '\\' -> '\\'; '/' -> '/'
                            'b' -> '\b'; 'f' -> '\u000C'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                            'u' -> {
                                val hex = s.substring(i, i + 4); i += 4
                                hex.toInt(16).toChar()
                            }
                            else -> error("bad escape \\$e at $i")
                        })
                    }
                    else -> sb.append(c)
                }
            }
            error("unterminated string")
        }

        private fun parseNumber(): Number {
            val start = i
            while (i < s.length && s[i] in '0'..'9' || s[i] == '-' || s[i] == '+' || s[i] == '.' || s[i] == 'e' || s[i] == 'E') i++
            val str = s.substring(start, i)
            require(str.isNotBlank()) { "expected number at $start" }
            return if (str.contains('.') || str.contains('e') || str.contains('E')) str.toDouble() else str.toLong()
        }

        private fun parseBool(): Boolean {
            if (s.startsWith("true", i)) { i += 4; return true }
            if (s.startsWith("false", i)) { i += 5; return false }
            error("expected bool at $i")
        }

        private fun skipWs() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        private fun next(): Char = s[i++]
        private fun expect(token: String) {
            require(s.startsWith(token, i)) { "expected '$token' at $i" }
            i += token.length
        }
    }

    // ---- serializer ----

    private fun writeValue(sb: StringBuilder, value: Any?, indent: String, depth: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(sb, value)
            is Boolean, is Number -> sb.append(value.toString())
            is Map<*, *> -> writeObject(sb, value, indent, depth)
            is List<*> -> writeArray(sb, value, indent, depth)
            else -> writeString(sb, value.toString())
        }
    }

    private fun writeObject(sb: StringBuilder, map: Map<*, *>, indent: String, depth: Int) {
        if (map.isEmpty()) { sb.append("{}"); return }
        sb.append("{\n")
        val pad = indent.repeat(depth + 1)
        map.entries.forEachIndexed { idx, (k, v) ->
            sb.append(pad).append('"').append(k.toString().replace("\"", "\\\"")).append("\": ")
            writeValue(sb, v, indent, depth + 1)
            sb.append(if (idx == map.size - 1) "\n" else ",\n")
        }
        sb.append(indent.repeat(depth)).append("}")
    }

    private fun writeArray(sb: StringBuilder, list: List<*>, indent: String, depth: Int) {
        if (list.isEmpty()) { sb.append("[]"); return }
        sb.append("[\n")
        val pad = indent.repeat(depth + 1)
        list.forEachIndexed { idx, v ->
            sb.append(pad)
            writeValue(sb, v, indent, depth + 1)
            sb.append(if (idx == list.size - 1) "\n" else ",\n")
        }
        sb.append(indent.repeat(depth)).append("]")
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        value.forEach { c ->
            sb.append(when (c) {
                '"' -> "\\\""; '\\' -> "\\\\"; '\n' -> "\\n"; '\r' -> "\\r"; '\t' -> "\\t"; '\b' -> "\\b"
                else -> c
            })
        }
        sb.append('"')
    }
}
