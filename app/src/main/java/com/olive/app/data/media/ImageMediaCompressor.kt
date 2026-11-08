// Safe-app roles: performs bounded still-image normalization before encryption.
package com.olive.app.data.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Codec-only image compressor with no Firebase, WorkManager, R2, UI, or encryption dependency. */
internal class ImageMediaCompressor {
    data class Result(val file:File,val mimeType:String,val fileExtension:String)

    fun compress(source:File,outputFolder:File,declaredMimeType:String):Result {
        require(ImageCompressionPolicy.isSupportedStillMime(declaredMimeType)) {
            "Declared image type is unsupported"
        }
        val detectedMime=requireNotNull(ImageCompressionPolicy.detectMimeType(source)) {
            "Declared image type does not match a supported still image"
        }
        if(detectedMime=="image/webp" && ImageCompressionPolicy.isAnimatedWebp(source)) {
            return Result(source,detectedMime,ImageCompressionPolicy.extensionFor(detectedMime))
        }
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(source.absolutePath,bounds)
        require(bounds.outWidth>0 && bounds.outHeight>0) { "Image could not be decoded" }
        require(bounds.outWidth<=MAX_SOURCE_DIMENSION && bounds.outHeight<=MAX_SOURCE_DIMENSION) {
            "Image dimensions are unsupported"
        }
        val actualMime=ImageCompressionPolicy.normalizedMimeType(bounds.outMimeType ?: detectedMime)
        require(ImageCompressionPolicy.isSupportedStillMime(actualMime)) {
            "Declared image type does not match a supported still image"
        }
        require(actualMime==detectedMime || setOf(actualMime,detectedMime)==setOf("image/heic","image/heif")) {
            "Image signature and decoder type do not match"
        }
        val originalExtension=ImageCompressionPolicy.extensionFor(actualMime)
        val options=BitmapFactory.Options().apply {
            inSampleSize=ImageCompressionPolicy.sampleSize(bounds.outWidth,bounds.outHeight)
            inPreferredConfig=Bitmap.Config.ARGB_8888
        }
        var bitmap=BitmapFactory.decodeFile(source.absolutePath,options)
            ?: error("Image could not be decoded")
        var compressed:File?=null
        try {
            compressed=File.createTempFile(OUTPUT_PREFIX,".tmp",outputFolder)
            bitmap=replace(bitmap,applyOrientation(bitmap,source))
            val target=ImageCompressionPolicy.targetSize(bitmap.width,bitmap.height)
            if(target.first!=bitmap.width || target.second!=bitmap.height) {
                bitmap=replace(bitmap,Bitmap.createScaledBitmap(bitmap,target.first,target.second,true))
            }
            val preserveAlpha=actualMime=="image/png" || (bitmap.hasAlpha() && actualMime!="image/jpeg")
            val outputMime=if(preserveAlpha) "image/png" else "image/jpeg"
            val outputExtension=ImageCompressionPolicy.extensionFor(outputMime)
            compressed.outputStream().buffered().use { output ->
                val format=if(preserveAlpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                check(bitmap.compress(format,JPEG_QUALITY,output)) { "Image compression failed" }
            }
            require(compressed.length()>0) { "Image compression produced an empty result" }
            return if(compressed.length()<source.length()) {
                Result(compressed,outputMime,outputExtension)
            } else {
                compressed.delete()
                Result(source,actualMime,originalExtension)
            }
        } catch(failure:Throwable) {
            compressed?.delete()
            throw failure
        } finally {
            if(!bitmap.isRecycled) bitmap.recycle()
        }
    }

    private fun applyOrientation(bitmap:Bitmap,source:File):Bitmap {
        val orientation=runCatching {
            ExifInterface(source.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix=Matrix()
        when(orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f,1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f,-1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f);matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f);matrix.postScale(-1f,1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true)
    }

    private fun replace(previous:Bitmap,next:Bitmap):Bitmap {
        if(previous!==next && !previous.isRecycled) previous.recycle()
        return next
    }

    private companion object {
        const val OUTPUT_PREFIX="olive-image-output-"
        const val MAX_SOURCE_DIMENSION=65_535
        const val JPEG_QUALITY=85
    }
}

/** Pure image policy kept separate from Android codecs for broad JVM stress testing. */
internal object ImageCompressionPolicy {
    private val eligibleMimeTypes=setOf("image/jpeg","image/png","image/webp","image/heic","image/heif")

    fun normalizedMimeType(value:String)=when(val normalized=value.substringBefore(';').trim().lowercase()) {
        "image/jpg","image/pjpeg" -> "image/jpeg"
        "image/x-png" -> "image/png"
        else -> normalized
    }

    fun isSupportedStillMime(mimeType:String)=normalizedMimeType(mimeType) in eligibleMimeTypes

    fun detectMimeType(file:File):String? {
        val header=readHeader(file,32)
        if(header.size>=3 && header[0]==0xff.toByte() && header[1]==0xd8.toByte() &&
            header[2]==0xff.toByte()) return "image/jpeg"
        val png=byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a)
        if(header.size>=png.size && png.indices.all { header[it]==png[it] }) return "image/png"
        if(header.size>=12 && ascii(header,0,4)=="RIFF" && ascii(header,8,4)=="WEBP") return "image/webp"
        if(header.size>=12 && ascii(header,4,4)=="ftyp") return when(ascii(header,8,4)) {
            "heic","heix","hevc","hevx" -> "image/heic"
            "mif1" -> "image/heif"
            else -> null
        }
        return null
    }

    fun isAnimatedWebp(file:File):Boolean {
        if(detectMimeType(file)!="image/webp") return false
        val header=readHeader(file,64)
        if(header.size>=21 && ascii(header,12,4)=="VP8X" && (header[20].toInt() and 0x02)!=0) {
            return true
        }
        return (12..(header.size-4).coerceAtLeast(11)).any { ascii(header,it,4)=="ANIM" }
    }

    fun isEligible(mimeType:String,kind:MediaKind)=
        kind in setOf(MediaKind.DOCUMENT,MediaKind.IMAGE) && isSupportedStillMime(mimeType)

    fun extensionFor(mimeType:String)=when(normalizedMimeType(mimeType)) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "img"
    }

    fun targetSize(width:Int,height:Int,maxDimension:Int=2_048):Pair<Int,Int> {
        require(width>0 && height>0 && maxDimension>0)
        val largest=max(width,height)
        if(largest<=maxDimension) return width to height
        val scale=maxDimension.toDouble()/largest
        return maxOf(1,(width*scale).roundToInt()) to maxOf(1,(height*scale).roundToInt())
    }

    fun sampleSize(width:Int,height:Int,maxDecodeDimension:Int=4_096):Int {
        require(width>0 && height>0 && maxDecodeDimension>0)
        var sample=1
        while(max(width,height)/sample>maxDecodeDimension && sample<=Int.MAX_VALUE/2) sample*=2
        return sample
    }

    private fun readHeader(file:File,size:Int):ByteArray {
        val buffer=ByteArray(size)
        val count=file.inputStream().use { input ->
            var total=0
            while(total<buffer.size) {
                val read=input.read(buffer,total,buffer.size-total)
                if(read<0) break
                if(read>0) total+=read
            }
            total
        }
        return buffer.copyOf(count)
    }

    private fun ascii(bytes:ByteArray,start:Int,length:Int)=String(bytes,start,length,Charsets.US_ASCII)
}
