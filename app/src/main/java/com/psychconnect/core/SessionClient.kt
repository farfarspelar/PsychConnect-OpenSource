package com.psychconnect.core

import android.os.SystemClock
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Owns the gameplay socket and retries briefly with the server-issued, in-memory resume token. */
class SessionClient(private val listener: (String) -> Unit) {
    private val http = OkHttpClient.Builder().pingInterval(10, TimeUnit.SECONDS).build()
    private val retries = Executors.newSingleThreadScheduledExecutor()
    private var socket: WebSocket? = null
    private val callbackGeneration = ConnectionCallbackGeneration()
    private var sessionUrl: String? = null
    private var initialMessage: JSONObject? = null
    private var profileId: String? = null
    private var profileToken: String? = null
    private var reconnectAttempts = 0
    private var reconnectStartedAt = 0L
    @Volatile private var reconnecting = false
    @Volatile private var intentionalClose = false
    @Volatile private var resumeToken: String? = null
    @Volatile var playerId: String? = null; private set
    @Volatile var role: String = "FOLLOWER"; private set
    val networkDiagnostics = NetworkDiagnostics()

    @Synchronized fun connect(url: String, action: String, code: String = "", durationSec: Int = 60, mode: String = "DEATHMATCH", matchHalves: Int = 2, profileId:String?=null, token:String?=null, allowHarmonyHaptics:Boolean=false, allowHarmonyTrails:Boolean=false) {
        val generation=callbackGeneration.begin()
        intentionalClose=true; socket?.cancel(); intentionalClose=false
        this.sessionUrl=url; this.profileId=profileId; this.profileToken=token
        initialMessage=JSONObject().put("type",action).put("code",code).put("durationSec",durationSec).put("matchHalves",matchHalves).put("mode",mode)
            .apply { if(action=="host"&&mode=="HARMONY") put("allowHarmonyHaptics",allowHarmonyHaptics).put("allowHarmonyTrails",allowHarmonyTrails); if(profileId!=null&&token!=null) put("profileId",profileId).put("token",token) }
        playerId=null; role="FOLLOWER"; resumeToken=null; reconnecting=false; reconnectAttempts=0; networkDiagnostics.reset()
        openSocket(generation)
    }

    @Synchronized private fun openSocket(generation:Long) {
        if(!callbackGeneration.isCurrent(generation)) return
        val url=sessionUrl ?: return
        val isResume=reconnecting
        val webSocket=http.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener() {
            override fun onOpen(webSocket:WebSocket,response:Response) {
                if(!callbackGeneration.isCurrent(generation)) { webSocket.cancel(); return }
                socket=webSocket
                if(isResume) {
                    val id=playerId; val token=resumeToken
                    if(id==null||token==null) { onTransportLost(generation,webSocket,"Resume information is missing"); return }
                    val message=JSONObject().put("type","resume").put("playerId",id).put("resumeToken",token)
                        .put("code",initialMessage?.optString("code").orEmpty())
                    if(profileId!=null&&profileToken!=null) message.put("profileId",profileId).put("token",profileToken)
                    webSocket.send(message.toString())
                } else initialMessage?.let { webSocket.send(it.toString()) }
            }
            override fun onMessage(webSocket:WebSocket,text:String) {
                if(!callbackGeneration.isCurrent(generation)||socket!==webSocket) return
                try {
                    val msg=JSONObject(text); val type=msg.optString("type")
                    if(type=="hosted"||type=="joined"||type=="session_resumed") {
                        if(msg.has("playerId")) playerId=msg.getString("playerId")
                        if(msg.has("role")) role=msg.getString("role")
                        if(msg.has("resumeToken")) resumeToken=msg.getString("resumeToken")
                    }
                    if(type=="session_resumed") { reconnecting=false; reconnectAttempts=0; reconnectStartedAt=0L }
                    if(type=="resume_rejected") { reconnecting=false; resumeToken=null; intentionalClose=true; webSocket.close(1000,"resume rejected") }
                    if(type=="pong") networkDiagnostics.recordPong(msg.optLong("clientTs",-1L),msg.optDouble("serverTs",Double.NaN),SystemClock.elapsedRealtime())
                    listener(text)
                } catch (_:Exception) { listener(text) }
            }
            override fun onFailure(webSocket:WebSocket,t:Throwable,response:Response?) { onTransportLost(generation,webSocket,t.message?:"offline") }
            override fun onClosed(webSocket:WebSocket,code:Int,reason:String) {
                if(intentionalClose) return
                onTransportLost(generation,webSocket,reason.ifBlank { "offline" })
            }
        })
        socket=webSocket
    }

    @Synchronized private fun onTransportLost(generation:Long,failed:WebSocket,message:String) {
        if(!callbackGeneration.isCurrent(generation)||socket!==failed||intentionalClose) return
        socket=null
        if(resumeToken==null||playerId==null) { listener(JSONObject().put("type","disconnect").put("message",message).toString()); return }
        if(!reconnecting) {
            reconnecting=true; reconnectStartedAt=SystemClock.elapsedRealtime(); reconnectAttempts=0
            listener("{\"type\":\"reconnecting\"}")
        }
        scheduleResume(generation)
    }

    @Synchronized private fun scheduleResume(generation:Long) {
        if(!callbackGeneration.isCurrent(generation)||!reconnecting||socket!=null) return
        val elapsed=SystemClock.elapsedRealtime()-reconnectStartedAt
        if(elapsed>=28_000L) {
            reconnecting=false; resumeToken=null
            listener(JSONObject().put("type","disconnect").put("message","Reconnect window expired").toString())
            return
        }
        val delay=minOf(350L shl reconnectAttempts.coerceAtMost(3),2_800L)
        reconnectAttempts++
        retries.schedule({ openSocket(generation) },delay,TimeUnit.MILLISECONDS)
    }

    fun sendTouch(state: TouchState) {
        val json=JSONObject().put("type","touch").put("active",state.active).put("seq",state.sequence).put("ts",state.timestampMs)
        if(state.point!=null) json.put("x",state.point.x).put("y",state.point.y)
        socket?.send(json.toString())
    }
    fun sendHarmonyTouches(points:List<HarmonyTouch>,sequence:Long,palmContact:Boolean=false) {
        val encoded=JSONArray(); harmonyTouchSnapshot(points).forEach { touch -> encoded.put(JSONObject().put("id",touch.id).put("x",touch.point.x).put("y",touch.point.y).put("sizeClass",touch.sizeClass.coerceIn(0,2))) }
        socket?.send(JSONObject().put("type","harmony_touches").put("seq",sequence).put("points",encoded).put("palm",palmContact).toString())
    }
    fun respondToHarmonySharingConsent(haptics:Boolean,trails:Boolean) { socket?.send(JSONObject().put("type","harmony_haptics_consent").put("accepted",haptics).put("haptics",haptics).put("trails",trails).toString()) }
    fun revokeHarmonyHaptics() { socket?.send("{\"type\":\"harmony_haptics_revoke\"}") }
    fun revokeHarmonyTrails() { socket?.send("{\"type\":\"harmony_trails_revoke\"}") }
    fun sendHarmonyGesture(gesture:String,fingers:Int,sequence:Long) { socket?.send(JSONObject().put("type","harmony_gesture").put("gesture",gesture).put("fingers",fingers.coerceIn(1,2)).put("seq",sequence).toString()) }
    fun useAbility(ability:String,point:Point?=null) { val json=JSONObject().put("type","ability").put("ability",ability); if(point!=null) json.put("x",point.x).put("y",point.y); socket?.send(json.toString()) }
    fun ping(clientTimeMs:Long=SystemClock.elapsedRealtime()) { socket?.send(JSONObject().put("type","ping").put("clientTs",clientTimeMs).toString()) }
    fun ready() { socket?.send("{\"type\":\"ready\"}") }
    fun nextRound() { ready() }
    fun rematch() { socket?.send("{\"type\":\"rematch\"}") }
    fun endSandbox() { socket?.send("{\"type\":\"sandbox_exit\"}") }
    fun leaveSession() { socket?.send("{\"type\":\"leave_session\"}"); close() }
    @Synchronized fun close() {
        callbackGeneration.invalidate(); intentionalClose=true; reconnecting=false; resumeToken=null; playerId=null
        socket?.close(1000,"done"); socket=null; sessionUrl=null; initialMessage=null
    }
}

internal class ConnectionCallbackGeneration {
    private var current=0L
    fun begin():Long { current++; return current }
    fun isCurrent(generation:Long)=generation==current
    fun invalidate() { current++ }
}
