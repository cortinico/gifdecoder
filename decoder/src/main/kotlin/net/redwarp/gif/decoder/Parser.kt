package net.redwarp.gif.decoder

import net.redwarp.gif.decoder.descriptors.Dimension
import net.redwarp.gif.decoder.descriptors.GifDescriptor
import net.redwarp.gif.decoder.descriptors.GraphicControlExtension
import net.redwarp.gif.decoder.descriptors.Header
import net.redwarp.gif.decoder.descriptors.ImageDescriptor
import net.redwarp.gif.decoder.descriptors.LogicalScreenDescriptor
import net.redwarp.gif.decoder.descriptors.Point
import net.redwarp.gif.decoder.utils.readAsciiString
import net.redwarp.gif.decoder.utils.readByte
import net.redwarp.gif.decoder.utils.readShortLe
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

private const val IMAGE_DESCRIPTOR_SEPARATOR = 0x2c.toByte()
private const val GIF_TERMINATOR = 0x3b.toByte()
private const val EXTENSION_INTRODUCER = 0x21.toByte()
private const val APPLICATION_EXTENSION = 0xff.toByte()
private const val GRAPHIC_CONTROL_EXTENSION = 0xf9.toByte()
private const val NETSCAPE = "NETSCAPE2.0"
private const val ANIMEXTS = "ANIMEXTS1.0"

/**
 * Based on https://web.archive.org/web/20160304075538/http://qalle.net/gif89a.php
 */
object Parser {

    @Throws(InvalidGifException::class)
    fun parse(file: File, pixelPacking: PixelPacking = PixelPacking.ARGB): GifDescriptor =
        parse(file.inputStream(), pixelPacking)

    @Throws(InvalidGifException::class)
    fun parse(
        inputStream: InputStream,
        pixelPacking: PixelPacking = PixelPacking.ARGB
    ): GifDescriptor {
        inputStream.buffered(2048).use { stream ->
            val header = stream.parseHeader()
            val logicalScreenDescriptor = stream.parseLogicalScreenDescriptor()

            val globalColorTable: IntArray? = if (logicalScreenDescriptor.hasGlobalColorTable) {
                stream.parseColorTable(logicalScreenDescriptor.colorCount, pixelPacking)
            } else {
                null
            }

            val (loopCount, imageDescriptors) = parseLoop(stream, pixelPacking)

            return GifDescriptor(
                header = header,
                logicalScreenDescriptor = logicalScreenDescriptor,
                globalColorTable = globalColorTable,
                // If only one image, no loop.
                loopCount = if (imageDescriptors.size <= 1) null else loopCount,
                imageDescriptors = imageDescriptors
            )
        }
    }

    @Throws(InvalidGifException::class)
    private fun InputStream.parseHeader(): Header {
        return when (val headerString = readAsciiString(6)) {
            "GIF87a" -> Header.GIF87a
            "GIF89a" -> Header.GIF89a
            else -> throw InvalidGifException("$headerString is not a valid GIF header")
        }
    }

    private fun InputStream.parseLogicalScreenDescriptor(): LogicalScreenDescriptor {
        val dimension = Dimension(readShortLe(), readShortLe())
        val packedFields = readByte().toUByte()
        val hasGlobalColorTableMask: UByte = 0b1000_0000u
        val hasGlobalColorTable =
            (hasGlobalColorTableMask and packedFields) == hasGlobalColorTableMask
        val sizeOfGlobalColorTableMask: UByte = 0b0000_0111u
        val sizeOfGlobalColorTable = (sizeOfGlobalColorTableMask and packedFields).toInt()

        val backgroundColorIndex = readByte()

        return LogicalScreenDescriptor(
            dimension = dimension,
            hasGlobalColorTable = hasGlobalColorTable,
            sizeOfGlobalColorTable = sizeOfGlobalColorTable,
            // If there is no global color table, the background color index is meaningless.
            backgroundColorIndex = if (hasGlobalColorTable) backgroundColorIndex else null,
            pixelAspectRatio = readByte()
        )
    }

    private fun InputStream.parseColorTable(colorCount: Int, pixelPacking: PixelPacking): IntArray {
        val colors = IntArray(colorCount)
        for (colorIndex in 0 until colorCount) {
            val r = readByte().toInt()
            val g = readByte().toInt()
            val b = readByte().toInt()

            val color: Int = when (pixelPacking) {
                PixelPacking.ARGB -> 0xff000000.toInt() or (r.shl(16) and 0x00ff0000) or (g.shl(8) and 0x0000ff00) or (b and 0x000000ff)
                PixelPacking.ABGR -> 0xff000000.toInt() or (b.shl(16) and 0x00ff0000) or (g.shl(8) and 0x0000ff00) or (r and 0x000000ff)
            }
            colors[colorIndex] = color
        }

        return colors
    }

    private fun InputStream.parseGraphicControl(): GraphicControlExtension {
        val blockSize = readByte()
        if (blockSize != 4.toByte()) throw InvalidGifException("Block size of the graphic control should be 4")

        val packedField = readByte().toInt()
        val disposalMethodValue = packedField.shr(2) and 0b0111
        val hasTransparency = packedField and 0b0001 == 1

        val delayTime = readShortLe().toUShort()
        val transparentColorIndex = readByte()

        val terminator = readByte()
        if (terminator != 0.toByte()) throw InvalidGifException("Terminator not properly set")

        val disposalMethod =
            if (disposalMethodValue >= GraphicControlExtension.Disposal.values().size) {
                // Unsupported disposal method, we default to not specified.
                GraphicControlExtension.Disposal.NOT_SPECIFIED
            } else {
                GraphicControlExtension.Disposal.values()[disposalMethodValue]
            }

        return GraphicControlExtension(
            disposalMethod = disposalMethod,
            delayTime = delayTime,
            transparentColorIndex = if (hasTransparency) transparentColorIndex else null
        )
    }

    private fun InputStream.parseApplicationId(): String {
        skip(1)
        return readAsciiString(11)
    }

    private fun InputStream.parseLoopCount(): Int {
        skip(2)
        val count = readShortLe().toInt()
        skip(1)

        return count
    }

    private fun InputStream.skipSubBlocks() {
        var subBlockSize: Long = readByte().toLong()
        while (subBlockSize != 0L) {
            skip(subBlockSize)
            subBlockSize = readByte().toLong()
        }
    }

    private fun parseLoop(
        bufferedSource: InputStream,
        pixelPacking: PixelPacking
    ): Pair<Int?, List<ImageDescriptor>> {
        var loopCount: Int? = 0
        var pendingGraphicControl: GraphicControlExtension? = null
        val imageDescriptors: MutableList<ImageDescriptor> = mutableListOf()
        while (true) {
            when (bufferedSource.readByte()) {
                IMAGE_DESCRIPTOR_SEPARATOR -> {
                    imageDescriptors.add(
                        bufferedSource.parseImageDescriptor(
                            pendingGraphicControl,
                            pixelPacking
                        )
                    )
                    pendingGraphicControl = null
                }
                GIF_TERMINATOR -> {
                    break
                }
                EXTENSION_INTRODUCER -> {
                    when (bufferedSource.readByte()) {
                        APPLICATION_EXTENSION -> {
                            val applicationId = bufferedSource.parseApplicationId()
                            if (applicationId == NETSCAPE || applicationId == ANIMEXTS) {
                                loopCount = bufferedSource.parseLoopCount()
                            } else {
                                // There might be other application extensions out there but...
                                // we probably don't care.
                                bufferedSource.skipSubBlocks()
                            }
                        }
                        GRAPHIC_CONTROL_EXTENSION -> {
                            pendingGraphicControl = bufferedSource.parseGraphicControl()
                        }
                        else -> {
                            bufferedSource.skipSubBlocks()
                        }
                    }
                }
            }
        }

        return Pair(loopCount, imageDescriptors)
    }

    private fun InputStream.parseImageDescriptor(
        graphicControlExtension: GraphicControlExtension?,
        pixelPacking: PixelPacking
    ): ImageDescriptor {
        val position = Point(readShortLe(), readShortLe())
        val dimension = Dimension(readShortLe(), readShortLe())

        val packedFields = readByte().toUByte()

        val colorTableFlagMask: UByte = 0b1000_0000u
        val usesLocalColorTable = (packedFields and colorTableFlagMask) == colorTableFlagMask

        val interlacedMask: UByte = 0b0100_0000u
        val isInterlaced = (packedFields and interlacedMask) == interlacedMask

        val sizeOfLocalTableMask: UByte = 0b0000_0111u
        val sizeOfLocalTable = (sizeOfLocalTableMask and packedFields).toInt()
        val colorCount = 1.shl(sizeOfLocalTable + 1)
        val localColorTable: IntArray? = if (usesLocalColorTable) {
            parseColorTable(colorCount, pixelPacking)
        } else {
            null
        }

        val imageData = readImageData()

        return ImageDescriptor(
            position = position,
            dimension = dimension,
            isInterlaced = isInterlaced,
            localColorTable = localColorTable,
            imageData = imageData,
            graphicControlExtension = graphicControlExtension
        )
    }

    internal fun InputStream.readImageData(): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        byteArrayOutputStream.write(readByte().toInt())

        while (true) {
            val blockSize = readByte().toUByte().toInt()
            byteArrayOutputStream.write(blockSize)
            if (blockSize == 0) {
                break
            }
            repeat(blockSize) {
                byteArrayOutputStream.write(read())
            }
        }

        return byteArrayOutputStream.toByteArray()
    }
}
