package daylightnebula.projectstream.webserver

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.random.Random

object ChunkedImageProcessor {
    val maxChunkWidth = 256 // maximum length of a node side in pixels
    val breakWidth = 4 // the square of this is the number of nodes that each node with be broken up into

    // returns root node
    fun breakupImage(image: BufferedImage): Node {
        return createNode(image, 0, 0, image.width, image.height, 0, 0, maxChunkWidth, maxChunkWidth)
    }

    fun createNode(
        image: BufferedImage,
        srcX: Int, srcY: Int,
        srcWidth: Int, srcHeight: Int,
        nodeX: Int, nodeY: Int,
        nodeWidth: Int, nodeHeight: Int
    ): Node {
        //println("SRCX $srcX SRCY $srcY SCRW $srcWidth SRCH $srcHeight NX $nodeX NY $nodeY NW $nodeWidth NH $nodeHeight")
        val xStep = srcWidth / nodeWidth
        val yStep = srcHeight / nodeHeight
        val createMoreNodes = srcWidth > maxChunkWidth || srcHeight > maxChunkWidth
        return Node(
            srcX, srcY, srcWidth, srcHeight,
            nodeX, nodeY, nodeWidth, nodeHeight,
            if (!createMoreNodes) arrayOf() else Array(breakWidth * breakWidth) { index ->
                val x = index % breakWidth
                val y = index / breakWidth
                val xPerc = x / breakWidth.toFloat()
                val yPerc = y / breakWidth.toFloat()
                //println("X $x Y $y xPerc $xPerc Change ${srcWidth * xPerc} New X ${srcX + (srcWidth * xPerc)} Node Change ${nodeWidth * xPerc} Node New X ${nodeX + (nodeWidth * xPerc)}")

                createNode(
                    image,
                    (srcX + (srcWidth * xPerc)).toInt(), (srcY + (srcHeight * yPerc)).toInt(),
                    srcWidth / breakWidth, srcHeight / breakWidth,
                    (nodeX + (nodeWidth * xPerc)).toInt(), (nodeY + (nodeHeight * yPerc)).toInt(),
                    256, 256 //nodeWidth / breakWidth, nodeHeight / breakWidth
                )
            },
            Array(nodeWidth * nodeHeight) { index ->
                val x = index % nodeWidth
                val y = index / nodeWidth
                try {
                    image.getRGB(x * xStep + srcX, y * yStep + srcY)
                } catch (ex: ArrayIndexOutOfBoundsException) {
                    println("X $x Y $y")
                    Color.GREEN.rgb
                }
            }
        )
    }

    fun runTests() {
        val testImage = ImageIO.read(File("D:\\projectstream\\WebServer\\WebServer\\tests\\Albedo.png"))
        val testNode = breakupImage(testImage)
        saveNodeToImage(testNode, "test_output")
    }

    fun saveNodeToImage(node: Node, name: String) {
        val image = BufferedImage(node.width, node.height, BufferedImage.TYPE_INT_ARGB)
        repeat(node.width) { x ->
            repeat(node.height) { y ->
                image.setRGB(x, y, node.pixels[y * node.width + x])
            }
        }
        node.subNodes.forEachIndexed { index, node ->
            saveNodeToImage(node, "${name}_$index")
        }
        ImageIO.write(image, "png", File("D:\\projectstream\\WebServer\\WebServer\\tests\\${name}.png"))
    }

    data class Node(
        val srcX: Int, val srcY: Int, val srcWidth: Int, val srcHeight: Int,
        val x: Int, val y: Int, val width: Int, val height: Int,
        val subNodes: Array<Node>,
        val pixels: Array<Int>
    )
}