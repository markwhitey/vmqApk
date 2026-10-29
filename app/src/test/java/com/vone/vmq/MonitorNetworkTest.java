package com.vone.vmq;

import java.io.IOException;
import okhttp3.Request;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class MonitorNetworkTest {
    private MockWebServer server;
    @Before public void openServer() throws IOException { server = new MockWebServer(); server.start(); }
    @After public void closeServer() throws IOException { server.shutdown(); }
    private MonitorNetwork.Result result(String body, int http) throws IOException {
        server.enqueue(new MockResponse().setResponseCode(http).setBody(body));
        return MonitorNetwork.read(MonitorNetwork.CLIENT.newCall(new Request.Builder().url(server.url("/")).build()).execute());
    }
    @Test public void http200DoesNotMeanHeartbeatAccepted() throws IOException {
        MonitorNetwork.Result result = result("{\"code\":400,\"msg\":\"签名错误\"}", 200);
        assertFalse(result.success); assertFalse(result.retryable()); assertEquals(400, result.code);
    }
    @Test public void acceptsBusinessSuccessOnly() throws IOException {
        assertTrue(result("{\"code\":200,\"msg\":\"success\"}", 200).success);
        assertFalse(result("{\"code\":200}", 500).success);
        assertFalse(result("<html>upstream error</html>", 200).success);
    }
    @Test public void paymentRetryPreservesServerDedupeIdentity() throws Exception {
        Request request = MonitorProtocol.payment(server.url("/").toString(), "test-key", 1, 40, "1773500000000");
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"code\":503}"));
        server.enqueue(new MockResponse().setBody("{\"code\":200}"));
        assertTrue(MonitorNetwork.read(MonitorNetwork.CLIENT.newCall(request).execute()).retryable());
        assertTrue(MonitorNetwork.read(MonitorNetwork.CLIENT.newCall(request).execute()).success);
        assertEquals(server.takeRequest().getPath(), server.takeRequest().getPath());
    }
}
