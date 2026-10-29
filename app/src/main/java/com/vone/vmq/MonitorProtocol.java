package com.vone.vmq;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** VMQFox v2 signatures. A payment retry must reuse the original Request/timestamp. */
final class MonitorProtocol {
    private MonitorProtocol() {}

    static String sign(String canonical, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder result = new StringBuilder();
            for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
            }
            return result.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    static String heartbeatSign(String timestamp, String key) {
        return sign("t=" + timestamp, key);
    }

    static String pushSign(int type, String price, String timestamp, String key) {
        return sign("type=" + type + "&price=" + price + "&t=" + timestamp, key);
    }

    static String price(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }

    static HttpUrl origin(String host) {
        String value = host.trim();
        // Legacy QR configuration contains only host[:port]. Prefer encrypted transport.
        if (!value.contains("://")) value = "https://" + value;
        HttpUrl url = HttpUrl.parse(value);
        if (url == null || !url.username().isEmpty() || !url.password().isEmpty()
                || !url.encodedPath().equals("/") || url.query() != null || url.fragment() != null) {
            throw new IllegalArgumentException("服务地址应为域名或 http(s)://域名，不含路径");
        }
        return url;
    }

    static Request heartbeat(String host, String key, String timestamp) {
        HttpUrl url = origin(host).newBuilder().addPathSegments("api/monitor/heart")
                .addQueryParameter("t", timestamp)
                .addQueryParameter("sign", heartbeatSign(timestamp, key)).build();
        return new Request.Builder().url(url).get().build();
    }

    static Request payment(String host, String key, int type, double amount, String timestamp) {
        if (type != 1 && type != 2) throw new IllegalArgumentException("支付方式错误");
        String price = price(amount);
        HttpUrl url = origin(host).newBuilder().addPathSegments("api/monitor/push")
                .addQueryParameter("t", timestamp).addQueryParameter("type", Integer.toString(type))
                .addQueryParameter("price", price)
                .addQueryParameter("sign", pushSign(type, price, timestamp, key)).build();
        return new Request.Builder().url(url).get().build();
    }
}
