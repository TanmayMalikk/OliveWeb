package com.olive.app.data.account

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpaqueBridgeScriptsTest {
    @Test
    fun `every OPAQUE operation passes its arguments in order`() {
        val cases = listOf(
            OpaqueBridgeMethod.START_REGISTRATION to listOf("register-password"),
            OpaqueBridgeMethod.FINISH_REGISTRATION to listOf("register-state", "register-response", "register-password"),
            OpaqueBridgeMethod.START_LOGIN to listOf("login-password"),
            OpaqueBridgeMethod.FINISH_LOGIN to listOf("login-state", "login-response", "login-password"),
        )

        cases.forEachIndexed { index, (method, arguments) ->
            val script = OpaqueBridgeScripts.invoke("request-$index", method, arguments)

            assertTrue(script.contains("window.opaqueBridge.${method.functionName}("))
            assertTrue(script.contains(arguments.joinToString(",") { JSONObject.quote(it) }))
            assertTrue(script.contains("Promise.resolve"))
        }
    }

    @Test
    fun `arguments remain quoted across broad hostile input classes`() {
        val values = listOf(
            "ordinary-password",
            "quote-\"-apostrophe-'",
            "line-one\nline-two\r\nline-three",
            "backslash-\\-path-C:\\vault",
            "unicode-密码-🔐-é",
            "</script><script>window.compromised=true</script>",
            "');window.compromised=true;//",
            "",
        )

        repeat(25) { repetition ->
            val arguments = values.map { "$repetition:$it" }
            val script = OpaqueBridgeScripts.invoke(
                requestId = "request-$repetition",
                method = OpaqueBridgeMethod.FINISH_LOGIN,
                arguments = arguments,
            )

            arguments.forEach { assertTrue(script.contains(JSONObject.quote(it))) }
            assertFalse(script.contains("window.opaqueBridge.finishLogin(, , )"))
        }
    }

    @Test
    fun `result polling distinguishes pending success and rejection`() {
        assertEquals(OpaqueBridgeResult.Pending, OpaqueBridgeScripts.decodeResult("null"))
        assertEquals(OpaqueBridgeResult.Pending, OpaqueBridgeScripts.decodeResult(JSONObject.quote("null")))

        val payload = "{\"clientLoginState\":\"state\",\"startLoginRequest\":\"request\"}"
        val success = JSONObject()
            .put("ok", true)
            .put("value", payload)
            .toString()
        assertEquals(
            OpaqueBridgeResult.Success(payload),
            OpaqueBridgeScripts.decodeResult(JSONObject.quote(success)),
        )

        val failure = JSONObject().put("ok", false).toString()
        assertEquals(
            OpaqueBridgeResult.Failure,
            OpaqueBridgeScripts.decodeResult(JSONObject.quote(failure)),
        )
    }

    @Test
    fun `request scripts isolate concurrent operation results and cleanup`() {
        val first = OpaqueBridgeScripts.invoke(
            "request-a",
            OpaqueBridgeMethod.START_LOGIN,
            listOf("same-password"),
        )
        val second = OpaqueBridgeScripts.invoke(
            "request-b",
            OpaqueBridgeMethod.START_LOGIN,
            listOf("same-password"),
        )

        assertNotEquals(first, second)
        assertTrue(first.contains(JSONObject.quote("request-a")))
        assertTrue(second.contains(JSONObject.quote("request-b")))
        assertTrue(OpaqueBridgeScripts.read("request-a").contains(JSONObject.quote("request-a")))
        assertTrue(OpaqueBridgeScripts.clear("request-a").contains(JSONObject.quote("request-a")))
    }

    @Test
    fun `awaiter tolerates repeated pending responses before success`() = runBlocking {
        repeat(50) { repetition ->
            var reads = 0
            val payload = "{\"clientLoginState\":\"state-$repetition\",\"startLoginRequest\":\"request\"}"
            val encodedSuccess = JSONObject.quote(
                JSONObject().put("ok", true).put("value", payload).toString(),
            )

            val result = awaitOpaqueBridgePayload(timeoutMillis = 1_000, pollMillis = 1) {
                reads++
                if (reads < 5) JSONObject.quote("null") else encodedSuccess
            }

            assertEquals("state-$repetition", result.getString("clientLoginState"))
            assertEquals(5, reads)
        }
    }

    @Test
    fun `awaiter returns bounded failures for rejection and timeout`() {
        val rejected = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                awaitOpaqueBridgePayload(timeoutMillis = 1_000, pollMillis = 1) {
                    JSONObject.quote(JSONObject().put("ok", false).toString())
                }
            }
        }
        assertEquals("OPAQUE client operation failed", rejected.message)

        val timedOut = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                awaitOpaqueBridgePayload(timeoutMillis = 25, pollMillis = 1) {
                    JSONObject.quote("null")
                }
            }
        }
        assertEquals("OPAQUE client operation timed out", timedOut.message)
    }
}
