package com.slash.agent;

import static org.junit.Assert.*;
import org.junit.Test;

public final class SafeAgentLogTest {
    @Test public void credentialsAreRedacted() {
        String output = SafeAgentLog.redact("Authorization: Bearer secret-token api_key=private sk-or-v1-example123");
        assertFalse(output.contains("secret-token"));
        assertFalse(output.contains("private"));
        assertFalse(output.contains("sk-or-v1-example123"));
        assertTrue(output.contains("REDACTED"));
    }
}
