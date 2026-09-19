package com.kingzcheung.xime.plugin.core.lua.ws

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPolicyTest {

    private val trusted = setOf("dashscope.aliyuncs.com")

    @Test
    fun `extractHost parses various urls`() {
        assertEquals("dashscope.aliyuncs.com", NetworkPolicy.extractHost("wss://dashscope.aliyuncs.com/api-ws/v1/inference/"))
        assertEquals("asr.example.com", NetworkPolicy.extractHost("wss://asr.example.com:443/ws"))
        assertEquals("openai.com", NetworkPolicy.extractHost("wss://openai.com?query=1"))
        assertEquals("host", NetworkPolicy.extractHost("ws://host"))
        assertNull(NetworkPolicy.extractHost(""))
        assertNull(NetworkPolicy.extractHost("http://"))
    }

    @Test
    fun `trusted host passes silently`() {
        assertNull(
            NetworkPolicy.check(
                "wss://dashscope.aliyuncs.com/ws",
                trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
            )
        )
    }

    @Test
    fun `declared and authorized host passes`() {
        assertNull(
            NetworkPolicy.check(
                "wss://asr.example.com/ws",
                trusted,
                declaredHosts = listOf("asr.example.com"),
                authorizedHosts = setOf("asr.example.com")
            )
        )
    }

    @Test
    fun `undeclared host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://evil.example.com/ws",
            trusted, declaredHosts = emptyList(), authorizedHosts = emptySet()
        )
        assertTrue("未声明域名应拒绝", reason != null)
    }

    @Test
    fun `declared but unauthorized host rejected`() {
        val reason = NetworkPolicy.check(
            "wss://asr.example.com/ws",
            trusted,
            declaredHosts = listOf("asr.example.com"),
            authorizedHosts = emptySet()
        )
        assertTrue("已声明但未授权应拒绝", reason != null)
        assertTrue("拒绝原因应含授权提示", reason!!.contains("授权"))
    }
}
