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

package com.paddle.ocr

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.engine.OCREngine
import com.paddle.ocr.engine.OCREngineResult
import com.paddle.ocr.model.OCRError
import com.paddle.ocr.model.OCRRunResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Groups the data and actions for paddle ocr.
class PaddleOCR private constructor(
    private val engine: OCREngine,
) {
    /** Time spent loading ONNX models (milliseconds). */
    val coldLoadTimeMs: Long get() = engine.coldLoadTimeMs

    companion object {

        /**
         * Creates the paddle OCR.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @return The resulting [PaddleOCR] instance.
         */
        suspend fun create(context: Context): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(appContext, PaddleOCRConfig(), EngineConfig())
                PaddleOCR(engine)
            }
        }

        /**
         * Creates the paddle OCR.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param config Configuration options governing behavior and thresholds.
         * @param engineConfig ONNX Runtime execution configuration options.
         * @return The resulting [PaddleOCR] instance.
         */
        suspend fun create(
            context: Context,
            config: PaddleOCRConfig,
            engineConfig: EngineConfig = EngineConfig(),
        ): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(appContext, config, engineConfig)
                PaddleOCR(engine)
            }
        }

        /**
         * Creates the paddle OCR.
         *
         * @param context Android context used to access system services, resources, or storage.
         * @param config Configuration options governing behavior and thresholds.
         * @param engineConfig ONNX Runtime execution configuration options.
         * @param detModelAssetPath Text detection model asset path supplied to [create].
         * @param recModelAssetPath Text recognition model asset path supplied to [create].
         * @param recConfigAssetPath Text recognition config asset path supplied to [create].
         * @return The resulting [PaddleOCR] instance.
         */
        suspend fun create(
            context: Context,
            config: PaddleOCRConfig,
            engineConfig: EngineConfig,
            detModelAssetPath: String,
            recModelAssetPath: String,
            recConfigAssetPath: String,
        ): PaddleOCR {
            val appContext = context.applicationContext
            return withContext(Dispatchers.IO) {
                val engine = OCREngine(
                    appContext, config, engineConfig,
                    detModelAsset = detModelAssetPath,
                    recModelAsset = recModelAssetPath,
                    recConfigAsset = recConfigAssetPath,
                )
                PaddleOCR(engine)
            }
        }
    }

    /**
     * Runs OCR text recognition on the paddle OCR.
     *
     * @param bitmap Bitmap image to process or render.
     * @return The resulting [OCRRunResult] instance.
     */
    suspend fun recognize(bitmap: Bitmap): OCRRunResult {
        if (bitmap.width == 0 || bitmap.height == 0) {
            throw OCRError.InvalidImage()
        }
        return recognizeResult { engine.run(bitmap) }
    }

    /**
     * Runs OCR text recognition on the paddle OCR.
     *
     * @param imageBytes Raw image bytes byte array.
     * @return The resulting [OCRRunResult] instance.
     */
    suspend fun recognize(imageBytes: ByteArray): OCRRunResult {
        if (imageBytes.isEmpty()) {
            throw OCRError.InvalidImage()
        }
        return recognizeResult { engine.run(imageBytes) }
    }

    /**
     * Runs OCR text recognition on result.
     *
     * @param runEngine Function used to transform or provide run engine.
     * @return The resulting [OCRRunResult] for recognize result.
     */
    private suspend fun recognizeResult(runEngine: () -> OCREngineResult): OCRRunResult {
        return withContext(Dispatchers.IO) {
            val result = runEngine()
            OCRRunResult(
                results = result.results,
                detectionTimeMs = result.detectionTimeMs,
                recognitionTimeMs = result.recognitionTimeMs,
                totalTimeMs = result.totalTimeMs,
                lineCount = result.lineCount,
                detPreprocessMs = result.detPreprocessMs,
                detInferenceMs = result.detInferenceMs,
                detPostprocessMs = result.detPostprocessMs,
                recPreprocessMs = result.recPreprocessMs,
                recInferenceMs = result.recInferenceMs,
                recPostprocessMs = result.recPostprocessMs,
                pipelineOverheadMs = result.pipelineOverheadMs,
                coldLoadTimeMs = result.coldLoadTimeMs,
                detInputShape = result.detInputShape,
                recInputShapes = result.recInputShapes,
                perLineRecMs = result.perLineRecMs,
            )
        }
    }

    /**
     * Computes release.
     */
    suspend fun release() {
        withContext(Dispatchers.IO) {
            engine.release()
        }
    }
}
