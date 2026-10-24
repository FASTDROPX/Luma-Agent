package dev.luma.agent.ui

import dev.luma.agent.core.tr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/** Token colours for code; one set for the light theme and one for the dark. */
data class CodePalette(val keyword: Color, val string: Color, val number: Color, val comment: Color,
    val type: Color, val function: Color, val tag: Color, val plain: Color) {
    companion object {
        fun of(dark: Boolean, plain: Color) = if (dark)
            CodePalette(Color(0xFFC9A4FF), Color(0xFF9EDDA8), Color(0xFFF5B98C), Color(0xFF7D879E), Color(0xFF8DC9FF), Color(0xFFFFD58C), Color(0xFFFF9EB4), plain)
        else CodePalette(Color(0xFF7740CC), Color(0xFF2B7A4B), Color(0xFFB0531A), Color(0xFF7A8296), Color(0xFF1F6AAE), Color(0xFF8F6200), Color(0xFFC0185A), plain)
    }
}

/** What a fenced block's language is called, which file extension it saves with, and how to colour it. */
data class CodeLanguage(val label: String, val extension: String, val family: Family) {
    enum class Family { C, PYTHON, SHELL, SQL, MARKUP, CSS, JSON, YAML, PLAIN }
}

object SyntaxHighlighter {
    private val languages: Map<String, CodeLanguage> = buildMap {
        fun add(label: String, ext: String, family: CodeLanguage.Family, vararg names: String) { for (n in names) put(n, CodeLanguage(label, ext, family)) }
        val c = CodeLanguage.Family.C
        add("Kotlin", "kt", c, "kotlin", "kt", "kts")
        add("Java", "java", c, "java")
        add("JavaScript", "js", c, "javascript", "js", "jsx", "mjs", "node")
        add("TypeScript", "ts", c, "typescript", "ts", "tsx")
        add("C", "c", c, "c", "h")
        add("C++", "cpp", c, "cpp", "c++", "cc", "cxx", "hpp")
        add("C#", "cs", c, "csharp", "cs", "c#")
        add("Go", "go", c, "go", "golang")
        add("Rust", "rs", c, "rust", "rs")
        add("Swift", "swift", c, "swift")
        add("Dart", "dart", c, "dart")
        add("PHP", "php", c, "php")
        add("GLSL", "glsl", c, "glsl", "hlsl", "frag", "vert", "shader")
        add("Groovy", "gradle", c, "groovy", "gradle")
        add("Python", "py", CodeLanguage.Family.PYTHON, "python", "py", "python3")
        add("Ruby", "rb", CodeLanguage.Family.PYTHON, "ruby", "rb")
        add("Bash", "sh", CodeLanguage.Family.SHELL, "bash", "sh", "shell", "zsh", "console", "shellscript")
        add("PowerShell", "ps1", CodeLanguage.Family.SHELL, "powershell", "ps1", "pwsh")
        add("SQL", "sql", CodeLanguage.Family.SQL, "sql", "mysql", "postgresql", "sqlite", "psql")
        add("HTML", "html", CodeLanguage.Family.MARKUP, "html", "htm", "xhtml", "vue", "svelte")
        add("XML", "xml", CodeLanguage.Family.MARKUP, "xml", "svg", "plist")
        add("CSS", "css", CodeLanguage.Family.CSS, "css", "scss", "sass", "less")
        add("JSON", "json", CodeLanguage.Family.JSON, "json", "jsonc", "json5")
        add("YAML", "yaml", CodeLanguage.Family.YAML, "yaml", "yml")
        add("TOML", "toml", CodeLanguage.Family.YAML, "toml", "ini", "properties", "conf")
        add("Markdown", "md", CodeLanguage.Family.PLAIN, "markdown", "md")
        add(tr("Текст"), "txt", CodeLanguage.Family.PLAIN, "text", "txt", "plaintext", "")
    }

    /** Known languages by their fence name; anything else keeps its own name and saves as .txt. */
    fun language(fence: String): CodeLanguage {
        val name = fence.trim().lowercase()
        return languages[name] ?: CodeLanguage(fence.trim().take(20).ifBlank { tr("Текст") }, name.filter { it.isLetterOrDigit() }.take(8).ifBlank { "txt" }, CodeLanguage.Family.PLAIN)
    }

    private val cKeywords = ("abstract actor as assert async await break case catch class companion const constructor continue crossinline data def default defer delegate " +
        "do dynamic else enum export extends extension external final finally for fun func function get go goto guard if impl implements import in infix init inline " +
        "inner instanceof interface internal is lateinit let loop match mod module mut namespace native new noinline object open operator out override package " +
        "private protected pub public readonly reified return sealed select set static struct super suspend switch tailrec this throw throws trait try typealias " +
        "typedef typeof union unsafe use using val var vararg virtual void volatile when where while with yield uniform varying attribute layout precision " +
        "highp mediump lowp true false null nil none undefined self Self").split(' ').toSet()
    private val cTypes = ("int long short byte char float double boolean bool string String Int Long Short Byte Char Float Double Boolean Unit Any Nothing " +
        "uint u8 u16 u32 u64 i8 i16 i32 i64 f32 f64 usize isize str vec2 vec3 vec4 ivec2 ivec3 ivec4 mat2 mat3 mat4 sampler2D auto size_t number object symbol bigint").split(' ').toSet()
    private val pyKeywords = ("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal " +
        "not or pass raise return try while with yield True False None self match case begin end module require then unless until do puts").split(' ').toSet()
    private val shKeywords = ("if then else elif fi for in do done while until case esac function return export local readonly echo cd exit set unset source " +
        "sudo param begin process end foreach switch try catch").split(' ').toSet()
    private val sqlKeywords = ("select from where and or not insert into values update set delete create table drop alter add index primary key foreign references " +
        "join left right inner outer full on group by order having limit offset as distinct union all null is in like between exists case when then else end " +
        "count sum avg min max default unique check view begin commit rollback transaction integer text varchar real blob boolean returning with").split(' ').toSet()

    private val number = Regex("""^(0[xX][0-9a-fA-F_]+|\d[\d_]*(\.\d+)?([eE][+-]?\d+)?[fFLuU]?)""")
    // Android (ICU) regexes need } and ] escaped even where Java accepts them bare.
    private val markupTag = Regex("""^</?[A-Za-z][\w:.\-]*""")
    private val markupName = Regex("""^[\w:.\-]+""")
    private val entity = Regex("""^&#?\w+;""")
    private val shellVariable = Regex("""^\$(\{[^\}]*\}|\(|[A-Za-z_][A-Za-z0-9_]*|[0-9@#?*!$\-])""")
    private val word = Regex("""^[A-Za-z_$][A-Za-z0-9_$]*""")

    /** Never fails: if colouring throws, the code is shown plain. */
    fun highlight(code: String, language: CodeLanguage, p: CodePalette): AnnotatedString =
        runCatching { colour(code, language, p) }.getOrElse { AnnotatedString(code) }

    private fun colour(code: String, language: CodeLanguage, p: CodePalette): AnnotatedString = buildAnnotatedString {
        // Very long blocks are shown plain: colouring them would cost more than it helps.
        if (code.length > 60_000 || language.family == CodeLanguage.Family.PLAIN) { append(code); return@buildAnnotatedString }
        val family = language.family
        var i = 0
        fun put(text: String, color: Color, italic: Boolean = false, bold: Boolean = false) {
            pushStyle(SpanStyle(color = color, fontStyle = if (italic) FontStyle.Italic else null, fontWeight = if (bold) FontWeight.Medium else null)); append(text); pop()
        }
        fun startsWith(s: String) = code.startsWith(s, i)
        fun nextNonSpace(from: Int): Char? { var j = from; while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++; return code.getOrNull(j) }
        fun window(from: Int, size: Int = 120) = code.substring(from.coerceAtMost(code.length), (from + size).coerceAtMost(code.length))
        fun untilLineEnd(): String { val end = code.indexOf('\n', i).let { if (it < 0) code.length else it }; return code.substring(i, end) }
        fun until(close: String, from: Int): String { val end = code.indexOf(close, from).let { if (it < 0) code.length else it + close.length }; return code.substring(i, end) }
        fun quoted(q: Char): String {
            var j = i + 1
            while (j < code.length && code[j] != q && !(code[j] == '\n' && q != '`')) { if (code[j] == '\\') j++; j++ }
            return code.substring(i, (j + 1).coerceAtMost(code.length))
        }
        var afterTagOpen = false
        while (i < code.length) {
            val ch = code[i]
            var token: String
            when (family) {
                CodeLanguage.Family.MARKUP -> {
                    when {
                        startsWith("<!--") -> { token = until("-->", i + 4); put(token, p.comment, italic = true) }
                        ch == '<' -> {
                            val m = markupTag.find(code.substring(i, (i + 80).coerceAtMost(code.length)))
                            token = m?.value ?: "<"; put(token, if (m != null) p.tag else p.plain); afterTagOpen = m != null
                        }
                        ch == '>' || startsWith("/>") -> { token = if (ch == '>') ">" else "/>"; put(token, p.tag); afterTagOpen = false }
                        afterTagOpen && (ch == '"' || ch == '\'') -> { token = quoted(ch); put(token, p.string) }
                        afterTagOpen && word.containsMatchIn(code.substring(i, (i + 1).coerceAtMost(code.length))) -> {
                            token = markupName.find(code.substring(i, (i + 80).coerceAtMost(code.length)))!!.value; put(token, p.function)
                        }
                        ch == '&' -> { token = entity.find(code.substring(i, (i + 12).coerceAtMost(code.length)))?.value ?: "&"; put(token, p.number) }
                        else -> { token = ch.toString(); append(token) }
                    }
                    i += token.length; continue
                }
                else -> {}
            }
            val lineComment = when (family) {
                CodeLanguage.Family.C, CodeLanguage.Family.JSON, CodeLanguage.Family.CSS -> startsWith("//") && family != CodeLanguage.Family.CSS
                CodeLanguage.Family.PYTHON, CodeLanguage.Family.SHELL, CodeLanguage.Family.YAML -> ch == '#' || (family == CodeLanguage.Family.YAML && ch == ';' && (i == 0 || code[i - 1] == '\n'))
                CodeLanguage.Family.SQL -> startsWith("--")
                else -> false
            }
            when {
                lineComment -> { token = untilLineEnd(); put(token, p.comment, italic = true) }
                startsWith("/*") && family in setOf(CodeLanguage.Family.C, CodeLanguage.Family.CSS, CodeLanguage.Family.SQL, CodeLanguage.Family.JSON) -> { token = until("*/", i + 2); put(token, p.comment, italic = true) }
                family == CodeLanguage.Family.PYTHON && (startsWith("\"\"\"") || startsWith("'''")) -> { token = until(code.substring(i, i + 3), i + 3); put(token, p.string) }
                family == CodeLanguage.Family.C && startsWith("\"\"\"") -> { token = until("\"\"\"", i + 3); put(token, p.string) }
                ch == '"' || ch == '\'' || (ch == '`' && family in setOf(CodeLanguage.Family.C, CodeLanguage.Family.SHELL, CodeLanguage.Family.SQL)) -> {
                    token = quoted(ch)
                    // A JSON key is a string followed by a colon.
                    val key = family == CodeLanguage.Family.JSON && nextNonSpace(i + token.length) == ':'
                    put(token, if (key) p.type else p.string)
                }
                ch == '@' && family in setOf(CodeLanguage.Family.C, CodeLanguage.Family.PYTHON, CodeLanguage.Family.CSS) && i + 1 < code.length && code[i + 1].isLetter() -> {
                    token = "@" + word.find(window(i + 1))!!.value; put(token, p.tag)
                }
                ch == '#' && family == CodeLanguage.Family.C && (i == 0 || code[i - 1] == '\n' || code.substring(code.lastIndexOf('\n', i - 1) + 1, i).isBlank()) -> {
                    token = untilLineEnd(); put(token, p.tag)
                }
                ch == '$' && family == CodeLanguage.Family.SHELL -> {
                    token = shellVariable.find(window(i))?.value ?: "$"; put(token, p.type)
                }
                ch.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_')) -> {
                    token = number.find(code.substring(i, (i + 40).coerceAtMost(code.length)))?.value ?: ch.toString(); put(token, p.number)
                }
                ch.isLetter() || ch == '_' || (ch == '$' && family == CodeLanguage.Family.C) -> {
                    token = word.find(code.substring(i, (i + 120).coerceAtMost(code.length)))?.value ?: ch.toString()
                    val next = nextNonSpace(i + token.length)
                    when (family) {
                        CodeLanguage.Family.C -> when {
                            token in cKeywords -> put(token, p.keyword, bold = true)
                            token in cTypes || (token[0].isUpperCase() && token.any { it.isLowerCase() }) -> put(token, p.type)
                            next == '(' -> put(token, p.function)
                            else -> append(token)
                        }
                        CodeLanguage.Family.PYTHON -> when {
                            token in pyKeywords -> put(token, p.keyword, bold = true)
                            next == '(' -> put(token, p.function)
                            token[0].isUpperCase() && token.any { it.isLowerCase() } -> put(token, p.type)
                            else -> append(token)
                        }
                        CodeLanguage.Family.SHELL -> when {
                            token.lowercase() in shKeywords -> put(token, p.keyword, bold = true)
                            (i == 0 || code.substring(code.lastIndexOf('\n', i - 1) + 1, i).isBlank()) -> put(token, p.function)
                            else -> append(token)
                        }
                        CodeLanguage.Family.SQL -> when {
                            token.lowercase() in sqlKeywords -> put(token, p.keyword, bold = true)
                            next == '(' -> put(token, p.function)
                            else -> append(token)
                        }
                        CodeLanguage.Family.JSON -> if (token == "true" || token == "false" || token == "null") put(token, p.keyword) else append(token)
                        CodeLanguage.Family.YAML -> when {
                            next == ':' || next == '=' -> put(token, p.type)
                            token in setOf("true", "false", "null", "yes", "no", "on", "off") -> put(token, p.keyword)
                            else -> append(token)
                        }
                        CodeLanguage.Family.CSS -> when {
                            next == ':' && window(i).substringBefore('\n').contains(';') -> put(token, p.type)
                            next == '(' -> put(token, p.function)
                            else -> append(token)
                        }
                        else -> append(token)
                    }
                }
                ch == '-' && family == CodeLanguage.Family.CSS && i + 1 < code.length && code[i + 1].isLetter() -> {
                    token = "-" + (word.find(window(i + 1))?.value ?: ""); append(token)
                }
                else -> { token = ch.toString(); append(token) }
            }
            i += token.length.coerceAtLeast(1)
        }
    }
}
