package com.pocketmartian.app.data.api

import com.pocketmartian.app.data.model.ChatRequest
import com.pocketmartian.app.data.model.ChatResponse
import com.pocketmartian.app.data.model.ModelsListResponse
import com.pocketmartian.app.data.model.ResponsesRequest
import com.pocketmartian.app.data.model.ResponsesResponse
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Streaming

interface XaiApiService {
    @POST("v1/chat/completions")
    suspend fun chatCompletions(
        @Header("Authorization") auth: String,
        @Body request: ChatRequest
    ): ChatResponse

    @POST("v1/responses")
    suspend fun responses(
        @Header("Authorization") auth: String,
        @Body request: ResponsesRequest
    ): ResponsesResponse

    /**
     * Streaming Responses API (SSE). Must be `@Streaming` so OkHttp does not buffer the
     * whole body, which would recreate the idle-timeout problem. A [retrofit2.Call] so it
     * can be cancelled while its body is being read: cancelling closes the connection
     * (HTTP/1.1) or resets just this stream (HTTP/2), so the server stops generating, and
     * billing, the answer.
     */
    @Streaming
    @POST("v1/responses")
    fun responsesStreamCall(
        @Header("Authorization") auth: String,
        @Body request: ResponsesRequest
    ): retrofit2.Call<ResponseBody>

    /** Lightweight auth check — lists models the API key can access. */
    @GET("v1/models")
    suspend fun listModels(
        @Header("Authorization") auth: String
    ): ModelsListResponse
}
