package daylightnebula.projectstream.webserver

import java.nio.ByteBuffer


class ByteUtils {
    companion object {
        fun convertShortToBytes(value: Short): ByteArray {
            return byteArrayOf(
                (value.toInt() shr 0).toByte(),
                (value.toInt() shr 8).toByte(),
            )
        }

        fun convertIntToBytes(value: Int): ByteArray {
            return byteArrayOf(
                (value shr 0).toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte()
            )
        }

        fun convertIntToBytes(value: UInt): ByteArray {
            return byteArrayOf(
                (value shr 0).toByte(),
                (value shr 8).toByte(),
                (value shr 16).toByte(),
                (value shr 24).toByte()
            )
        }

        fun convertBytesToInt(buffer: ByteArray, startByte: Int): Int {
            return (buffer[startByte + 3].toInt() shl 24) or
                    (buffer[startByte + 2].toInt() and 0xff shl 16) or
                    (buffer[startByte + 1].toInt() and 0xff shl 8) or
                    (buffer[startByte + 0].toInt() and 0xff)
        }

        fun convertBytesToShort(buffer: ByteArray, startByte: Int): Short {
            return ((buffer[startByte + 1].toInt() and 0xff shl 8) or
                    (buffer[startByte + 0].toInt() and 0xff)).toShort()
        }

        fun applyIntToByteArray(value: Int, array: ByteArray, startIndex: Int) {
            val newBytes = convertIntToBytes(value)
            array[startIndex + 0] = newBytes[0]
            array[startIndex + 1] = newBytes[1]
            array[startIndex + 2] = newBytes[2]
            array[startIndex + 3] = newBytes[3]
        }

        fun applyIntToByteArray(value: UInt, array: ByteArray, startIndex: Int) {
            val newBytes = convertIntToBytes(value)
            array[startIndex + 0] = newBytes[0]
            array[startIndex + 1] = newBytes[1]
            array[startIndex + 2] = newBytes[2]
            array[startIndex + 3] = newBytes[3]
        }

        fun applyFloatToByteArray(value: Float, array: ByteArray, startIndex: Int) {
            val newBytes = ByteBuffer.allocate(4).putFloat(value).array()
            array[startIndex + 0] = newBytes[3]
            array[startIndex + 1] = newBytes[2]
            array[startIndex + 2] = newBytes[1]
            array[startIndex + 3] = newBytes[0]
        }

        fun getBit(value: Int, position: Int): Int {
            return (value shr position) and 1;
        }
    }
}