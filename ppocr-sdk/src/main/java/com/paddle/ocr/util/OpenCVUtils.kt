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

import android.content.Context
import android.util.Log
import org.opencv.android.OpenCVLoader

// Groups the data and actions for open cvutils.
object OpenCVUtils {

    private var initialized = false

    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            initialized = OpenCVLoader.initDebug()
            if (!initialized) {
                System.loadLibrary("opencv_java")
                initialized = true
            }
        } catch (e: Exception) {
            Log.e("OpenCVUtils", "Failed to initialize OpenCV 5.0.0.1: ${e.message}")
        }
        return initialized
    }
}
