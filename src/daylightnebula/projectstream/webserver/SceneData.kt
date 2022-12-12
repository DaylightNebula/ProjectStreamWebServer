package daylightnebula.projectstream.webserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.*
import java.io.File

class SceneHandler(file: File): HttpHandler {

    /**
     * camera_position: 3 floats
     * camera_rotation: 3 floats
     * numEntities: Int
     * entities: [
     * - name_length: Int
     * - name_bytes: Array<Byte>
     * - position: 3 floats
     * - rotation: 3 floats
     * - scale: 3 floats
     * - numComponents: Int
     * - components: [
     *   - type_length: Int
     *   - type_bytes: Array<Byte>
     *   - numArgs: Int
     *   - args: [
     *     - arg_length: Int
     *     - arg_bytes: Array<Byte>
     *     - info_length: Int
     *     - info_bytes: Array<Byte>
     *   - ]
     * - ]
     * ]
     * numControlScripts: Int
     * controlScripts: [
     * - path_length: Int
     * - path_bytes: Array<Byte>
     * ]
     */

    val data: ByteArray

    init {
        // get json file
        val text = file.readText()
        val inJson = Json.parseToJsonElement(text).jsonObject

        // breakup json
        val cameraJson = inJson.get("camera")!!.jsonObject
        val entitiesJson = inJson.get("entities")!!.jsonArray
        val controlScriptsJson = inJson.get("controlScripts")!!.jsonArray

        data = byteArrayOf(
            *getCameraBytes(cameraJson),
            *getEntitiesBytes(entitiesJson),
            *getControlScriptsBytes(controlScriptsJson)
        )
    }

    fun getCameraBytes(cameraJson: JsonObject): ByteArray {
        // unpack json
        val position = cameraJson.get("position")!!.jsonArray
        val rotation = cameraJson.get("rotation")!!.jsonArray

        // convert position and rotation to a single byte array
        return byteArrayOf(
            *ByteUtils.convertFloatToByteArray(position[0].jsonPrimitive.float),
            *ByteUtils.convertFloatToByteArray(position[1].jsonPrimitive.float),
            *ByteUtils.convertFloatToByteArray(position[2].jsonPrimitive.float),
            *ByteUtils.convertFloatToByteArray(rotation[0].jsonPrimitive.float),
            *ByteUtils.convertFloatToByteArray(rotation[1].jsonPrimitive.float),
            *ByteUtils.convertFloatToByteArray(rotation[2].jsonPrimitive.float),
        )
    }

    fun getEntitiesBytes(entitiesJson: JsonArray): ByteArray {
        val entityBytes = mutableListOf<ByteArray>()

        entitiesJson.forEach {
            val entity = it.jsonObject

            // breakup json
            val name = entity.get("name")!!.jsonPrimitive.content
            val position = entity.get("position")?.jsonArray
            val rotation = entity.get("rotation")?.jsonArray
            val scale = entity.get("scale")?.jsonArray
            val components = entity.get("components")!!.jsonArray

            // build components byte array list
            val componentsBytes = mutableListOf<ByteArray>()
            components.forEach {
                // breakup json
                val component = it.jsonObject
                val type = component.get("type")!!.jsonPrimitive.content

                // read arguments
                val argumentsBytes = mutableListOf<ByteArray>()
                component.keys.forEach { key ->
                    // skip type keys
                    if (key == "type") return@forEach

                    // get value
                    val value = component.get(key)!!.jsonPrimitive.content

                    // add to byte array list
                    argumentsBytes.add(
                        byteArrayOf(
                            *ByteUtils.convertIntToBytes(key.length),
                            *key.toByteArray(),
                            *ByteUtils.convertIntToBytes(value.length),
                            *value.toByteArray()
                        )
                    )
                }

                // build final component byte array
                componentsBytes.add(
                    byteArrayOf(
                        *ByteUtils.convertIntToBytes(type.length),
                        *type.toByteArray(),
                        *flattenListOfByteArrays(argumentsBytes)
                    )
                )
            }

            // build final entity byte array
            entityBytes.add(
                byteArrayOf(
                    *ByteUtils.convertIntToBytes(name.length),
                    *name.toByteArray(),
                    *ByteUtils.convertFloatToByteArray(position?.get(0)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(position?.get(1)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(position?.get(2)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(rotation?.get(0)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(rotation?.get(1)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(rotation?.get(2)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(scale?.get(0)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(scale?.get(1)?.jsonPrimitive?.float ?: 0f),
                    *ByteUtils.convertFloatToByteArray(scale?.get(2)?.jsonPrimitive?.float ?: 0f),
                    *flattenListOfByteArrays(componentsBytes)
                )
            )
        }

        return flattenListOfByteArrays(entityBytes)
    }

    fun getControlScriptsBytes(controlScriptsJson: JsonArray): ByteArray {
        val bytesList = mutableListOf<ByteArray>()

        controlScriptsJson.forEach {
            val path = it.jsonPrimitive.content
            println("Control script $path")
            bytesList.add(
                byteArrayOf(
                    *ByteUtils.convertIntToBytes(path.length),
                    *path.toByteArray()
                )
            )
        }

        return byteArrayOf(
            *ByteUtils.convertIntToBytes(controlScriptsJson.size),
            *flattenListOfByteArrays(bytesList)
        )
    }

    override fun handle(exchange: HttpExchange) {
        // send everything
        exchange.sendResponseHeaders(200, data.size.toLong())
        val os = exchange.responseBody
        os.write(data)
        os.close()
    }

    fun flattenListOfByteArrays(list: List<ByteArray>): ByteArray {
        val output = ByteArray(list.sumOf { it.size } + 4)
        ByteUtils.applyIntToByteArray(list.size, output, 0)
        var counter = 4
        var i = 0
        while (i < list.size) {
            val arr = list[i]
            arr.copyInto(output, counter)
            counter += arr.size
            i++
        }
        return output
    }
}