/*
 * Copyright 2026 Proify, Tomakino
 * Licensed under the Apache License, Version 2.0
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.github.proify.lyricon.spotifyprovider.xposed.api

import android.util.Log
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object SpotifyApi {

    val keysRequired = arrayOf(
        "authorization",
        "client-token",
        "user-agent",
        "x-client-id"
    )

    private const val TAG = "SpotifyApi"
    private const val BASE_URL = "https://guc3-spclient.spotify.com/color-lyrics/v2/track/"

    val headers = mutableMapOf<String, String>()

    val jsonParser = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Throws(Exception::class)
    fun fetchRawLyric(id: String): String = performNetworkRequest(id)

    @Throws(Exception::class)
    private fun performNetworkRequest(id: String): String {
        val url = URL("$BASE_URL$id?vocalRemoval=false&clientLanguage=zh_CN&preview=false")

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("accept", "application/json")
            setRequestProperty("app-platform", "WebPlayer")
            for (key in headers.keys) {
                setRequestProperty(key, headers[key]!!)
            }
        }

        try {
            val response = when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK ->
                    connection.inputStream.bufferedReader().use(BufferedReader::readText)

                HttpURLConnection.HTTP_NOT_FOUND ->
                    throw NoFoundLyricException(id, "No lyric found for $id")

                else -> throw IOException("HTTP error code: $status, msg: ${connection.responseMessage}")
            }

            return try {
                JSONObject(response)
                response
            } catch (e: Exception) {
                Log.e(TAG, "Invalid JSON response for $id: $response", e)
                throw IOException("Invalid JSON response")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error fetching lyric for $id", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }
}