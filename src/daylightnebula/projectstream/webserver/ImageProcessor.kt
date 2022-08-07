package daylightnebula.projectstream.webserver

import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.lang.Integer.max
import java.lang.Integer.min
import javax.imageio.ImageIO

object ImageProcessor {

    /**
     * IDEA:
     * 1. Build into array of 256x256 blocks of YUV compressed pixels
     * 2. Each block should also store an average of the omitted bits
     *      from both the U and V channels during YUV compression.
     * 3. Add those averages back on at the end.
     */

    fun runTest() {
        val ycbcr = rgb2yuv(255, 255, 0)
        println("YCbCr $ycbcr")
        val rgb = yuv2rgb(ycbcr.first, ycbcr.second, ycbcr.third)
        println("RGB $rgb")

        val compressedYUV = compressYUV(255, 240, 240)//compressYUV(ycbcr.first, ycbcr.second, ycbcr.third)
        val uncompressedYUV = uncompressYUV(compressedYUV.first, compressedYUV.second)

        val testImage = ImageIO.read(File("D:\\megascans\\exports\\Decorative Antlers Wall Mount_udehcidiw\\Albedo.png"))
        val compressedImage = convertImageToYUV(testImage)

        val uncompressedImage = convertYUVToImage(compressedImage)
        ImageIO.write(uncompressedImage, "png", File("D:\\projectstream\\ProjectStream\\tests\\test.png"))
    }

    fun convertYUVToImage(yuvImage: YUVImage): BufferedImage {
        val image = BufferedImage(yuvImage.width, yuvImage.height, BufferedImage.TYPE_3BYTE_BGR)
        repeat(yuvImage.height) { y ->
            repeat(yuvImage.width) { x ->
                val pixel = yuvImage.getPixel(x, y)
                val yuv = uncompressYUV(pixel.first, pixel.second)
                val rgb = yuv2rgb(yuv.first, yuv.second, yuv.third)
                val color = Color(rgb.first, rgb.second, rgb.third)
                if (x == 0 && y == 0) {
                    println("First post compress ${color.red} ${color.green} ${color.blue}")
                    println("First post compress ${yuv.first} ${yuv.second} ${yuv.third}")
                }
                image.setRGB(x, y, color.rgb)
            }
        }
        return image
    }

    fun convertImageToYUV(image: BufferedImage): YUVImage {
        return YUVImage(image.width, image.height,
            Array<Pair<UByte, UByte>>(image.width * image.height) { index ->
                val y = index / image.width
                val x = index % image.width
                val color = Color(image.getRGB(x, y))
                val yuv = rgb2yuv(color.red, color.green, color.blue)
                if (x == 0 && y == 0) {
                    println("First pre compress ${color.red} ${color.green} ${color.blue}")
                    println("First pre compress ${yuv.first} ${yuv.second} ${yuv.third}")
                }
                compressYUV(yuv.first, yuv.second, yuv.third)
            }
        )
    }

    fun rgb2yuv(R: Int, G: Int, B: Int): Triple<Int, Int, Int> {
        var Y = (66 * R + 129 * G + 25 * B + 128 shr 8) + 16
        var U = (-38 * R - 74 * G + 112 * B + 128 shr 8) + 128
        var V = (112 * R - 94 * G - 18 * B + 128 shr 8) + 128

        Y = min(max(Y, 0), 255)
        U = min(max(U, 0), 255)
        V = min(max(V, 0), 255)
        return Triple(Y, U, V)
    }

    fun yuv2rgb(y: Int, cb: Int, cr: Int): Triple<Int, Int, Int> {
        var r = (298.082 * (y - 16) + 408.583 * (cr - 128)).toInt() shr 8
        var g = (298.082 * (y - 16) + -100.291 * (cb - 128) + -208.120 * (cr - 128)).toInt() shr 8
        var b = (298.082 * (y - 16) + 516.411 * (cb - 128)).toInt() shr 8

        r = min(max(r, 0), 255)
        g = min(max(g, 0), 255)
        b = min(max(b, 0), 255)
        return Triple(r, g, b)
    }

    fun compressYUV(inY: Int, inU: Int, inV: Int): Pair<UByte, UByte> {
        val outY = inY.toUByte()
        val tmpU = inU.toUByte()
        val tmpV = inV.toUByte()
        // first four bits = 15u, second four bits = 240u
        val outUV = ((tmpU.toInt() shr 4).toUByte() and 15u) or (tmpV and 240u)
        /*println("U CONVERSION")
        printByte(tmpU.toByte())
        printByte((tmpU and 240u).toByte())
        println("V CONVERSION")
        printByte(tmpV.toByte())
        printByte(((tmpV.toInt() shr 4).toUByte()).toByte())
        printByte(((tmpV.toInt() shr 4).toUByte() and 15u).toByte())
        println("Final")
        printByte(outUV.toByte())
        println()*/

        return Pair(outY, outUV)
    }

    fun uncompressYUV(inY: UByte, inUV: UByte): Triple<Int, Int, Int> {
        val outY = inY.toInt()
        val outU = (inUV and 15u).toInt() shl 4 or 8
        val outV = (inUV and 240u).toInt() or 8

        /*printByte(outU.toByte())
        printByte(outV.toByte())
        printByte((outU or 10).toByte())
        printByte((outV or 10).toByte())*/

        return Triple(outY, outU + 1, outV + 1)
    }

    fun printByte(byte: Byte) {
        repeat(8) {
            print(getBit(byte.toInt(), it))
        }
        println()
    }

    fun getBit(value: Int, position: Int): Int {
        return (value shr position) and 1;
    }

    class YUVImage(val width: Int, val height: Int, val pixels: Array<Pair<UByte, UByte>>) {
        fun getPixel(x: Int, y: Int): Pair<UByte, UByte> {
            return pixels[y * width + x]
        }
    }
}