package com.starfarer.companionoverlay

import com.starfarer.companionoverlay.api.ClaudeBilling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for Claude billing header computation.
 */
class ClaudeBillingTest {

    @Test
    fun `computeHeader returns valid format`() {
        val header = ClaudeBilling.computeHeader("Hello, how are you today?")
        
        assertTrue("Header should start with x-anthropic-billing-header:", 
            header.startsWith("x-anthropic-billing-header:"))
        assertTrue("Header should contain cc_version", header.contains("cc_version="))
        assertTrue("Header should contain cc_entrypoint=cli", header.contains("cc_entrypoint=cli"))
        assertTrue("Header should contain cch=", header.contains("cch="))
    }

    @Test
    fun `computeHeader handles short text`() {
        val header = ClaudeBilling.computeHeader("Hi")
        
        // Should not crash, should use '0' for missing positions
        assertTrue(header.startsWith("x-anthropic-billing-header:"))
    }

    @Test
    fun `computeHeader handles empty text`() {
        val header = ClaudeBilling.computeHeader("")
        
        // Should not crash, should use '0' for all positions
        assertTrue(header.startsWith("x-anthropic-billing-header:"))
    }

    @Test
    fun `computeHeader produces consistent output for same input`() {
        val text = "Test message for billing"
        val header1 = ClaudeBilling.computeHeader(text)
        val header2 = ClaudeBilling.computeHeader(text)
        
        assertEquals("Same input should produce same output", header1, header2)
    }

    @Test
    fun `computeHeader produces different output for different input`() {
        val header1 = ClaudeBilling.computeHeader("First message here")
        val header2 = ClaudeBilling.computeHeader("Second message here")
        
        // The hash portion should differ (positions 4, 7, 20 are different)
        assertTrue("Different input should produce different output", header1 != header2)
    }
}
