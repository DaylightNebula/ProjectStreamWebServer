package daylightnebula.projectstream.webserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
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
    var ATTEMPT_COMPRESS = true

    lateinit var clientDirectory: File
    lateinit var masterJSFile: String
    lateinit var httpserver: HttpServer
    lateinit var watchService: WatchService

    val configString = File(System.getProperty("user.dir"), "webconfig.txt").readText()
    val masterJSFileBuilder = StringBuilder()
    val contexts = mutableListOf<String>()

    fun start(port: Int) {
        // load webconfig.txt
        configString.split("\n").forEach { tempLine ->
            val line = tempLine.replace("\r", "")
            val tokens = line.split("=")
            println("Loading config option ${tokens[0]} = ${tokens[1]}")
            when (tokens[0]) {
                "DIR" -> clientDirectory = File(tokens[1])
                "COMPRESS" -> ATTEMPT_COMPRESS = tokens[1].equals("true", ignoreCase = true)
            }
        }

        // create http server
        httpserver = HttpServer.create(InetSocketAddress(port), 0)

        // load basic contexts
        httpserver.createContext("/", FileHandler("/index.html"))
        httpserver.createContext("/main.js", MainJSHandler())

        // load everything
        loadFiles()

        // start http server
        httpserver.executor = null
        httpserver.start()
        println("HTTP server started!")

        createFileReload()

        while (true) {
            checkFiles()
        }
    }

    fun createFileReload() {
        // create watch service
        val path = clientDirectory.toPath()
        watchService = path.fileSystem.newWatchService()

        // register client directory and all of its subdirectories with watch service events
        registerFileWatch(clientDirectory)
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
        if (contexts.isNotEmpty()) println("Removing old contexts")
        // remove old contexts
        contexts.forEach {
            httpserver.removeContext(it)
        }
        contexts.clear()

        // clear old main js file
        masterJSFileBuilder.clear()

        // load contexts
        println("Loading contexts")
        loadContexts(clientDirectory, "/")

        // finish masterJSFile, if compression is enabled, attempt to remove all tabs and end lines
        masterJSFile =
            if (ATTEMPT_COMPRESS)
                masterJSFileBuilder.toString()
                    .replace("\t", "")
                    .replace("\r", "\n")
                    .replace("\n", "")
            else
                masterJSFileBuilder.toString()

        // extra space compression
        if (ATTEMPT_COMPRESS) {
            masterJSFile = masterJSFile.replace("\\s+".toRegex(), " ")
        }

        println("Finished loading files")
    }

    fun loadContexts(file: File, path: String) {
        file.listFiles()?.forEach { subfile ->
            if (subfile.isDirectory)
                loadContexts(subfile, "$path${subfile.nameWithoutExtension}/")
            else if (subfile.name != "index.html") {
                val subpath = "$path${subfile.name}"

                if (subpath.endsWith("js")) {
                    // get text from js file and split it into lines
                    val text = File(WebServer.clientDirectory, subpath).readText().split("\n").toMutableList()

                    // loop through each line and modify it if necessary
                    text.replaceAll {
                        // remove comments if we are compressing
                        if (ATTEMPT_COMPRESS) {
                            if (it.contains("//")) return@replaceAll it.split("//")[0]
                            else if (it.startsWith("/**")) return@replaceAll ""
                            else if (it.startsWith(" *")) return@replaceAll ""
                            else if (it.startsWith(" */")) return@replaceAll ""
                        }

                        // get rid of unnecessary information as everything is compressed into one line of text
                        if (it.startsWith("import")) ""
                        else if (it.startsWith("export ")) it.removePrefix("export ")
                        else it
                    }

                    for (i in 0 until text.size)
                        if (text[i].isNotBlank()) {
                            masterJSFileBuilder.append(text[i])
                            masterJSFileBuilder.append("\n")
                        }
                    masterJSFileBuilder.append("\n")
                } else if (subpath.endsWith("obj")) {
                    httpserver.createContext(subpath, OBJHandler(subpath))
                    contexts.add(subpath)
                } else if (subpath.endsWith("png")) {
                    httpserver.createContext(subpath, PNGHandler(subpath))
                    contexts.add(subpath)
                } else {
                    httpserver.createContext(subpath, FileHandler(subpath))
                    contexts.add(subpath)
                }
            }
        }

        if (path == "/")
            masterJSFileBuilder.append("main();")
    }
}
class FileHandler(val subpath: String): HttpHandler {
    override fun handle(exchange: HttpExchange) {
        // get file as bytes
        val bytes = File(WebServer.clientDirectory, subpath).readBytes()

        // setup exchange
        exchange.sendResponseHeaders(200, bytes.size.toLong())

        // write response bytes to response
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}
class MainJSHandler(): HttpHandler {
    override fun handle(exchange: HttpExchange) {
        val js = WebServer.masterJSFile.toString()
        exchange.sendResponseHeaders(200, js.length.toLong())
        val os = exchange.responseBody
        os.write(js.encodeToByteArray())
        os.close()
    }
}
class PNGHandler(val subpath: String): HttpHandler {
    val maxLengthSize = 256
    val bytes: ByteArray
    init {
        // get source image
        val file = File(WebServer.clientDirectory, subpath)
        val inputImage = ImageIO.read(file)

        // get output image
        if ((inputImage.width > maxLengthSize || inputImage.height > maxLengthSize) && inputImage.type != 0) {
            println("PNGHandler: Scaled $subpath with type ${inputImage.type}")
            // get new width and height
            val scaleAmount = maxLengthSize.toDouble() / max(inputImage.width, inputImage.height).toDouble()
            val outWidth = (inputImage.width * scaleAmount).toInt()
            val outHeight = (inputImage.height * scaleAmount).toInt()

            // create output image
            val out = BufferedImage(outWidth, outHeight, inputImage.type)
            val graphics = out.createGraphics()
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.drawImage(inputImage, 0, 0, outWidth, outHeight, null)
            graphics.dispose()

            // write out image to byte array
            val baos = ByteArrayOutputStream()
            ImageIO.write(out, "png", baos)
            bytes = baos.toByteArray()
        } else {
            println("PNGHandler: Left $subpath unmodified")
            bytes = file.readBytes()
        }
    }

    override fun handle(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}
class OBJHandler(val subpath: String): HttpHandler {

    val bytes: ByteArray
    init {
        // break up object file
        val lines = File(WebServer.clientDirectory, subpath).readText().split("\n")

        // create arrays for the object
        val vertices = mutableListOf<Byte>()
        val normals = mutableListOf<Byte>()
        //val indices = mutableListOf<Byte>()
        val texCoords = mutableListOf<Byte>()

        var faceStringCounter = 0
        val faceStrings = hashMapOf<String, Int>()

        val faceIndices = mutableListOf<Byte>()

        // loop through the lines and add them to the vertices array
        lines.forEach {
            val tokens = it.split(" ")

            // deserialize line
            when (tokens[0]) {
                "v" -> {
                    vertices.addAll(ByteUtils.convertIntToBytes(tokens[1].toFloat().toBits()).toTypedArray())
                    vertices.addAll(ByteUtils.convertIntToBytes(tokens[2].toFloat().toBits()).toTypedArray())
                    vertices.addAll(ByteUtils.convertIntToBytes(tokens[3].toFloat().toBits()).toTypedArray())
                }
                "vn" -> {
                    normals.add((tokens[1].toFloat() * 127f).toInt().toByte())
                    normals.add((tokens[2].toFloat() * 127f).toInt().toByte())
                    normals.add((tokens[3].toFloat() * 127f).toInt().toByte())
                    //normals.addAll(ByteUtils.convertShortToBytes((tokens[1].toFloat() * 32767f).toInt().toShort()).toTypedArray())
                    //normals.addAll(ByteUtils.convertShortToBytes((tokens[2].toFloat() * 32767f).toInt().toShort()).toTypedArray())
                    //normals.addAll(ByteUtils.convertShortToBytes((tokens[3].toFloat() * 32767f).toInt().toShort()).toTypedArray())
                }
                "vt" -> {
                    texCoords.add((tokens[1].toFloat() * 127f).toInt().toByte())
                    texCoords.add((tokens[2].toFloat() * 127f).toInt().toByte())

                    //texCoords.addAll(ByteUtils.convertShortToBytes((tokens[1].toFloat() * 32767f).toInt().toShort()).toTypedArray())
                    //texCoords.addAll(ByteUtils.convertShortToBytes((tokens[2].toFloat() * 32767f).toInt().toShort()).toTypedArray())
                }
                "f" -> {
                    if (tokens.size != 4) println("Face size ${tokens.size}")
                    tokens.forEachIndexed { index, face ->
                        if (index == 0) return@forEachIndexed

                        // try to get a face index for the given face
                        var faceIndex = faceStrings[face]

                        // if no face index was found, create a new one
                        if (faceIndex == null) {
                            faceIndex = faceStringCounter
                            faceStringCounter++
                            faceStrings[face] = faceIndex
                        }

                        // save index
                        faceIndices.addAll(ByteUtils.convertShortToBytes(faceIndex.toShort()).toTypedArray())

                        /*val faceElements = face.replace("\r", "").split("/")
                        indices.addAll(ByteUtils.convertShortToBytes(faceElements[0].toShort()).toTypedArray())
                        indices.addAll(ByteUtils.convertShortToBytes(faceElements[1].toShort()).toTypedArray())
                        indices.addAll(ByteUtils.convertShortToBytes(faceElements[2].toShort()).toTypedArray())*/
                    }
                }
            }
        }

        // convert face strings to face bytes
        val facePointsBytes = ByteArray(faceStrings.size * 6)
        faceStrings.forEach { face, index ->
            val faceElements = face.replace("\r", "").split("/")
            ByteUtils.applyShortToByteArray(faceElements[0].toShort(), facePointsBytes, index * 6 + 0)
            ByteUtils.applyShortToByteArray(faceElements[1].toShort(), facePointsBytes, index * 6 + 2)
            ByteUtils.applyShortToByteArray(faceElements[2].toShort(), facePointsBytes, index * 6 + 4)
        }

        println("Vertex count ${vertices.size / 12}")
        println("Normals count ${normals.size / 3}")
        println("Tex Coords count ${texCoords.size / 2}")
        //println("Indices count ${indices.size / 6}")

        println("Vertices Byte Size ${vertices.size}")
        println("Normals Byte Size ${normals.size}")
        println("Tex Coords Byte Size ${texCoords.size}")
        println("Face bytes Byte Size ${facePointsBytes.size}")
        println("Face indices Byte Size ${faceIndices.size}")
        //println("Indices Byte Size ${indices.size}")
        println("Total Byte Size ${vertices.size + normals.size + texCoords.size + facePointsBytes.size + faceIndices.size}")

        //println("Face indices byte size ${faceIndices.size}")
        //println("Face Elements ${faceStrings.size * 6}")

        // compile to byte array=
        bytes = byteArrayOf(
            *ByteUtils.convertIntToBytes(vertices.size / 12),
            *vertices.toByteArray(),
            *ByteUtils.convertIntToBytes(normals.size / 3),
            *normals.toByteArray(),
            *ByteUtils.convertIntToBytes(texCoords.size / 2),
            *texCoords.toByteArray(),
            *ByteUtils.convertIntToBytes(facePointsBytes.size / 6),
            *facePointsBytes,
            *ByteUtils.convertIntToBytes(faceIndices.size / 2),
            *faceIndices.toByteArray()
            //*ByteUtils.convertIntToBytes(indices.size / 6),
            //*indices.toByteArray(),
        )
    }

    override fun handle(exchange: HttpExchange) {
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }
}