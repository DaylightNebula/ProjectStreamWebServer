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

        // load basic contexts
        httpserver.createContext("/", GeneralFileHandler(File(Config.getFile("ENGINE_DIR"), "/index.html")))
        httpserver.createContext("/main.js", MainJSHandler())

        // load everything
        loadFiles()

        // start http server
        httpserver.executor = null
        httpserver.start()
        println("HTTP server started!")
    }

    fun loadFiles() {
        if (contexts.isNotEmpty()) println("Removing old contexts")
        // remove old contexts
        contexts.forEach {
            httpserver.removeContext(it)
        }
        contexts.clear()

        // load contexts
        println("Loading contexts")
        loadAssetsContexts()

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