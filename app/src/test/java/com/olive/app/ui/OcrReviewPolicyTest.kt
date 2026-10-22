package com.olive.app.ui

import com.olive.app.ocr.OcrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrReviewPolicyTest {
    @Test fun confidenceBelowSeventyFiveAlwaysOffersLowConfidenceChoice() {
        assertTrue(isLowOcrConfidence(OcrResult(true,"text",.749f)))
        assertTrue(isLowOcrConfidence(OcrResult(false,"failed",0f)))
        assertFalse(isLowOcrConfidence(OcrResult(true,"text",.75f)))
    }
}
