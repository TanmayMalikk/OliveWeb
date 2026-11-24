// Copyright (c) 2026 PaddlePaddle Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.paddle.ocr.util

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

// Groups the data and actions for bitmap utils.
object BitmapUtils {

    /**
     * Computes imdecode BGR.
     *
     * @param imageBytes Raw image bytes byte array.
     * @return The resulting [Mat] for imdecode BGR.
     */
    fun imdecodeBGR(imageBytes: ByteArray): Mat {
        val encoded = MatOfByte(*imageBytes)
        return try {
            Imgcodecs.imdecode(encoded, Imgcodecs.IMREAD_COLOR)
        } finally {
            encoded.release()
        }
    }

    /**
     * Computes bitmap to BGR mat.
     *
     * @param bitmap Bitmap image to process or render.
     * @return The resulting [Mat] for bitmap to BGR mat.
     */
    fun bitmapToBGRMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2BGR)
    }

    /**
     * Computes bitmap to RGB mat.
     *
     * @param bitmap Bitmap image to process or render.
     * @return The resulting [Mat] for bitmap to RGB mat.
     */
    fun bitmapToRGBMat(bitmap: Bitmap): Mat {
        return bitmapToMat(bitmap, Imgproc.COLOR_RGBA2RGB)
    }

    /**
     * Computes BGR mat to bitmap.
     *
     * @param mat Mat supplied to [bgrMatToBitmap].
     * @return The resulting [Bitmap] for BGR mat to bitmap.
     */
    fun bgrMatToBitmap(mat: Mat): Bitmap {
        val rgba = Mat()
        return try {
            Imgproc.cvtColor(mat, rgba, Imgproc.COLOR_BGR2RGBA)
            Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also { bitmap ->
                Utils.matToBitmap(rgba, bitmap)
            }
        } finally {
            rgba.release()
        }
    }

    /**
     * Computes bitmap to mat.
     *
     * @param bitmap Bitmap image to process or render.
     * @param colorConversionCode Color conversion code supplied to [bitmapToMat].
     * @return The resulting [Mat] for bitmap to mat.
     */
    private fun bitmapToMat(bitmap: Bitmap, colorConversionCode: Int): Mat {
        val bmp = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat(bmp.height, bmp.width, CvType.CV_8UC4)
        val dst = Mat()
        return try {
            Utils.bitmapToMat(bmp, rgba)
            Imgproc.cvtColor(rgba, dst, colorConversionCode)
            dst
        } catch (t: Throwable) {
            dst.release()
            throw t
        } finally {
            bmp.recycle()
            rgba.release()
        }
    }
}
