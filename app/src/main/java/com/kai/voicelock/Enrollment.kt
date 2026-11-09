package com.kai.voicelock

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Stores 3 MFCC templates of the enrolled phrase + the auto-calibrated threshold.
 * Everything is on-device; no upload, no model download.
 */
object Enrollment {

    class Model(
        val templates: List<Array<DoubleArray>>,
        val threshold: Double,
        val pitchMean: Double,
        val pitchStd: Double
    )

    private const val FILE = "enrollment.bin"
    private const val MAGIC = 0x564C4B32.toInt() // "VLK2"
    private const val VERSION = 1
    private const val DIM = Dsp.CEPSTRA

    fun exists(ctx: Context): Boolean = File(ctx.filesDir, FILE).isFile

    fun save(ctx: Context, model: Model) {
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp))).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(model.templates.size)
            for (template in model.templates) {
                out.writeInt(template.size)
                out.writeInt(DIM)
                for (frame in template) {
                    for (v in frame) out.writeDouble(v)
                }
            }
            out.writeDouble(model.threshold)
            out.writeDouble(model.pitchMean)
            out.writeDouble(model.pitchStd)
        }
        val target = File(ctx.filesDir, FILE)
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(ctx: Context): Model? = runCatching {
        val file = File(ctx.filesDir, FILE)
        if (!file.isFile) return null
        DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
            require(input.readInt() == MAGIC)
            require(input.readInt() == VERSION)
            val count = input.readInt()
            require(count in 1..8)

            val templates = ArrayList<Array<DoubleArray>>(count)
            repeat(count) {
                val frames = input.readInt()
                val dim = input.readInt()
                require(frames in 1..2000 && dim == DIM)
                templates.add(Array(frames) { DoubleArray(dim) { input.readDouble() } })
            }
            val threshold = input.readDouble()
            val pitchMean = input.readDouble()
            val pitchStd = input.readDouble()
            Model(templates, threshold, pitchMean, pitchStd)
        }
    }.getOrNull()

    fun clear(ctx: Context) {
        File(ctx.filesDir, FILE).delete()
    }
}
