package daylightnebula.projectstream.webserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.Integer.max
import java.net.InetSocketAddress
import java.nio.file.StandardWatchEventKinds.*
import java.nio.file.WatchService
import javax.imageio.ImageIO

object WebServer {

    lateinit var httpserver: HttpServer
    lateinit var watchService: WatchService

    val contexts = mutableListOf<String>()

    fun start(port: Int) {
        /*val compiled = WSJSCompiler.runComplexCompile("class Test {\n" +
                "    hello() {\n" +
                "        console.log(\"Hello World!\");\n" +
                "    }\n" +
                "}\n" +
                "let test = new Test();\n" +
                "test.hello();\n")
        println("COMPILED $compiled")
        return*/

        // load webconfig.txt
        Config.loadConfig()

        // create http server
        httpserver = HttpServer.create(InetSocketAddress(port), 0)

        // load everything
        loadFiles()

        // start http server
        httpserver.executor = null
        httpserver.start()
        println("HTTP server started!")

        // create watch service
        val path = File(System.getProperty("user.dir")).toPath()
        watchService = path.fileSystem.newWatchService()

        // register file watches
        registerFileWatch(Config.getFile("ENGINE_DIR"))
        registerFileWatch(Config.getFile("ASSETS_DIR"))
        registerFileWatch(Config.getFile("SCRIPTS_DIR"))

        while(true) { checkFiles() }
    }

    fun registerFileWatch(file: File) {
        val path = file.toPath()
        path.register(watchService, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)

        // loop through all files, and for each directory, call this function recursively
        file.listFiles()?.forEach {
            if (it.isDirectory)
                registerFileWatch(it)
        }
    }

    fun checkFiles() {
        // get water service key
        val key = watchService.take()
        var filesDirty = false

        // get events from the watch service key
        for (event in key.pollEvents()) {
            // get event kind
            val kind = event.kind()

            // if kind is overflow, skip the rest of this iteration
            if (kind == OVERFLOW) continue

            // switch for kind, for all accepted criteria set files dirty to true, if unknown criteria, print a warning statement
            when (kind) {
                ENTRY_CREATE -> { filesDirty = true }
                ENTRY_MODIFY -> { filesDirty = true }
                ENTRY_DELETE -> { filesDirty = true }
                else -> {
                    println("WARN: Unknown kind $kind")
                }
            }

            // reset the key, I don't know why we need this, but we do otherwise everything hangs
            key.reset()
        }

        // if files are dirty, reload the files
        if (filesDirty) loadFiles()
    }

    fun loadFiles() {
        if (contexts.isNotEmpty()) {
            println("Removing old contexts")
            httpserver.removeContext("/")
            httpserver.removeContext("/main.js")
        }
        // remove old contexts
        contexts.forEach {
            httpserver.removeContext(it)
        }
        contexts.clear()

        // load basic contexts
        httpserver.createContext("/", GeneralFileHandler(File(Config.getFile("ENGINE_DIR"), "/index.html")))
        httpserver.createContext("/main.js", MainJSHandler())

        // load contexts
        println("Loading contexts")
        loadAssetsContexts()
        loadScriptsContexts()

        println("Finished loading files")
    }

    fun loadAssetsContexts() {
        val rootDir = Config.getFile("ASSETS_DIR")
        loadAssetContext(rootDir, "")
        println("Loaded assets contexts")
    }

    fun loadAssetContext(file: File, subpath: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                loadAssetContext(it, "$subpath/${it.name}")
            }
        } else if (!contexts.contains(subpath)) {
            println("Adding http context for file at path " + subpath)
            contexts.add(subpath)

            val fileHandler = when (subpath.split(".").last()) {
                "bbmodel" -> BBModelHandler(file)
                "scene" -> SceneHandler(file)
                else -> GeneralFileHandler(file)
            }
            httpserver.createContext(subpath, fileHandler)
        }
    }

    fun loadScriptsContexts() {
        val rootDir = Config.getFile("SCRIPTS_DIR")
        loadScriptContext(rootDir, "")
        println("Loaded assets contexts")
    }

    fun loadScriptContext(file: File, subpath: String) {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                loadScriptContext(it, "$subpath/${it.name}")
            }
        } else if (!contexts.contains(subpath)) {
            println("Adding http context for file at path " + subpath)
            contexts.add(subpath)

            val fileHandler = SubJSHandler(subpath, file)
            httpserver.createContext(subpath, fileHandler)
        }
    }
}
class GeneralFileHandler(val file: File): HttpHandler {
    override fun handle(exchange: HttpExchange) {
        // get file as bytes
        val bytes = file.readBytes()

        // setup exchange
        exchange.sendResponseHeaders(200, bytes.size.toLong())

        // write response bytes to response
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}
class MainJSHandler(): HttpHandler {

    var js = WSJSCompiler.buildMasterJSFile(Config.getFile("ENGINE_DIR"))

    override fun handle(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, js.length.toLong())
        val os = exchange.responseBody
        os.write(js.encodeToByteArray())
        os.close()
    }
}
class SubJSHandler(subpath: String, file: File): HttpHandler {

    val text: String

    init {
        text = file.readText()
            .replace("function start(", "function sub${subpath.replace("/", "_").replace(".", "_")}_start(")
            .replace("function update(", "function sub${subpath.replace("/", "_").replace(".", "_")}_update(")
    }

    override fun handle(exchange: HttpExchange) {
        // get file as bytes
        val bytes = text.toByteArray()

        // setup exchange
        exchange.sendResponseHeaders(200, bytes.size.toLong())

        // write response bytes to response
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}