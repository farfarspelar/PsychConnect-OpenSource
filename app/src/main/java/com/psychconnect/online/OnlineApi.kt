package com.psychconnect.online

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Small HTTP client for the profile API; all routes stay under /api. */
class OnlineApi(websocketUrl:String) {
    private val base=websocketUrl.trim().removeSuffix("/").replaceFirst(Regex("^wss://"),"https://").replaceFirst(Regex("^ws://"),"http://").removeSuffix("/ws")
    private val http=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(12,TimeUnit.SECONDS).build()
    private val jsonType="application/json; charset=utf-8".toMediaType()
    fun request(path:String,method:String="GET",body:JSONObject?=null,credentials:ProfileStore.Credentials?=null):JSONObject {
        val builder=Request.Builder().url(base+path).header("Accept","application/json")
        credentials?.let { builder.header("Authorization","Bearer ${it.token}").header("X-Profile-Id",it.profileId) }
        when(method) { "POST","PATCH","PUT" -> builder.method(method,(body?:JSONObject()).toString().toRequestBody(jsonType)); "DELETE" -> builder.delete(); else -> builder.get() }
        val response=http.newCall(builder.build()).execute()
        val text=response.body?.string().orEmpty()
        val result=try { JSONObject(text) } catch (_:Exception) { JSONObject() }
        if(!response.isSuccessful) throw IllegalStateException(result.optString("error").ifBlank { "Online service returned ${response.code}." })
        return result
    }
    fun create(nickname:String,avatarId:Int)=request("/api/profiles","POST",JSONObject().put("nickname",nickname).put("avatarId",avatarId))
    fun authenticate(id:String,token:String)=request("/api/profile/auth","POST",JSONObject().put("profileId",id).put("token",token))
    fun recover(code:String,recovery:String)=request("/api/profile/recover","POST",JSONObject().put("profileCode",code).put("recoveryCode",recovery))
}
