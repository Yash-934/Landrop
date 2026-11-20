package com.example.util

/**
 * Pure-Kotlin ISO/IEC 18004 QR Code matrix generator (Byte mode, ECC Level L, Versions 1..6).
 * Handles URLs up to 134 UTF-8 bytes cleanly with zero external library dependencies.
 */
object QrCodeGenerator {

    private data class VersionSpec(
        val version: Int,
        val size: Int,
        val totalCodewords: Int,
        val ecCodewordsPerBlock: Int,
        val numBlocks: Int,
        val alignmentCenters: IntArray,
    )

    // ISO/IEC 18004 Table 9 for Error Correction Level L (Versions 1..6)
    private val versionSpecs = listOf(
        VersionSpec(1, 21, 26, 7, 1, intArrayOf()),
        VersionSpec(2, 25, 44, 10, 1, intArrayOf(6, 18)),
        VersionSpec(3, 29, 70, 15, 1, intArrayOf(6, 22)),
        VersionSpec(4, 33, 100, 20, 1, intArrayOf(6, 26)),
        VersionSpec(5, 37, 134, 26, 1, intArrayOf(6, 30)),
        VersionSpec(6, 41, 172, 18, 2, intArrayOf(6, 34)),
    )

    private val gfExp = IntArray(512)
    private val gfLog = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            gfExp[i] = x
            gfLog[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11D
        }
        for (i in 255 until 512) {
            gfExp[i] = gfExp[i - 255]
        }
    }

    fun encode(text: String): Array<BooleanArray>? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        // Byte mode header: 4-bit mode (0100) + 8-bit length + 8*N bits data
        val spec = versionSpecs.firstOrNull { spec ->
            val dataCodewords = spec.totalCodewords - spec.ecCodewordsPerBlock * spec.numBlocks
            val capacityBytes = dataCodewords - 2 // 4 + 8 bits header rounds to 2 bytes max overhead
            bytes.size <= capacityBytes
        } ?: return null

        val dataCodewords = encodeDataCodewords(bytes, spec)
        val fullCodewords = addErrorCorrection(dataCodewords, spec)
        val bits = toBitList(fullCodewords)

        val size = spec.size
        val isFunction = Array(size) { BooleanArray(size) }
        val baseMatrix = Array(size) { BooleanArray(size) }

        placeFunctionPatterns(baseMatrix, isFunction, spec)
        placeDataBits(baseMatrix, isFunction, bits)

        var bestMask = 0
        var bestPenalty = Int.MAX_VALUE
        var bestMatrix = baseMatrix

        for (mask in 0..7) {
            val candidate = Array(size) { r -> baseMatrix[r].copyOf() }
            applyMask(candidate, isFunction, mask)
            drawFormatBits(candidate, mask)
            val penalty = calculatePenalty(candidate)
            if (penalty < bestPenalty) {
                bestPenalty = penalty
                bestMask = mask
                bestMatrix = candidate
            }
        }
        drawFormatBits(bestMatrix, bestMask)
        return bestMatrix
    }

    private fun encodeDataCodewords(data: ByteArray, spec: VersionSpec): IntArray {
        val dataCapacityCodewords = spec.totalCodewords - spec.ecCodewordsPerBlock * spec.numBlocks
        val dataCapacityBits = dataCapacityCodewords * 8
        val bits = ArrayList<Int>(dataCapacityBits)

        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }

        // Mode indicator: Byte (0100)
        appendBits(0b0100, 4)
        // Character count indicator (8 bits for versions 1..9)
        appendBits(data.size, 8)
        for (b in data) {
            appendBits(b.toInt() and 0xFF, 8)
        }

        // Terminator up to 4 zero bits
        val terminatorLen = minOf(4, dataCapacityBits - bits.size)
        appendBits(0, terminatorLen)

        // Pad to byte boundary
        while (bits.size % 8 != 0) {
            bits.add(0)
        }

        val codewords = IntArray(dataCapacityCodewords)
        var idx = 0
        var bitPos = 0
        while (bitPos < bits.size) {
            var value = 0
            for (j in 0 until 8) {
                value = (value shl 1) or bits[bitPos++]
            }
            codewords[idx++] = value
        }

        val padBytes = intArrayOf(0xEC, 0x11)
        var padIdx = 0
        while (idx < dataCapacityCodewords) {
            codewords[idx++] = padBytes[padIdx]
            padIdx = (padIdx + 1) % 2
        }
        return codewords
    }

    private fun addErrorCorrection(dataCodewords: IntArray, spec: VersionSpec): IntArray {
        val numBlocks = spec.numBlocks
        val ecLen = spec.ecCodewordsPerBlock
        val dataPerBlock = dataCodewords.size / numBlocks

        val dataBlocks = Array(numBlocks) { b ->
            dataCodewords.copyOfRange(b * dataPerBlock, (b + 1) * dataPerBlock)
        }
        val ecBlocks = Array(numBlocks) { b ->
            reedSolomonRemainder(dataBlocks[b], ecLen)
        }

        val result = IntArray(spec.totalCodewords)
        var outIdx = 0
        for (i in 0 until dataPerBlock) {
            for (b in 0 until numBlocks) {
                result[outIdx++] = dataBlocks[b][i]
            }
        }
        for (i in 0 until ecLen) {
            for (b in 0 until numBlocks) {
                result[outIdx++] = ecBlocks[b][i]
            }
        }
        return result
    }

    private fun gfMultiply(a: Int, b: Int): Int {
        if (a == 0 || b == 0) return 0
        return gfExp[gfLog[a] + gfLog[b]]
    }

    private fun reedSolomonGenerator(degree: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until degree) {
            val next = IntArray(poly.size + 1)
            val factor = gfExp[i]
            for (j in poly.indices) {
                next[j] = next[j] xor poly[j]
                next[j + 1] = next[j + 1] xor gfMultiply(poly[j], factor)
            }
            poly = next
        }
        return poly
    }

    private fun reedSolomonRemainder(data: IntArray, ecLen: Int): IntArray {
        val gen = reedSolomonGenerator(ecLen)
        val remainder = IntArray(ecLen)
        for (byte in data) {
            val factor = byte xor remainder[0]
            System.arraycopy(remainder, 1, remainder, 0, ecLen - 1)
            remainder[ecLen - 1] = 0
            if (factor != 0) {
                for (i in 0 until ecLen) {
                    remainder[i] = remainder[i] xor gfMultiply(gen[i + 1], factor)
                }
            }
        }
        return remainder
    }

    private fun toBitList(codewords: IntArray): BooleanArray {
        val result = BooleanArray(codewords.size * 8)
        var pos = 0
        for (cw in codewords) {
            for (bit in 7 downTo 0) {
                result[pos++] = ((cw ushr bit) and 1) != 0
            }
        }
        return result
    }

    private fun placeFunctionPatterns(
        matrix: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        spec: VersionSpec,
    ) {
        val size = spec.size

        fun setModule(r: Int, c: Int, dark: Boolean) {
            if (r in 0 until size && c in 0 until size) {
                matrix[r][c] = dark
                isFunction[r][c] = true
            }
        }

        // Finder patterns + 1-module white separators
        fun drawFinder(top: Int, left: Int) {
            for (dr in -1..7) {
                for (dc in -1..7) {
                    val r = top + dr
                    val c = left + dc
                    if (r !in 0 until size || c !in 0 until size) continue
                    val inOuter = dr in 0..6 && dc in 0..6
                    val dark = inOuter && (
                        dr == 0 || dr == 6 || dc == 0 || dc == 6 ||
                            (dr in 2..4 && dc in 2..4)
                        )
                    setModule(r, c, dark)
                }
            }
        }

        drawFinder(0, 0)
        drawFinder(0, size - 7)
        drawFinder(size - 7, 0)

        // Timing patterns
        for (i in 8 until size - 8) {
            setModule(6, i, i % 2 == 0)
            setModule(i, 6, i % 2 == 0)
        }

        // Alignment patterns (versions 2..6)
        val centers = spec.alignmentCenters
        for (rCenter in centers) {
            for (cCenter in centers) {
                val overlapsFinder =
                    (rCenter == 6 && cCenter == 6) ||
                        (rCenter == 6 && cCenter == size - 7) ||
                        (rCenter == size - 7 && cCenter == 6)
                if (overlapsFinder) continue
                for (dr in -2..2) {
                    for (dc in -2..2) {
                        val dist = maxOf(kotlin.math.abs(dr), kotlin.math.abs(dc))
                        setModule(rCenter + dr, cCenter + dc, dist != 1)
                    }
                }
            }
        }

        // Reserve format info modules
        for (i in 0..8) {
            if (i != 6) {
                setModule(8, i, false)
                setModule(i, 8, false)
            }
        }
        for (i in size - 8 until size) {
            setModule(8, i, false)
            setModule(i, 8, false)
        }

        // Dark module
        setModule(4 * spec.version + 9, 8, true)
    }

    private fun placeDataBits(
        matrix: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        bits: BooleanArray,
    ) {
        val size = matrix.size
        var bitIndex = 0
        var upward = true
        var col = size - 1

        while (col > 0) {
            if (col == 6) col-- // Skip vertical timing column
            for (step in 0 until size) {
                val row = if (upward) size - 1 - step else step
                for (dx in 0..1) {
                    val c = col - dx
                    if (!isFunction[row][c]) {
                        matrix[row][c] = if (bitIndex < bits.size) bits[bitIndex++] else false
                    }
                }
            }
            upward = !upward
            col -= 2
        }
    }

    private fun applyMask(
        matrix: Array<BooleanArray>,
        isFunction: Array<BooleanArray>,
        mask: Int,
    ) {
        val size = matrix.size
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (isFunction[r][c]) continue
                val invert = when (mask) {
                    0 -> (r + c) % 2 == 0
                    1 -> r % 2 == 0
                    2 -> c % 3 == 0
                    3 -> (r + c) % 3 == 0
                    4 -> ((r / 2) + (c / 3)) % 2 == 0
                    5 -> ((r * c) % 2) + ((r * c) % 3) == 0
                    6 -> ((((r * c) % 2) + ((r * c) % 3)) % 2) == 0
                    7 -> ((((r + c) % 2) + ((r * c) % 3)) % 2) == 0
                    else -> false
                }
                if (invert) {
                    matrix[r][c] = !matrix[r][c]
                }
            }
        }
    }

    private fun drawFormatBits(matrix: Array<BooleanArray>, mask: Int) {
        val size = matrix.size
        // Error correction level L = 01
        val data = (0b01 shl 3) or mask
        var rem = data
        for (i in 0 until 10) {
            rem = (rem shl 1) xor (((rem ushr 9) * 0x537))
        }
        val bits = ((data shl 10) or rem) xor 0x5412

        // First copy (around top-left finder)
        for (i in 0..5) matrix[8][i] = ((bits ushr (14 - i)) and 1) != 0
        matrix[8][7] = ((bits ushr 8) and 1) != 0
        matrix[8][8] = ((bits ushr 7) and 1) != 0
        matrix[7][8] = ((bits ushr 6) and 1) != 0
        for (i in 0..5) matrix[5 - i][8] = ((bits ushr (5 - i)) and 1) != 0

        // Second copy (top-right and bottom-left)
        for (i in 0..7) matrix[8][size - 1 - i] = ((bits ushr i) and 1) != 0
        for (i in 0..6) matrix[size - 7 + i][8] = ((bits ushr (14 - i)) and 1) != 0
    }

    private fun calculatePenalty(matrix: Array<BooleanArray>): Int {
        val size = matrix.size
        var penalty = 0

        // Rule 1: 5+ same-color modules in a row/col
        for (r in 0 until size) {
            var runColor = matrix[r][0]
            var runLen = 1
            for (c in 1 until size) {
                if (matrix[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = matrix[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }
        for (c in 0 until size) {
            var runColor = matrix[0][c]
            var runLen = 1
            for (r in 1 until size) {
                if (matrix[r][c] == runColor) {
                    runLen++
                } else {
                    if (runLen >= 5) penalty += 3 + (runLen - 5)
                    runColor = matrix[r][c]
                    runLen = 1
                }
            }
            if (runLen >= 5) penalty += 3 + (runLen - 5)
        }

        // Rule 2: 2x2 blocks of same color
        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                val color = matrix[r][c]
                if (matrix[r][c + 1] == color && matrix[r + 1][c] == color && matrix[r + 1][c + 1] == color) {
                    penalty += 3
                }
            }
        }

        // Rule 4: Balance of dark modules
        var darkCount = 0
        for (r in 0 until size) {
            for (c in 0 until size) {
                if (matrix[r][c]) darkCount++
            }
        }
        val total = size * size
        val percentDiff = kotlin.math.abs(darkCount * 100 / total - 50)
        penalty += (percentDiff / 5) * 10

        return penalty
    }
}
