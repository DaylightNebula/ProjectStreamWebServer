package daylightnebula.projectstream.webserver

import java.io.File
import java.lang.IndexOutOfBoundsException
import java.lang.NullPointerException
import java.lang.StringBuilder

object WSJSCompiler {

    val globalFunctionNames = mutableListOf<String>()

    fun buildMasterJSFile(srcFolder: File): String {
        val builder = StringBuilder()
        compileWSJS(srcFolder, builder, true)
        builder.append("main();")
        var text =
            if (Config.getBoolean("COMPRESS_BLANKSPACE", true))
                builder.toString().replace("\\s+".toRegex(), " ").replace("\t", "")
            else
                builder.toString()

        if (Config.getBoolean("COMPRESS_UNUSED", true)) {
            // loop through each recorded global function
            globalFunctionNames.forEach { name ->
                if (name == "main") return@forEach
                val matches = countMatches(text, name)

                if (matches < 2) {
                    try {
                        val startIndex = text.indexOf("function $name(")
                        var endIndex = startIndex + 1
                        var bracketCounter = 0
                        var stop = false
                        while (!stop) {
                            val char = text[endIndex]
                            if (char == '{') bracketCounter++
                            if (char == '}') {
                                bracketCounter--
                                if (bracketCounter < 1)
                                    stop = true
                            }

                            endIndex++
                        }
                        text = text.removeRange(startIndex, endIndex)
                    } catch (ex: IndexOutOfBoundsException) {
                        println("Failed to remove global function $name")
                    }
                }
            }
        }

        return text
    }

    fun countMatches(string: String, pattern: String): Int {
        return string.split(pattern)
            .dropLastWhile { it.isEmpty() }
            .toTypedArray().size - 1
    }

    fun compileWSJS(file: File, builder: StringBuilder, recursiveCompileChildren: Boolean = false) {
        // if this file is a directory, cancel
        if (file.isDirectory) {
            // if recursive is called for, call this function recursively on the children files
            if (recursiveCompileChildren)
                file.listFiles()?.forEach {
                    compileWSJS(it, builder, recursiveCompileChildren)
                }

            return
        }

        // make sure this file is js file or a wsjs file
        if (!file.extension.endsWith("js"))
            return

        builder.append(runComplexCompile(file.readText(), recursiveCompileChildren))
    }

    fun tokenize(a: String, inEngine: Boolean): List<String> {
        var input = a

        // remove imports and exports if in engine
        if (inEngine) {
            while (input.contains("import")) {
                val startIndex = input.indexOf("import")
                val endIndex = input.indexOf(";", startIndex)
                input = input.removeRange(startIndex, endIndex + 1)
            }

            while (input.contains("export")) {
                val startIndex = input.indexOf("export")
                val endIndex = input.indexOf(" ", startIndex)
                input = input.removeRange(startIndex, endIndex + 1)
            }
        }

        if (Config.getBoolean("COMPRESS_COMMENTS", true)) {
            // remove single line comments
            while (input.contains("//")) {
                val startIndex = input.indexOf("//")
                val endIndex = input.indexOf("\n", startIndex) + 1
                input = input.removeRange(startIndex, endIndex)
            }

            // remove multiline comments
            while (input.contains("/*")) {
                val startIndex = input.indexOf("/*")
                val endIndex = input.indexOf("*/", startIndex) + 2
                input = input.removeRange(startIndex, endIndex)
            }
        }

        val output = mutableListOf<String>()
        val text = input.replace("\n", "")
        var bracketOffset = 0
        var inQuotation = false
        var inGrave = false
        var inApostrophe = false
        var tracker = 0

        var i = 0
        while (i < text.length) {
            val char = text[i]

            // check bracket offsets
            if (char == '{') bracketOffset++
            else if (char == '}') bracketOffset--

            // track string notations
            if (char == '\"') inQuotation = !inQuotation
            if (char == '\'') inApostrophe = !inApostrophe
            if (char == '`') inGrave = !inGrave

            // if end character is received, and all allowers have been reset, split
            if ((char == '}' || char == ';') && bracketOffset < 1 && !inQuotation && !inGrave && !inApostrophe) {

                output.add(text.substring(tracker, i + 1))
                tracker = i + 1
            }

            i++
        }

        return output
    }

    fun compressToken(input: String, inEngine: Boolean): String {
        var text = input

        if (text == "\n" || text == "\r\n") return ""
        if (inEngine && text.startsWith("import")) return ""
        if (inEngine && text.startsWith("export")) text = text.replace("export ", "")

        return text
    }

    fun convertToNodes(tokens: List<String>, rootType: NodeType?, inEngine: Boolean): List<Node> {
        val output = mutableListOf<Node>()

        var i = 0
        while (i < tokens.size) {
            val token = compressToken(tokens[i], true)

            if (token.length < 1) {
                i++
                continue
            }

            val declaration = token.split(" ", limit = 2).first()
            var type = NodeType.values().firstOrNull { type -> type.declarations.contains(declaration) }

            // if our root is a class
            if (rootType == NodeType.CLASS && token.contains("{")) type = NodeType.FUNCTION

            // default to operation type
            if (type == null) type = NodeType.OPERATION

            // build node from type and token
            val node = when (type) {
                NodeType.OPERATION -> {
                    OperationNode(token)
                }
                NodeType.VARIABLE -> {
                    VariableNode(token)
                }
                NodeType.MODIFICATION -> {
                    ModificationNode(token)
                }
                NodeType.CLASS -> {
                    val startIndex = token.indexOfFirst { it == '{' }
                    val endIndex = token.indexOfLast { it == '}' }
                    val start = token.substring(0, startIndex + 1)
                    val mid = token.substring(startIndex + 1, endIndex)
                    val end = token.substring(endIndex, token.length)
                    ClassNode(start, end, convertToNodes(tokenize(mid, inEngine), type, inEngine))
                }
                NodeType.FUNCTION -> {
                    val startIndex = token.indexOfFirst { it == '{' }
                    val endIndex = token.indexOfLast { it == '}' }
                    val start = token.substring(0, startIndex + 1)
                    val mid = token.substring(startIndex + 1, endIndex)
                    val end = token.substring(endIndex, token.length)

                    if (rootType == null) {
                        val fName = start.substring(start.indexOf(" ", start.indexOf("function")), start.indexOf("(")).replace(" ", "")
                        globalFunctionNames.add(fName)
                    }

                    FunctionNode(start, end, convertToNodes(tokenize(mid, inEngine), type, inEngine))
                }
                NodeType.SWITCH -> {
                    val startIndex = token.indexOfFirst { it == '{' }
                    val endIndex = token.indexOfLast { it == '}' }
                    val start = token.substring(0, startIndex + 1)
                    val mid = token.substring(startIndex + 1, endIndex)
                    val end = token.substring(endIndex, token.length)
                    SwitchNode(start, end, convertToNodes(tokenize(mid, inEngine), type, inEngine))
                }
                else -> {
                    println("ERROR: No process made $type, declaration $declaration, defaulting to operation, full text is $token")
                    OperationNode(token)
                }
            }

            output.add(node)

            i++
        }

        return output
    }

    fun runComplexCompile(text: String, inEngine: Boolean): String {
        val tokens = tokenize(text, inEngine)
        val nodes = convertToNodes(tokens, null, inEngine)
        val builder = StringBuilder()

        var i = 0
        while (i < nodes.size) {
            val node = nodes[i]
            builder.append(node.draw())

            i++
        }

        return builder.toString()
    }
}
enum class NodeType(val declarations: Array<String>) {
    CLASS(arrayOf("class")),
    FUNCTION(arrayOf("function", "async")),
    SWITCH(arrayOf("switch")),
    VARIABLE(arrayOf("let", "const", "var")),
    MODIFICATION(arrayOf()),
    OPERATION(arrayOf())
}
abstract class Node(val type: NodeType) {
    abstract fun draw(): String
}
abstract class ContainerNode(type: NodeType): Node(type)
class OperationNode(val content: String): Node(NodeType.OPERATION) {
    override fun draw(): String {
        return content
    }
}
class VariableNode(val content: String): Node(NodeType.VARIABLE) {
    override fun draw(): String {
        return content
    }
}
class ModificationNode(val content: String): Node(NodeType.MODIFICATION) {
    override fun draw(): String {
        return content
    }
}
class ClassNode(val firstLine: String, val lastLine: String, val content: List<Node>): ContainerNode(NodeType.CLASS) {
    override fun draw(): String {
        val builder = StringBuilder(firstLine)

        var i = 0
        while (i < content.size) {
            builder.append(content[i].draw())

            i++
        }

        builder.append(lastLine)
        return builder.toString()
    }
}
class FunctionNode(val firstLine: String, val lastLine: String, val content: List<Node>): ContainerNode(NodeType.FUNCTION) {
    override fun draw(): String {
        val builder = StringBuilder(firstLine)

        var i = 0
        while (i < content.size) {
            builder.append(content[i].draw())

            i++
        }

        builder.append(lastLine)
        return builder.toString()
    }
}
class SwitchNode(val firstLine: String, val lastLine: String, val content: List<Node>): ContainerNode(NodeType.SWITCH) {
    override fun draw(): String {
        val builder = StringBuilder(firstLine)

        var i = 0
        while (i < content.size) {
            builder.append(content[i].draw())

            i++
        }

        builder.append(lastLine)
        return builder.toString()
    }
}