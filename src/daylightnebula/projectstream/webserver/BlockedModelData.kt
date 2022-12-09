package daylightnebula.projectstream.webserver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.*
import java.io.File

class BBModelHandler(val subpath: String): HttpHandler {

    /**
     * numBlocks: Int
     * blocks: [ ( ~ 136 bytes per block )
     * - id: Int
     * - origin: 3 floats
     * - from: 3 floats
     * - to: 3 floats
     * - north: 4 floats
     * - south: 4 floats
     * -  east: 4 floats
     * -  west: 4 floats
     * -    up: 4 floats
     * -  down: 4 floats
     * ]: Array<Block>
     * numBones: Int
     * bones: [ 20 + (4 * length of childrenIDs)
     * - id: Int
     * - origin: 3 floats
     * - numChildren: Int
     * - childrenIDs: Array<Int>
     * - numAnimations: Int
     * ]: Array<Bone>
     * numAnimations: Int
     * animations: [
     * - name_length: Int
     * - name: String
     * - loop: Bool
     * - numAnimators: Int
     * - animators: [ 8 + (18 * numKeyFrames) byte size
     *   - target: Int
     *   - numKeyFrames: Int
     *   - keyFrames: [
     *     - channel: Byte (0x00 = position, 0x01 = rotation, 0x02 = scale)
     *     - values: 3 floats
     *     - time: float
     *     - interpolation: Byte (0x00 = linear, 0x01 = lazy)
     *   - ]
     * - ]
     * ]
     */

    val bytes: ByteArray

    init {
        val blockIDMap = mutableMapOf<String, Int>()
        val boneIDMap = mutableMapOf<String, Int>()

        // get and break up input json
        val text = File(WebServer.clientDirectory, subpath).readText()
        val inJson = Json.parseToJsonElement(text).jsonObject
        val elementsArray = inJson.get("elements")?.jsonArray ?: throw NullPointerException("Couldn't find elements array in bbmodel on subpath $subpath")
        val outlinerArray = inJson.get("outliner")?.jsonArray ?: throw NullPointerException("Couldn't find outliner array in bbmodel on subpath $subpath")
        val animationsArray = inJson.get("animations")?.jsonArray ?: throw NullPointerException("Couldn't find animations array in bbmodel on subpath $subpath")

        // build output byte array
        bytes = byteArrayOf(
            *getBlockBytes(elementsArray, blockIDMap),
            *getBoneBytes(outlinerArray, blockIDMap, boneIDMap),
            *getAnimationsBytes(animationsArray, blockIDMap, boneIDMap)
        )

        println("Prepared model with subpath $subpath")
    }

    override fun handle(exchange: HttpExchange) {
        // send everything
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        val os = exchange.responseBody
        os.write(bytes)
        os.close()
    }

    fun getAnimationsBytes(animationsArray: JsonArray, blockIDMap: Map<String, Int>, boneIDMap: MutableMap<String, Int>): ByteArray {
        val finalBytesList = mutableListOf<ByteArray>()

        // loop through each animation
        animationsArray.forEach {
            val subanimBytes = mutableListOf<ByteArray>()

            // unpack json
            val anim = it.jsonObject
            val name = anim.get("name")!!.jsonPrimitive.content
            val loop = anim.get("loop")!!.jsonPrimitive.content != "once"
            val subanims = anim.get("animators")!!.jsonObject

            var largestTime = 0f

            boneIDMap.keys.forEach { boneName ->
                // unpack information
                val subanim = subanims.get(boneName)!!.jsonObject
                val target = boneIDMap[boneName]!!
                val keyFrames = subanim.get("keyframes")!!.jsonArray

                // setup byte array
                val array = ByteArray((keyFrames.size * 18) + 8)
                ByteUtils.applyIntToByteArray(target, array, 0)
                ByteUtils.applyIntToByteArray(keyFrames.size, array, 4)

                // add each keyframe to the array
                keyFrames.forEachIndexed { index, jsonElement ->
                    val offset = 18 * index

                    val keyframe = jsonElement.jsonObject
                    val channel = stringToChannelID(keyframe.get("channel")!!.jsonPrimitive.content)
                    val valuesJson = keyframe.get("data_points")!!.jsonArray.first().jsonObject
                    val time = keyframe.get("time")!!.jsonPrimitive.float
                    val interpolation = stringToInterpolationID(keyframe.get("interpolation")!!.jsonPrimitive.content)

                    array[8 + offset] = channel
                    ByteUtils.applyFloatToByteArray(valuesJson.get("x")!!.jsonPrimitive.float, array, 8 + offset + 1)
                    ByteUtils.applyFloatToByteArray(valuesJson.get("y")!!.jsonPrimitive.float, array, 8 + offset + 5)
                    ByteUtils.applyFloatToByteArray(valuesJson.get("z")!!.jsonPrimitive.float, array, 8 + offset + 9)
                    ByteUtils.applyFloatToByteArray(time, array, 8 + offset + 13)
                    array[8 + offset + 17] = interpolation

                    if (time > largestTime)
                        largestTime = time
                }

                // save array to bytes list
                subanimBytes.add(array)
            }

            // convert all information to a single byte array and save it
            finalBytesList.add(
                byteArrayOf(
                    *ByteUtils.convertIntToBytes(name.length),
                    *name.toByteArray(),
                    (if (loop) 1 else 0).toByte(),
                    *flattenListOfByteArrays(subanimBytes),
                    *ByteUtils.convertFloatToByteArray(largestTime)
                )
            )
        }

        return flattenListOfByteArrays(finalBytesList)
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

    fun stringToChannelID(str: String): Byte {
        return when (str) {
            "position" -> 0
            "rotation" -> 1
            "scale" -> 2
            else -> throw NullPointerException("Unknown identifier $str when converting from string to channel id")
        }
    }

    fun stringToInterpolationID(str: String): Byte {
        return when (str) {
            "linear" -> 0
            "catmullrom" -> 1
            "step" -> 2
            else -> throw NullPointerException("Unknown identifier $str when converting from string to interpolation id")
        }
    }

    fun getBoneBytes(outlinerArray: JsonArray, blockIDMap: Map<String, Int>, boneIDMap: MutableMap<String, Int>): ByteArray {
        // setup output array
        val totalChildrenCount = outlinerArray.sumOf { outline -> outline.jsonObject.get("children")!!.jsonArray.size }
        val output = ByteArray((20 * outlinerArray.size) + (4 * totalChildrenCount) + 4)
        ByteUtils.applyIntToByteArray(outlinerArray.size, output, 0)

        // loop through each bone (outline)
        var counter = 4
        var i = 0
        while (i < outlinerArray.size) {
            // get current outline
            val outline = outlinerArray[i].jsonObject
            val uuid = outline.get("uuid")!!.jsonPrimitive.content
            val origin = outline.get("origin")!!.jsonArray
            val children = outline.get("children")!!.jsonArray
            boneIDMap[uuid] = i

            // apply basic info
            ByteUtils.applyIntToByteArray(i, output, counter)
            ByteUtils.applyFloatToByteArray(origin[0].jsonPrimitive.float, output, counter + 4)
            ByteUtils.applyFloatToByteArray(origin[1].jsonPrimitive.float, output, counter + 8)
            ByteUtils.applyFloatToByteArray(origin[2].jsonPrimitive.float, output, counter + 12)
            ByteUtils.applyIntToByteArray(children.size, output, counter + 16)
            counter += 20

            // apply children array to output
            children.forEachIndexed { index, child ->
                val id = blockIDMap[child.jsonPrimitive.content]!!
                ByteUtils.applyIntToByteArray(id, output, counter + (index * 4))
            }
            counter += 4 * children.size

            i++
        }

        println("Compiled bones")

        return output
    }

    fun getBlockBytes(elementsArray: JsonArray, IDMap: MutableMap<String, Int>): ByteArray {
        val output = ByteArray(136 * elementsArray.size + 4)
        ByteUtils.applyIntToByteArray(elementsArray.size, output, 0)

        var counter = 4
        repeat (elementsArray.size) { i ->
            // get block from json
            val block = elementsArray.get(i).jsonObject

            // convert the block json to bytes and then copy it into the block
            convertBlockJsonToByteArray(block, i).copyInto(output, counter)

            // save model uuid and its byte id to map for later
            IDMap[block.get("uuid")!!.jsonPrimitive.content] = i

            // update counter
            counter += 136
        }

        println("Compiled blocks")

        return output
    }

    fun convertBlockJsonToByteArray(block: JsonObject, id: Int): ByteArray {
        val blockData = ByteArray(136)

        val origin = block.get("origin")!!.jsonArray
        val from = block.get("from")!!.jsonArray
        val to = block.get("to")!!.jsonArray
        val faces = block.get("faces")!!.jsonObject
        val north = faces.get("north")!!.jsonObject.get("uv")!!.jsonArray
        val south = faces.get("south")!!.jsonObject.get("uv")!!.jsonArray
        val east = faces.get("east")!!.jsonObject.get("uv")!!.jsonArray
        val west = faces.get("west")!!.jsonObject.get("uv")!!.jsonArray
        val up = faces.get("up")!!.jsonObject.get("uv")!!.jsonArray
        val down = faces.get("down")!!.jsonObject.get("uv")!!.jsonArray

        ByteUtils.applyIntToByteArray(id, blockData, 0)
        ByteUtils.applyFloatToByteArray(origin[0].jsonPrimitive.float, blockData, 4)
        ByteUtils.applyFloatToByteArray(origin[1].jsonPrimitive.float, blockData, 8)
        ByteUtils.applyFloatToByteArray(origin[2].jsonPrimitive.float, blockData, 12)
        ByteUtils.applyFloatToByteArray(from[0].jsonPrimitive.float, blockData, 16)
        ByteUtils.applyFloatToByteArray(from[1].jsonPrimitive.float, blockData, 20)
        ByteUtils.applyFloatToByteArray(from[2].jsonPrimitive.float, blockData, 24)
        ByteUtils.applyFloatToByteArray(to[0].jsonPrimitive.float, blockData, 28)
        ByteUtils.applyFloatToByteArray(to[1].jsonPrimitive.float, blockData, 32)
        ByteUtils.applyFloatToByteArray(to[2].jsonPrimitive.float, blockData, 36)

        ByteUtils.applyFloatToByteArray(north[0].jsonPrimitive.float, blockData, 40)
        ByteUtils.applyFloatToByteArray(north[1].jsonPrimitive.float, blockData, 44)
        ByteUtils.applyFloatToByteArray(north[2].jsonPrimitive.float, blockData, 48)
        ByteUtils.applyFloatToByteArray(north[3].jsonPrimitive.float, blockData, 52)

        ByteUtils.applyFloatToByteArray(south[0].jsonPrimitive.float, blockData, 56)
        ByteUtils.applyFloatToByteArray(south[1].jsonPrimitive.float, blockData, 60)
        ByteUtils.applyFloatToByteArray(south[2].jsonPrimitive.float, blockData, 64)
        ByteUtils.applyFloatToByteArray(south[3].jsonPrimitive.float, blockData, 68)

        ByteUtils.applyFloatToByteArray(east[0].jsonPrimitive.float, blockData, 72)
        ByteUtils.applyFloatToByteArray(east[1].jsonPrimitive.float, blockData, 76)
        ByteUtils.applyFloatToByteArray(east[2].jsonPrimitive.float, blockData, 80)
        ByteUtils.applyFloatToByteArray(east[3].jsonPrimitive.float, blockData, 84)

        ByteUtils.applyFloatToByteArray(west[0].jsonPrimitive.float, blockData, 88)
        ByteUtils.applyFloatToByteArray(west[1].jsonPrimitive.float, blockData, 92)
        ByteUtils.applyFloatToByteArray(west[2].jsonPrimitive.float, blockData, 96)
        ByteUtils.applyFloatToByteArray(west[3].jsonPrimitive.float, blockData, 100)

        ByteUtils.applyFloatToByteArray(up[0].jsonPrimitive.float, blockData, 104)
        ByteUtils.applyFloatToByteArray(up[1].jsonPrimitive.float, blockData, 108)
        ByteUtils.applyFloatToByteArray(up[2].jsonPrimitive.float, blockData, 112)
        ByteUtils.applyFloatToByteArray(up[3].jsonPrimitive.float, blockData, 116)

        ByteUtils.applyFloatToByteArray(down[0].jsonPrimitive.float, blockData, 120)
        ByteUtils.applyFloatToByteArray(down[1].jsonPrimitive.float, blockData, 124)
        ByteUtils.applyFloatToByteArray(down[2].jsonPrimitive.float, blockData, 128)
        ByteUtils.applyFloatToByteArray(down[3].jsonPrimitive.float, blockData, 132)

        return blockData
    }
}