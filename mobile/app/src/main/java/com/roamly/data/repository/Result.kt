package com.roamly.data.repository

sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val message: String) : Result<Nothing>()
}

suspend fun <T> safeApiCall(call: suspend () -> retrofit2.Response<T>): Result<T> {
    return try {
        val response = call()
        if (response.isSuccessful) {
            val body = response.body()
            if (body != null) Result.Success(body)
            else Result.Error("Empty response")
        } else {
            Result.Error("Server error ${response.code()}: ${response.message()}")
        }
    } catch (e: Exception) {
        Result.Error(e.message ?: "Unknown error")
    }
}
