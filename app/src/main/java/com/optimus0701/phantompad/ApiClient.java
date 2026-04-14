package com.optimus0701.phantompad;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Modern HTTP API client for backend communication using OkHttp3.
 */
public class ApiClient {
    private static final String TAG = "ApiClient";
    // Change this to your production server URL
    private static final String BASE_URL = "http://43.163.104.4"; // Standard port 80

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public interface ApiCallback {
        void onSuccess(JSONObject response);

        void onError(String error);
    }

    /**
     * POST request with JSON body.
     */
    public static void post(String endpoint, JSONObject body, String token, ApiCallback callback) {
        String jsonString = body != null ? body.toString() : "{}";
        RequestBody reqBody = RequestBody.create(jsonString, JSON);

        Request.Builder requestBuilder = new Request.Builder()
                .url(BASE_URL + endpoint)
                .post(reqBody)
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 (KHTML, like Gecko)");

        if (token != null && !token.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + token);
        }

        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "API Error: " + e.toString(), e);
                mainHandler.post(() -> callback.onError("Network error: " + e.toString()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String responseBody = response.body() != null ? response.body().string() : "{}";
                final int code = response.code();

                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    if (response.isSuccessful()) {
                        mainHandler.post(() -> callback.onSuccess(jsonResponse));
                    } else {
                        String detail = jsonResponse.optString("detail", "Request failed (HTTP " + code + ")");
                        mainHandler.post(() -> callback.onError(detail));
                    }
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("Invalid server response (HTTP " + code + ")"));
                }
            }
        });
    }

    /**
     * GET request.
     */
    public static void get(String endpoint, String token, ApiCallback callback) {
        Request.Builder requestBuilder = new Request.Builder()
                .url(BASE_URL + endpoint)
                .get()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Android; Mobile) AppleWebKit/537.36 (KHTML, like Gecko)");

        if (token != null && !token.isEmpty()) {
            requestBuilder.addHeader("Authorization", "Bearer " + token);
        }

        client.newCall(requestBuilder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "API Error: " + e.toString(), e);
                mainHandler.post(() -> callback.onError("Network error: " + e.toString()));
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                final String responseBody = response.body() != null ? response.body().string() : "{}";
                final int code = response.code();

                try {
                    JSONObject jsonResponse = new JSONObject(responseBody);
                    if (response.isSuccessful()) {
                        mainHandler.post(() -> callback.onSuccess(jsonResponse));
                    } else {
                        String detail = jsonResponse.optString("detail", "Request failed (HTTP " + code + ")");
                        mainHandler.post(() -> callback.onError(detail));
                    }
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onError("Invalid server response (HTTP " + code + ")"));
                }
            }
        });
    }
}
