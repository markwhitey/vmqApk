package com.vone.vmq;

import org.junit.Test;
import static org.junit.Assert.*;
import okhttp3.Request;

public class MonitorProtocolTest {
    @Test public void matchesDeployedServerGoldenVectors() {
        assertEquals("71ec70d2383ec10bdb2fe39f42f60721a1808fa31884deebc9b8c88b5b527bbe",
                MonitorProtocol.heartbeatSign("1773500000000", "testkey123456"));
        assertEquals("61174e2503aec9c2ffe8430ba322d03b8e3c5f46c3d08f29e69ee35d0b34a51e",
                MonitorProtocol.pushSign(1, "1.00", "1773500000000", "testkey123456"));
    }
    @Test public void usesCurrentMonitorEndpointsAndHttpsByDefault() {
        Request request = MonitorProtocol.heartbeat("vmq.example.com", "test-key", "1773500000000");
        assertEquals("https", request.url().scheme());
        assertEquals("/api/monitor/heart", request.url().encodedPath());
        assertEquals(64, request.url().queryParameter("sign").length());
    }
    @Test public void paymentUsesExactPriceTextAndSignedTimestamp() {
        Request request = MonitorProtocol.payment("https://vmq.example.com", "test-key", 2, 40.0, "1773500000000");
        assertEquals("40.00", request.url().queryParameter("price"));
        assertEquals("/api/monitor/push", request.url().encodedPath());
        assertEquals(MonitorProtocol.pushSign(2, "40.00", "1773500000000", "test-key"), request.url().queryParameter("sign"));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsServerAddressWithPath() {
        MonitorProtocol.origin("https://vmq.example.com/admin");
    }
}
