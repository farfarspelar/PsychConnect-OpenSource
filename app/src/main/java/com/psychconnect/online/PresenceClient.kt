package com.psychconnect.online

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import android.os.Handler
import android.os.Looper
import java.util.concurrent.TimeUnit

/** Authenticated presence socket with lifecycle-controlled reconnects. */
class PresenceClient(private val listener:(JSONObject)->Unit) {
    private val http=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build()
    private val handler=Handler(Looper.getMainLooper())
    private val backoff=ReconnectBackoff()
    private var socket:WebSocket?=null
    private var serverUrl:String?=null
    private var credentials:ProfileStore.Credentials?=null
    private var shouldReconnect=false
    private var generation=0L
    private var reconnectTask:Runnable?=null

    @Synchronized fun connect(url:String,credentials:ProfileStore.Credentials) {
        close()
        this.serverUrl=url.trim().removeSuffix("/").let { if(it.endsWith("/ws")) it else "$it/ws" }
        this.credentials=credentials
        shouldReconnect=true
        openSocket(generation)
    }

    private fun openSocket(expectedGeneration:Long) {
        val target=serverUrl ?: return
        val identity=credentials ?: return
        val request=Request.Builder().url(target).build()
        val opened=http.newWebSocket(request,object:WebSocketListener(){
            override fun onOpen(webSocket:WebSocket,response:okhttp3.Response) {
                if(!isCurrent(expectedGeneration)) { webSocket.cancel(); return }
                synchronized(this@PresenceClient) { socket=webSocket }
                val message=JSONObject().put("type","profile_auth").put("profileId",identity.profileId).put("token",identity.token).toString()
                if(!webSocket.send(message)) scheduleReconnect(expectedGeneration,webSocket)
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(!isCurrent(expectedGeneration)) return
                try {
                    val message=JSONObject(text)
                    when(message.optString("type")) {
                        "profile_authenticated" -> synchronized(this@PresenceClient) { if(expectedGeneration==generation) backoff.reset() }
                        "profile_auth_failed" -> stopAfterAuthFailure(expectedGeneration,webSocket)
                    }
                    listener(message)
                } catch (_:Exception) { }
            }
            override fun onMessage(webSocket:WebSocket,bytes:ByteString) { }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) { scheduleReconnect(expectedGeneration,webSocket) }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:okhttp3.Response?) { scheduleReconnect(expectedGeneration,webSocket) }
        })
        synchronized(this) { if(shouldReconnect&&expectedGeneration==generation) socket=opened else opened.cancel() }
    }

    @Synchronized private fun isCurrent(expectedGeneration:Long)=shouldReconnect&&generation==expectedGeneration

    private fun stopAfterAuthFailure(expectedGeneration:Long,webSocket:WebSocket) {
        synchronized(this) {
            if(expectedGeneration!=generation) return
            shouldReconnect=false
            if(socket===webSocket) socket=null
            reconnectTask?.let(handler::removeCallbacks); reconnectTask=null
        }
        webSocket.close(1000,"profile authentication failed")
    }

    private fun scheduleReconnect(expectedGeneration:Long,webSocket:WebSocket) {
        synchronized(this) {
            if(!shouldReconnect||expectedGeneration!=generation||reconnectTask!=null) return
            if(socket!=null&&socket!==webSocket) return
            if(socket===webSocket) socket=null
            val task=Runnable {
                synchronized(this@PresenceClient) {
                    if(!shouldReconnect||expectedGeneration!=generation) return@Runnable
                    reconnectTask=null
                    openSocket(expectedGeneration)
                }
            }
            reconnectTask=task
            handler.postDelayed(task,backoff.nextDelayMs())
        }
    }

    @Synchronized fun close() {
        shouldReconnect=false
        generation++
        reconnectTask?.let(handler::removeCallbacks); reconnectTask=null
        socket?.close(1000,"presence closed"); socket=null
        serverUrl=null; credentials=null; backoff.reset()
    }
}
