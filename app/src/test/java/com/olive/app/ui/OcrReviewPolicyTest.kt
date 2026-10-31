// Tests the retake or save choice for low-confidence user scans.
package com.olive.app.ui

import com.olive.app.ui.users.shared.camera.isLowOcrConfidence

import com.olive.app.ocr.OcrResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrReviewPolicyTest {
    // Inputs: the values shown below. Expected: confidence below seventy five always offers low confidence choice.
    @Test
    fun confidenceBelowSeventyFiveAlwaysOffersLowConfidenceChoice() {
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", .749f)))
        assertTrue(isLowOcrConfidence(OcrResult(false, "failed", 0f)))
        assertFalse(isLowOcrConfidence(OcrResult(true, "text", .75f)))
    }

    // Inputs: the values shown below. Expected: non finite and out of range confidence cannot pass review.
    @Test
    fun nonFiniteAndOutOfRangeConfidenceCannotPassReview() {
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", Float.NaN)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", Float.NEGATIVE_INFINITY)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", -0.1f)))
        assertTrue(isLowOcrConfidence(OcrResult(true, "text", 1.1f)))
    }
}
