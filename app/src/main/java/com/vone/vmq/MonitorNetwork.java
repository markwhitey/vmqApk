package com.vone.vmq;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONException;
import org.json.JSONObject;

final class MonitorNetwork {
    static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).build();

    static Result read(Response response) throws IOException {
        try (Response ignored = response) {
            String body = response.body() == null ? "" : response.body().string();
            try {
                JSONObject json = new JSONObject(body);
                int code = json.optInt("code", -1);
                boolean success = response.isSuccessful() && code == 200;
                String message = json.optString("msg", json.optString("message", ""));
                return new Result(success, response.code(), code, message);
            } catch (JSONException e) {
                return new Result(false, response.code(), -1, "服务器返回格式错误");
            }
        }
    }

    static final class Result {
        final boolean success;
        final int httpCode;
        final int code;
        final String message;
        Result(boolean success, int httpCode, int code, String message) {
            this.success = success; this.httpCode = httpCode; this.code = code; this.message = message;
        }
        boolean retryable() { return httpCode == 429 || httpCode >= 500; }
        String summary() { return "HTTP " + httpCode + " / code=" + code + " " + message; }
    }
}
