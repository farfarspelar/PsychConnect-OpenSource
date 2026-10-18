package com.psychconnect.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipData
import android.os.Bundle
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.BitmapFactory
import android.graphics.LinearGradient
import android.graphics.Shader
import android.net.Uri
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.content.FileProvider
import com.psychconnect.R
import java.io.File
import com.psychconnect.core.*
import com.psychconnect.online.OnlineApi
import com.psychconnect.online.ProfileStore
import com.psychconnect.online.PresenceClient
import org.json.JSONObject
import org.json.JSONArray
import kotlin.math.hypot
import kotlin.math.min
import java.util.concurrent.Executors
import java.util.UUID

private const val BG = 0xFF090A10.toInt()
private const val PURPLE = 0xFFB39DFF.toInt()
private const val CYAN = 0xFF70D7DE.toInt()
private class FlameGradientTextView(context: android.content.Context) : TextView(context) {
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            paint.shader = LinearGradient(
                0f, 0f, 0f, h.toFloat(),
                intArrayOf(0xFFFFEAA1.toInt(), 0xFFFFA04A.toInt(), 0xFFFF5947.toInt()),
                floatArrayOf(0f, .52f, 1f), Shader.TileMode.CLAMP
            )
        }
    }
}
private val zodiacAvatars=listOf("♈ Aries","♉ Taurus","♊ Gemini","♋ Cancer","♌ Leo","♍ Virgo","♎ Libra","♏ Scorpio","♐ Sagittarius","♑ Capricorn","♒ Aquarius","♓ Pisces")
private data class AbilityEffect(val type:String,val point:Point,val startedAt:Long,val durationMs:Long)
private data class TogetherWaveEffect(val id:String,val point:Point,val gaps:List<Double>,val startedAt:Long,val durationMs:Long,val maxRadius:Double)
private data class TouchTrailSample(val owner:String,val point:Point,val at:Long,val color:Int)
private data class PuzzleWall(val x1:Double,val y1:Double,val x2:Double,val y2:Double)
private data class PuzzleBlast(val center:Point,val radius:Double,val maxRadius:Double,val armed:Boolean)
private data class DuoBlast(val center:Point,val radius:Double,val maxRadius:Double,val armed:Boolean,val remainingMs:Long,val durationMs:Long,val motionFactor:Double,val receivedAtMs:Long) {
    fun radiusAt(nowMs:Long):Double {
        val activeElapsed=(nowMs-receivedAtMs).coerceAtLeast(0L).coerceAtMost(180L)*motionFactor
        if(armed) {
            if(activeElapsed<remainingMs) return 0.0
            return maxRadius*((activeElapsed-remainingMs)/durationMs).coerceIn(0.0,1.0)
        }
        return (radius+maxRadius*activeElapsed/durationMs).coerceIn(0.0,maxRadius)
    }
}

class MainActivity : Activity() {
    private lateinit var haptics: HapticEngine
    private lateinit var root: LinearLayout
    private lateinit var screenFrame: FrameLayout
    private lateinit var status: TextView
    private lateinit var client: SessionClient
    private lateinit var profileStore: ProfileStore
    private lateinit var onlineApi: OnlineApi
    private lateinit var presenceClient:PresenceClient
    private val onlineWorker=Executors.newSingleThreadExecutor()
    private var profileCredentials:ProfileStore.Credentials?=null
    private var profileData:JSONObject?=null
    private var profileReady=false
    private var screen = "loading"
    private var connected = false
    private var developer = false
    private var hapticsEnabled = true
    private var fasterWhenClose = false
    private var animatedBackground = true
    private var reducedVisualEffects = false
    private var reducedMotion = false
    private var touchTrails = true
    private var trailLifetimeMs = 3_000
    private var menuMusicEnabled = true
    private var buttonSoundsEnabled = true
    private var soundPool: SoundPool? = null
    private var buttonSoundId = 0
    private var buttonSoundReady = false
    private var menuMusic: MediaPlayer? = null
    private var menuMusicAsset: String? = null
    private val spriteCache = mutableMapOf<String, Bitmap>()
    private val spriteLoader = Executors.newSingleThreadExecutor()
    private var sparkleOverlay: AmbientSparkleView? = null
    private val animationUi = android.os.Handler(android.os.Looper.getMainLooper())
    private var activityResumed = false
    private val animationTick = object : Runnable {
        override fun run() {
            if (!activityResumed || screen == "game") return
            val now = SystemClock.elapsedRealtime()
            sparkleOverlay?.updateAt(now, !animatedBackground, reducedMotion, reducedVisualEffects)
            energyOverlay?.updateAt(now, !animatedBackground || reducedMotion || reducedVisualEffects)
            animationUi.postDelayed(this, 80L)
        }
    }
    private var energyOverlay: MenuElectricOverlay? = null
    private var gameMode = "DEATHMATCH"
    private var hostAllowsHarmonyHaptics = false
    private var hostAllowsHarmonyTrails = false
    private var harmonySharedHaptics = false
    private var harmonySharedTrails = false
    private var pendingHarmonyHapticsConsent = false
    private var lastHarmonyConsentOffer:JSONObject?=null
    private var harmonyGestureSequence = 0L
    private var harmonyAnchorLocal = false
    private var harmonyAnchorRemote = false
    private var remotePalmContact = false
    private var lastAdvertisedPalm = false
    private var palmFeedbackMode = PalmFeedbackMode.FINGER
    private var harmonyAnchorPointer: Int? = null
    private val harmonyHapticMapper=HarmonyHapticMapper()
    private val localHarmonyMotion=HarmonyMotionTracker()
    private val remoteHarmonyMotion=HarmonyMotionTracker()
    private val localHarmonyRotation=HarmonyRotationTracker()
    private val remoteHarmonyRotation=HarmonyRotationTracker()
    private val harmonyPalmSynchronizer=HarmonyPalmSynchronizer()
    private var durationSec = 60
    private val survivalEngine = SurvivalGameEngine()
    private var survivalBestMs = 0L
    private var duoSurvivalBestMs = 0L
    private var survivalPausedAtMs = 0L
    private var survivalRunActive = false
    private var survivalMotionPoint=Point(.5,.76)
    private var survivalMotionAtMs=0L
    private var puzzleLevel=0
    private var puzzleScore=0
    private var puzzleTotalScore=0
    private var puzzleStart=Point(.5,.89)
    private var puzzleWalls=emptyList<PuzzleWall>()
    private var puzzleCheckpoints=emptyList<Point>()
    private var puzzleStarted=false
    private var puzzleTimerStarted=false
    private var puzzleNeedsStart=true
    private var puzzleHoldMs=0L
    private var puzzleCheckpointAccepted=false
    private var puzzleRemainingMs=60_000L
    private var puzzleTimeAtMs=0L
    private var puzzleProjectiles=emptyList<SurvivalProjectile>()
    private var puzzleBlasts=emptyList<PuzzleBlast>()
    private var puzzleMotionFactor=0.0
    private var duoSurvivalStarted=false
    private var duoSurvivalElapsedMs=0L
    private var duoSurvivalMotionFactor=0.0
    private var duoSurvivalLevel=1
    private var duoReadyCount=0
    private var duoProjectiles=emptyList<SurvivalProjectile>()
    private var duoBlasts=emptyList<DuoBlast>()
    private var duoStateAtMs=0L
    private var matchHalves = 2
    private var currentRole = "LEADER"
    private var serverUrl = ""
    private var roomCode = ""
    private var shareRoomButton: Button? = null
    private var liveScore = 0
    private var matchFinished = false
    private val duelScores = mutableMapOf<String, Int>()
    private var lastNetworkSendMs = 0L
    private var lastPingAt = 0L
    private var lastRemoteServerTs: Double? = null
    private var touchSequence = 0L
    private var isHost = false
    private var simMode = false
    private var localLeader = Point(.5, .5)
    private var localFollower = Point(.5, .5)
    private var sharedScore = 0.0
    private var lastSharedScoreAt = 0L
    private var previousSharedLeader: Point? = null
    private var previousSharedFollower: Point? = null
    private var remotePoint: Point? = null
    private var interpolatedRemotePoint: Point? = null
    private val remoteHarmonyPoints = linkedMapOf<Int,Point>()
    private val remoteHarmonyFingerSizes = linkedMapOf<Int,Int>()
    private val remotePositions=RemotePositionBuffer()
    private var localActive = false
    private var remoteActive = false
    private val remoteTouch = RemoteTouchTracker()
    private var synchronizedNow = false
    private var abilityUsesRemaining = 2
    private val abilityEffects = mutableListOf<AbilityEffect>()
    private val togetherWaveEffects = mutableListOf<TogetherWaveEffect>()
    private var togetherWaveCue = ""
    private var togetherWaveCueUntil = 0L
    private var hapticsRunning = false
    private var connectionStartedAt = 0L
    private var currentBpm = 65.0
    private val beatHistory = mutableListOf<Pair<Long, Float>>()
    private var speedWarningUntil = 0L
    private var roundStart = 0L
    private var networkPaused = false
    private var networkPauseMessage = ""
    private var networkRemainingMs = 0L
    private val samples = mutableListOf<Sample>()
    private var mapper = HeartbeatMapper()
    private var speedLimiter = SpeedLimiter()
    private var roundState = RoundStateMachine()
    private val roundInputGate = RoundInputGate()
    private lateinit var pad: TouchPad
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable { override fun run() { updateGame(); if (screen == "game") ui.postDelayed(this, 50) } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; window.statusBarColor=BG; window.navigationBarColor=BG; val prefs=getSharedPreferences("psychconnect",MODE_PRIVATE); fasterWhenClose=prefs.getBoolean("fasterWhenClose",false); animatedBackground=prefs.getBoolean("animatedBackground",true); reducedVisualEffects=prefs.getBoolean("reducedVisualEffects",false); reducedMotion=prefs.getBoolean("reducedMotion",false); touchTrails=prefs.getBoolean("touchTrails",true); trailLifetimeMs=prefs.getInt("trailLifetimeMs",3_000).coerceIn(1_000,12_000); menuMusicEnabled=prefs.getBoolean("menuMusicEnabled",true); buttonSoundsEnabled=prefs.getBoolean("buttonSoundsEnabled",true); survivalBestMs=prefs.getLong("survivalHighScoreMs",0L); duoSurvivalBestMs=prefs.getLong("duoSurvivalHighScoreMs",0L); initAudio(); profileStore=ProfileStore(this); onlineApi=OnlineApi(prefs.getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!); profileCredentials=profileStore.get(); presenceClient=PresenceClient { msg -> runOnUiThread { onPresenceMessage(msg) } }; haptics=HapticEngine(this); haptics.onBeat={ boost -> val now=SystemClock.elapsedRealtime(); beatHistory.add(now to boost.coerceAtMost(1.4f)); while(beatHistory.size>3) beatHistory.removeAt(0); if(::pad.isInitialized) pad.invalidate() }; client=SessionClient { msg -> runOnUiThread { onServerMessage(msg) } }; loadSpriteAssets() }
    private fun initAudio() {
        val attributes=AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        soundPool=SoundPool.Builder().setMaxStreams(2).setAudioAttributes(attributes).build().also { pool ->
            pool.setOnLoadCompleteListener { _,sampleId,status -> if(sampleId==buttonSoundId) buttonSoundReady=status==0 }
            buttonSoundId=pool.load(this,R.raw.button_click,1)
        }
    }
    private fun playButtonSound() {
        if(!buttonSoundsEnabled || !buttonSoundReady || buttonSoundId==0) return
        soundPool?.play(buttonSoundId,.38f,.38f,1,0,1f)
    }
    private fun syncMenuMusic() {
        if(!activityResumed || !menuMusicEnabled || screen=="loading" || isFinishing) { stopMenuMusic(); return }
        val wantedAsset=if(gameMode in setOf("SURVIVAL","COOP_SURVIVAL") && screen in setOf("game","result")) "audio/survival_music.ogg" else "audio/menu_music.ogg"
        if(menuMusic!=null && menuMusicAsset==wantedAsset) return
        stopMenuMusic()
        try {
            val player=MediaPlayer()
            assets.openFd(wantedAsset).use { track -> player.setDataSource(track.fileDescriptor,track.startOffset,track.length) }
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            player.isLooping=true
            player.setVolume(.32f,.32f)
            player.setOnCompletionListener { it.seekTo(0); it.start() }
            player.setOnErrorListener { failed,_,_ -> failed.release(); if(menuMusic===failed) { menuMusic=null; menuMusicAsset=null }; true }
            menuMusic=player
            menuMusicAsset=wantedAsset
            player.setOnPreparedListener { if(activityResumed && menuMusicEnabled && menuMusic===it) it.start() else { if(menuMusic===it) menuMusic=null; it.release() } }
            player.prepareAsync()
        } catch (_:Exception) { menuMusic?.release(); menuMusic=null; menuMusicAsset=null }
    }
    private fun stopMenuMusic() { menuMusic?.let { player -> runCatching { if(player.isPlaying) player.pause() }; runCatching { player.release() } }; menuMusic=null; menuMusicAsset=null }
    private fun loadSpriteAssets() {
        val loading=TextView(this).apply { text="PsychConnect"; textSize=26f; setTextColor(PURPLE); gravity=Gravity.CENTER; setBackgroundColor(BG) }
        setContentView(loading)
        spriteLoader.execute {
            val decoded=mutableMapOf<String,Bitmap>()
            var failure:Exception?=null
            try {
                val names=listOf("hands_background") +
                    listOf(1,4,7).map { "mode_${it.toString().padStart(2,'0')}" } +
                    listOf(1,4,7,10).map { "secondary_${it.toString().padStart(2,'0')}" } +
                    listOf("blank_large_button","blank_room_code_field","blank_small_button","profile_button") +
                    (0..11).map { "zodiac_${it.toString().padStart(2,'0')}" }
                for(name in names) {
                    val path=if(name.startsWith("zodiac_")) "ui/avatars/$name.webp" else "ui/frames/$name.webp"
                    assets.open(path).use { stream ->
                    decoded[name]=BitmapFactory.decodeStream(stream) ?: error("Could not decode UI sprite $name")
                    }
                }
            } catch (error:Exception) { failure=error }
            runOnUiThread {
                if (isFinishing || isDestroyed || failure!=null) {
                    decoded.values.forEach { if(!it.isRecycled) it.recycle() }
                    if(!isFinishing && !isDestroyed) {
                        Toast.makeText(this,"Menu artwork could not be loaded",Toast.LENGTH_LONG).show()
                        finish()
                    }
                } else {
                    spriteCache.putAll(decoded)
                    showHome()
                    profileStore.pendingRecoveryCode()?.let { showRecoveryCode(it) }
                    ensureOnlineProfile()
                }
            }
        }
    }
    private fun base(title: String, subtitle: String? = null) {
        screen = title.lowercase()
        syncMenuMusic()
        energyOverlay=null
        window.decorView.systemUiVisibility=0
        animationUi.removeCallbacks(animationTick)
        screenFrame = FrameLayout(this).apply { setBackgroundColor(BG) }
        screenFrame.addView(SpriteImageView(this, assetBitmap("hands_background"), centerCrop=true), FrameLayout.LayoutParams(-1,-1))
        val sparkles=AmbientSparkleView(this)
        sparkleOverlay=sparkles
        screenFrame.addView(sparkles, FrameLayout.LayoutParams(-1,-1))
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(28),dp(20),dp(28),dp(20)); setBackgroundColor(android.graphics.Color.TRANSPARENT) }
        if (title == "home") { root.gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; root.setPadding(dp(28),dp(8),dp(28),dp(8)); root.clipChildren=false; root.clipToPadding=false }
        if (title == "home") setContentView(screenFrame.apply {
            addView(root, FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        })
        else {
            val scroll=ScrollView(this).apply { isFillViewport=true; clipToPadding=false }
            scroll.addView(root, FrameLayout.LayoutParams(-1,-2))
            screenFrame.addView(scroll, FrameLayout.LayoutParams(-1,-1))
            root.minimumHeight=resources.displayMetrics.heightPixels
            setContentView(screenFrame)
            if (subtitle != null) text(subtitle, 15, 0xFFAAA9B5.toInt())
        }
        if (activityResumed) animationUi.post(animationTick)
    }
    private fun dp(value:Int):Int=(value*resources.displayMetrics.density+0.5f).toInt()
    /** Keep phone artwork unchanged and proportionally enlarge the menu stack on tablets. */
    private fun homeArtworkScale():Float {
        val smallestWidth=resources.configuration.smallestScreenWidthDp
        if(smallestWidth<600) return 1f
        return (1.10f+(smallestWidth-600)*0.0004f).coerceIn(1.10f,1.20f)
    }
    private fun zodiacAvatar(id:Int)=zodiacAvatars[id.coerceIn(0,zodiacAvatars.lastIndex)]
    private fun assetBitmap(name: String): Bitmap = spriteCache[name] ?: error("UI sprite was not preloaded: $name")
    private fun text(value: String, size: Int, color: Int, bold: Boolean=false): TextView = TextView(this).apply {
        text=value; textSize=size.toFloat(); setTextColor(color); gravity=Gravity.CENTER
        if(bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12),dp(10),dp(12),dp(10))
        if(size in 13..17) {
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(0xD90A0B13.toInt())
                cornerRadius=dp(12).toFloat()
                setStroke(dp(1),0x557B71A2)
            }
        }
        root.addView(this, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4); bottomMargin=dp(4) })
    }
    private fun button(value: String, action: () -> Unit) { root.addView(Button(this).apply { text=value; setTextColor(Color.WHITE); setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF292637.toInt())); setOnClickListener { playButtonSound(); action() } }, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(7); bottomMargin=dp(7) }) }
    private fun artworkButton(label:String, asset:String, heightDp:Int, action:()->Unit) {
        val image=SpriteImageView(this,assetBitmap(asset)).apply { scaleType=ImageView.ScaleType.FIT_XY }
        val frame=FrameLayout(this).apply {
            contentDescription=label; isClickable=true; isFocusable=true
            addView(image,FrameLayout.LayoutParams(-1,-1))
            addView(TextView(this@MainActivity).apply { text=label; textSize=18f; setTextColor(0xFFF5F2FF.toInt()); setTypeface(typeface,android.graphics.Typeface.BOLD); gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
            setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { image.setPressedHighlight(true); view.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { image.setPressedHighlight(false); view.animate().scaleX(1f).scaleY(1f).setDuration(130).start() }
                }
                false
            }
            setOnClickListener { playButtonSound(); action() }
        }
        root.addView(frame,LinearLayout.LayoutParams(-1,dp(heightDp)).apply { topMargin=dp(2); bottomMargin=dp(2) })
    }
    private fun ensureOnlineProfile() {
        if(profileCredentials!=null) {
            onlineWorker.execute {
                try { onlineApi.authenticate(profileCredentials!!.profileId,profileCredentials!!.token); profileData=onlineApi.request("/api/profile",credentials=profileCredentials).optJSONObject("profile"); profileReady=true; presenceClient.connect(getSharedPreferences("psychconnect",MODE_PRIVATE).getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!,profileCredentials!!) }
                catch (_:Exception) { /* profile routes remain available for retry; gameplay stays usable */ }
                runOnUiThread { if(screen=="home") updateProfileShortcut() }
            }
        } else if(!getSharedPreferences("psychconnect",MODE_PRIVATE).getBoolean("continueWithoutProfile",false)) {
            window.decorView.postDelayed({ if(!isFinishing && screen=="home") showNicknamePrompt() },300)
        }
    }
    private fun showNicknamePrompt() {
        val input=EditText(this).apply { hint="Nickname (2–20 characters)"; setSingleLine(true); filters=arrayOf(android.text.InputFilter.LengthFilter(20)) }
        val avatar=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,zodiacAvatars) }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),0,dp(20),0); addView(input); addView(avatar) }
        AlertDialog.Builder(this).setTitle("What should we call you?")
            .setMessage("Choose a nickname to create your PsychConnect profile. No email or password is needed.")
            .setView(box).setPositiveButton("Create profile",null).setNegativeButton("Continue without profile",null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val nickname=input.text.toString().trim()
                        if(nickname.length !in 2..20) { input.error="Use 2–20 characters"; return@setOnClickListener }
                        dialog.dismiss(); createOnlineProfile(nickname,avatar.selectedItemPosition)
                    }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                        getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putBoolean("continueWithoutProfile",true).apply(); dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }
    private fun createOnlineProfile(nickname:String,avatar:Int) {
        val progress=Toast.makeText(this,"Creating your profile…",Toast.LENGTH_SHORT); progress.show()
        onlineWorker.execute {
            try {
                val response=onlineApi.create(nickname,avatar); val profile=response.getJSONObject("profile"); val credentials=response.getJSONObject("credentials")
                val stored=ProfileStore.Credentials(profile.getString("id"),profile.getString("profileCode"),profile.getString("nickname"),profile.optInt("avatarId"),credentials.getString("token"))
                profileStore.save(stored.profileId,stored.profileCode,stored.nickname,stored.avatarId,stored.token); profileCredentials=stored; profileData=profile; profileReady=true
                presenceClient.connect(getSharedPreferences("psychconnect",MODE_PRIVATE).getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!,stored)
                runOnUiThread { showRecoveryCode(credentials.getString("recoveryCode")); if(screen=="home") updateProfileShortcut() }
            } catch(error:Exception) {
                runOnUiThread { AlertDialog.Builder(this).setTitle("Profile unavailable").setMessage(error.message ?: "Could not connect to the profile service.").setPositiveButton("Retry") { _,_ -> showNicknamePrompt() }.setNegativeButton("Continue offline") { _,_ -> getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putBoolean("continueWithoutProfile",true).apply() }.show() }
            }
        }
    }
    private fun showRecoveryCode(recovery:String) {
        val profileCode=profileCredentials?.profileCode.orEmpty()
        profileStore.saveRecoveryCode(recovery)
        val dialog=AlertDialog.Builder(this).setTitle("Save your recovery code")
            .setMessage("Profile code: $profileCode\n\nRecovery code: $recovery\n\nSave both somewhere safe. This code is shown only once by the server. If you lose your device and recovery code, this profile cannot be restored.")
            .setNeutralButton("Copy",null).setPositiveButton("I saved it",null).create()
        dialog.setCancelable(false)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("PsychConnect recovery", "$profileCode\n$recovery"))
                Toast.makeText(this,"Recovery details copied",Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                profileStore.clearRecoveryCode()
                dialog.dismiss()
            }
        }
        dialog.show()
    }
    private fun updateProfileShortcut() {
        if(screen!="home") return
        val shortcut=screenFrame.findViewWithTag<View>("profile_shortcut") as? SpriteImageView ?: return
        val button=screenFrame.findViewWithTag<View>("profile_button_container")
        val nameView=screenFrame.findViewWithTag<View>("profile_shortcut_name") as? TextView
        val presenceDot=screenFrame.findViewWithTag<View>("profile_shortcut_presence")
        val credential=profileCredentials
        val displayName=profileData?.optString("nickname")?.takeIf { it.isNotBlank() } ?: credential?.nickname
        if(credential!=null) {
            val avatarId=(profileData?.optInt("avatarId",credential.avatarId) ?: credential.avatarId).coerceIn(0,11)
            shortcut.setImageBitmap(assetBitmap("zodiac_${avatarId.toString().padStart(2,'0')}"))
            nameView?.apply {
                visibility=View.VISIBLE
                text=displayName.orEmpty()
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,when(displayName.orEmpty().length) { in 0..7->8f; in 8..12->7f; else->5f })
            }
            presenceDot?.visibility=View.VISIBLE
            (presenceDot?.background as? android.graphics.drawable.GradientDrawable)?.setColor(if(profileReady) CYAN else 0xFFFFC16D.toInt())
            shortcut.contentDescription=if(profileReady) "Signed in as ${displayName ?: "player"}. Open profile" else "${displayName ?: "Profile"}, profile offline. Open profile"
            button?.contentDescription=shortcut.contentDescription
        } else {
            shortcut.setImageBitmap(assetBitmap("profile_button"))
            nameView?.visibility=View.GONE
            presenceDot?.visibility=View.GONE
            shortcut.contentDescription="Set up a PsychConnect profile"
            button?.contentDescription=shortcut.contentDescription
        }
    }
    private fun apiRequest(path:String,method:String="GET",body:JSONObject?=null,done:(JSONObject?,String?)->Unit) {
        val credential=profileCredentials
        onlineWorker.execute {
            try { val result=onlineApi.request(path,method,body,credential); runOnUiThread { done(result,null) } }
            catch(error:Exception) { runOnUiThread { done(null,error.message ?: "Online service unavailable.") } }
        }
    }
    private fun showOnlineHome() {
        base("online", "Your pseudonymous PsychConnect identity")
        val credential=profileCredentials
        if(credential==null) {
            text("Create a nickname profile to save matches, view rankings, and build rivalries. The profile service needs a database connection.",15,0xFFD1CFDC.toInt())
            button("Create profile") { showNicknamePrompt() }
            button("Restore with recovery code") { showRecoveryPrompt() }
            button("Back") { showHome() }; return
        }
        text("Loading profile…",15,CYAN)
        apiRequest("/api/profile") { result,error ->
            if(screen!="online") return@apiRequest
            root.removeAllViews()
            if(error!=null || result==null) { text(error ?: "Could not load profile",15,0xFFFFA0A0.toInt()); button("Retry") { showOnlineHome() }; button("Restore with recovery code") { showRecoveryPrompt() }; button("Back") { showHome() }; return@apiRequest }
            val p=result.getJSONObject("profile"); profileData=p; profileReady=true
            val s=p.getJSONObject("stats")
            text("${zodiacAvatar(p.optInt("avatarId"))}  ${p.optString("nickname")}  ·  ${p.optString("profileCode")}",22,PURPLE,true)
            button("Copy public profile code") { (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(ClipData.newPlainText("PsychConnect profile code",p.optString("profileCode"))); toastOnline("Profile code copied") }
            text("${s.optInt("totalMatches")} matches · Elo ${s.optInt("rating")} (peak ${s.optInt("highestRating")}) · ${s.optInt("versusWins")}W / ${s.optInt("versusLosses")}L\nWin rate ${s.optInt("winRate")}% · Streak ${s.optInt("currentWinStreak")} (best ${s.optInt("longestWinStreak")})\nTogether best ${s.optInt("togetherBestScore")} · ${s.optInt("togetherSessions")} sessions\nHarmony ${s.optInt("harmonySessions")} · ${s.optInt("harmonySeconds")}s · Sync ${s.optInt("totalSyncSeconds")}s\nFavorite ${s.optString("favoriteMode")} · Rivals ${s.optInt("rivalsEncountered")}",15,0xFFF0EDF8.toInt())
            button("Edit nickname and avatar") { editProfile(p) }
            button("Match history") { showMatchHistory() }
            button("Rival challenges") { showChallenges() }
            button("Recovery and privacy") { profileSettings(p) }
            button("Achievements") { showAchievements() }
            button("Delete profile") { confirmProfileDeletion() }
            button("Back") { showHome() }
        }
    }
    private fun editProfile(profile:JSONObject) {
        val input=EditText(this).apply { setText(profile.optString("nickname")); filters=arrayOf(android.text.InputFilter.LengthFilter(20)) }
        val avatar=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,zodiacAvatars); setSelection(profile.optInt("avatarId").coerceIn(0,11)) }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(input); addView(avatar) }
        AlertDialog.Builder(this).setTitle("Edit profile").setView(box).setPositiveButton("Save") { _,_ -> apiRequest("/api/profile","PATCH",JSONObject().put("nickname",input.text.toString().trim()).put("avatarId",avatar.selectedItemPosition)) { r,e -> if(e!=null) toastOnline(e) else { profileData=r?.optJSONObject("profile"); showOnlineHome() } } }.setNegativeButton("Cancel",null).show()
    }
    private fun profileSettings(profile:JSONObject) {
        val hidden=Switch(this).apply { text="Hide online presence"; setTextColor(Color.WHITE); isChecked=profile.optBoolean("presenceHidden") }
        val challenges=Switch(this).apply { text="Allow rival challenges"; setTextColor(Color.WHITE); isChecked=profile.optBoolean("challengesEnabled") }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,8,20,0); addView(hidden); addView(challenges) }
        AlertDialog.Builder(this).setTitle("Profile privacy").setView(box).setPositiveButton("Save") { _,_ -> apiRequest("/api/profile/privacy","PATCH",JSONObject().put("presenceHidden",hidden.isChecked).put("challengesEnabled",challenges.isChecked)) { _,e -> if(e!=null) toastOnline(e) else showOnlineHome() } }.setNeutralButton("Restore profile") { _,_ -> showRecoveryPrompt() }.setNegativeButton("Cancel",null).show()
    }
    private fun showRecoveryPrompt() {
        val code=EditText(this).apply { hint="Profile code"; setSingleLine() }; val recovery=EditText(this).apply { hint="Recovery code"; setSingleLine() }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(code); addView(recovery) }
        AlertDialog.Builder(this).setTitle("Restore profile").setMessage("This replaces the profile stored on this device.").setView(box).setPositiveButton("Restore",null).setNegativeButton("Cancel",null).create().also { dialog -> dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            onlineWorker.execute { try { val r=onlineApi.recover(code.text.toString(),recovery.text.toString()); val p=r.getJSONObject("profile"); val c=r.getJSONObject("credentials"); val value=ProfileStore.Credentials(p.getString("id"),p.getString("profileCode"),p.getString("nickname"),p.optInt("avatarId"),c.getString("token")); profileStore.save(value.profileId,value.profileCode,value.nickname,value.avatarId,value.token); profileCredentials=value; profileData=p; profileReady=true; presenceClient.connect(getSharedPreferences("psychconnect",MODE_PRIVATE).getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!,value); runOnUiThread { dialog.dismiss(); showRecoveryCode(c.getString("recoveryCode")); if(screen=="online") showOnlineHome() } }
                catch(e:Exception) { runOnUiThread { toastOnline(e.message ?: "Restore failed") } } }
        } }; dialog.show() }
    }
    private fun confirmProfileDeletion() { AlertDialog.Builder(this).setTitle("Delete profile?").setMessage("Your profile, saved match history, achievements, and rival statistics will be deleted permanently.").setPositiveButton("Delete") { _,_ -> apiRequest("/api/profile","DELETE") { _,e -> if(e!=null) toastOnline(e) else { profileStore.clear(); profileCredentials=null; profileData=null; profileReady=false; showHome() } } }.setNegativeButton("Cancel",null).show() }
    private fun showLeaderboards(initialMode:String="DEATHMATCH") {
        base("leaderboards","Top five · device records and online rankings")
        val modes=listOf("DEATHMATCH","CONNECTION","SOLO_SURVIVAL","COOP_SURVIVAL","PUZZLE")
        var mode=initialMode.takeIf { it in modes } ?: "DEATHMATCH"; var source="online"; var range="all_time"; var generation=0
        lateinit var renderHeaderOnly:()->Unit
        lateinit var renderAction:(Boolean)->Unit
        fun label(value:String)=when(value) { "DEATHMATCH"->"Versus"; "CONNECTION"->"Together"; "COOP_SURVIVAL"->"2P Survival"; "PUZZLE"->"Puzzle"; else->"Solo Survival" }
        fun pill(parent:LinearLayout,title:String,selected:Boolean,onClick:()->Unit) {
            val item=TextView(this).apply {
                text=title; textSize=13f; gravity=Gravity.CENTER; setTextColor(if(selected) 0xFFFFFFFF.toInt() else 0xFFCDC8DD.toInt())
                setPadding(dp(14),dp(9),dp(14),dp(9)); isClickable=true; isFocusable=true
                background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(22).toFloat(); setColor(if(selected) 0xFF554477.toInt() else 0xCC10111B.toInt()); setStroke(dp(1),if(selected) PURPLE else 0x557B71A2) }
                setOnClickListener { playButtonSound(); onClick() }
            }
            parent.addView(item,LinearLayout.LayoutParams(-2,-2).apply { rightMargin=dp(6); bottomMargin=dp(6) })
        }
        fun modeSelector():LinearLayout = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            modes.chunked(3).forEach { values ->
                val row=LinearLayout(this@MainActivity).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
                values.forEach { value -> pill(row,label(value),mode==value) { mode=value; range="all_time"; renderAction(false) } }
                addView(row,LinearLayout.LayoutParams(-1,-2))
            }
        }
        fun render(refresh:Boolean=false) {
            val current=++generation
            root.removeAllViews()
            root.addView(modeSelector(),LinearLayout.LayoutParams(-1,-2))
            val sourceStrip=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
            pill(sourceStrip,"On this device",source=="local") { source="local"; render() }
            pill(sourceStrip,"Online",source=="online") { source="online"; render() }
            root.addView(sourceStrip,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4); bottomMargin=dp(4) })
            if(mode=="DEATHMATCH"&&source=="online") pill(sourceStrip,if(range=="all_time") "All time" else "Weekly",false) { range=if(range=="all_time") "weekly" else "all_time"; render() }
            if(source=="local") {
                val rows=localLeaderboard(mode)
                text("${label(mode).uppercase()} · LOCAL TOP 5",12,CYAN,true)
                if(rows.length()==0) text("No local runs saved yet. Play a round and your best results will appear here.",15,0xFFD1CFDC.toInt())
                else for(i in 0 until rows.length()) {
                    val row=rows.getJSONObject(i); val score=row.optLong("score")
                    val detail=if(mode in setOf("SOLO_SURVIVAL","COOP_SURVIVAL")) formatSurvivalTime(score) else "$score pts"
                    leaderboardCard("#${i+1}   ${row.optString("name","You")}",detail,i==0)
                }
                if(mode=="SOLO_SURVIVAL") pill(sourceStrip,"Sync best to online",false) {
                    val best=(0 until rows.length()).maxOfOrNull { rows.optJSONObject(it)?.optLong("score") ?: 0L } ?: 0L
                    val credential=profileCredentials
                    if(best<=0L) toastOnline("Play a Solo Survival run first.")
                    else if(credential==null) toastOnline("Create or sign in to a profile to sync your score.")
                    else apiRequest("/api/leaderboards/solo-survival","POST",JSONObject().put("runId",UUID.randomUUID().toString()).put("scoreMs",best)) { _,error -> toastOnline(error ?: "Local best synced to the online leaderboard.") }
                }
            } else if(profileCredentials==null) {
                text("Create a profile to view online rankings and sync Solo Survival records.",15,0xFFD1CFDC.toInt())
                artworkButton("Create profile","blank_large_button",62) { showNicknamePrompt() }
                footer(refreshAction={ render() },backAction={ showHome() })
            } else {
                text("${label(mode).uppercase()} · ONLINE TOP 5${if(range=="weekly") " · THIS WEEK" else ""}",12,CYAN,true)
                text("Loading…",14,0xFFCDC8DD.toInt())
                apiRequest("/api/leaderboards?mode=$mode&range=$range${if(refresh) "&refresh=true" else ""}") { response,error ->
                    if(screen!="leaderboards"||current!=generation||source!="online") return@apiRequest
                    root.removeAllViews(); renderHeaderOnly()
                    if(error!=null||response==null) text(error ?: "Online rankings unavailable.",15,0xFFFFA0A0.toInt()) else {
                        val rows=response.optJSONArray("entries")
                        if(rows==null||rows.length()==0) text("No scores here yet. Be the first to post a run.",15,0xFFD1CFDC.toInt()) else for(i in 0 until minOf(5,rows.length())) {
                            val r=rows.getJSONObject(i)
                            if(mode in setOf("COOP_SURVIVAL","PUZZLE","CONNECTION")) {
                                val a=r.optJSONObject("playerA"); val b=r.optJSONObject("playerB")
                                val value=if(mode=="COOP_SURVIVAL") formatSurvivalTime(r.optLong("bestScore")) else "${r.optInt("bestScore")} pts"
                                leaderboardCard("#${i+1}   ${a?.optString("nickname")?:"Player"}  +  ${b?.optString("nickname")?:"Player"}","$value  ·  ${r.optInt("sessions")} runs",i==0)
                            } else {
                                val mine=r.optString("id")==profileCredentials?.profileId
                                val detail=when(mode) { "SOLO_SURVIVAL"->"${formatSurvivalTime(r.optLong("bestScore"))}  ·  ${r.optInt("sessions")} runs"; "DEATHMATCH"->"${r.optInt("rating")} Elo  ·  ${r.optInt("wins")}–${r.optInt("losses")}"; else->"${r.optInt("rating")} Elo" }
                                leaderboardCard("#${i+1}   ${zodiacAvatar(r.optInt("avatarId"))}  ${r.optString("nickname")}${if(mine) "  ·  YOU" else ""}",detail,mine||i==0)
                            }
                        }
                        if(mode=="DEATHMATCH") response.optJSONObject("me")?.let { text("Your rank  #${it.optInt("rank")}  ·  ${it.optInt("rating")} Elo",14,CYAN,true) }
                    }
                    footer(refreshAction={ render(true) },backAction={ showHome() })
                }
            }
            if(source=="local") footer(refreshAction={ render() },backAction={ showHome() })
        }
        renderAction={ refresh -> render(refresh) }
        renderHeaderOnly = {
            root.addView(modeSelector(),LinearLayout.LayoutParams(-1,-2))
            val sourceStrip=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
            pill(sourceStrip,"On this device",false) { source="local"; render() }; pill(sourceStrip,"Online",true) { source="online"; render() }
            root.addView(sourceStrip,LinearLayout.LayoutParams(-1,-2))
            if(mode=="DEATHMATCH") pill(sourceStrip,if(range=="all_time") "All time" else "Weekly",false) { range=if(range=="all_time") "weekly" else "all_time"; render() }
            text("${label(mode).uppercase()} · ONLINE TOP 5${if(range=="weekly") " · THIS WEEK" else ""}",12,CYAN,true)
        }
        render()
    }
    private fun leaderboardCard(title:String,detail:String,highlight:Boolean) {
        val card=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(9),dp(14),dp(9))
            background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(14).toFloat(); setColor(if(highlight) 0xE01E1A2D.toInt() else 0xD910111B.toInt()); setStroke(dp(1),if(highlight) 0xAA9F81E8.toInt() else 0x556A647B) }
            addView(TextView(this@MainActivity).apply { text=title; textSize=15f; setTextColor(if(highlight) 0xFFE4D9FF.toInt() else 0xFFF3F0F8.toInt()); setTypeface(typeface,android.graphics.Typeface.BOLD); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
            addView(TextView(this@MainActivity).apply { text=detail; textSize=13f; setTextColor(0xFFB9B4C8.toInt()); setPadding(0,dp(3),0,0) })
        }
        root.addView(card,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4); bottomMargin=dp(4) })
    }
    private fun footer(refreshAction:()->Unit,backAction:()->Unit) {
        val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        fun actionChip(title:String,action:()->Unit) { val view=TextView(this).apply { text=title; textSize=14f; gravity=Gravity.CENTER; setTextColor(0xFFECE7F8.toInt()); setPadding(dp(18),dp(10),dp(18),dp(10)); background=android.graphics.drawable.GradientDrawable().apply { cornerRadius=dp(18).toFloat(); setColor(0xCC201B2D.toInt()); setStroke(dp(1),0x667F70A0) }; setOnClickListener { playButtonSound(); action() } }; row.addView(view,LinearLayout.LayoutParams(0,-2,1f).apply { leftMargin=dp(4); rightMargin=dp(4) }) }
        actionChip("Refresh",refreshAction); actionChip("Back",backAction)
        root.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(10); bottomMargin=dp(4) })
    }
    private fun localLeaderboard(mode:String):JSONArray = try {
        val stored=getSharedPreferences("psychconnect",MODE_PRIVATE).getString("localLeaderboard_$mode","[]") ?: "[]"
        JSONArray(stored).let { if(it.length()==0 && mode=="SOLO_SURVIVAL" && survivalBestMs>0L) JSONArray().put(JSONObject().put("score",survivalBestMs).put("name","You")) else it }
    } catch(_:Exception) { JSONArray() }
    private fun saveLocalLeaderboard(mode:String,score:Long) {
        if(score<0L) return
        val previous=localLeaderboard(mode); val records=(0 until previous.length()).mapNotNull { previous.optJSONObject(it) }
        val new=JSONObject().put("score",score).put("name",profileCredentials?.nickname?.takeIf { it.isNotBlank() } ?: "You").put("at",System.currentTimeMillis())
        val sorted=(records+new).sortedByDescending { it.optLong("score") }.take(5)
        val output=JSONArray(); sorted.forEach { output.put(it) }
        getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putString("localLeaderboard_$mode",output.toString()).apply()
    }
    private fun showFriendsAndFoes(category:String="FRIENDS") {
        base("friends_and_foes","People you have played with or against")
        val tabs=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER }
        listOf("FRIENDS","FOES","RIVALS").forEach { tab ->
            val b=Button(this).apply { text=tab.lowercase().replaceFirstChar(Char::uppercase); setTextColor(if(category==tab) CYAN else Color.WHITE); setBackgroundColor(0x33201C31) }
            b.setOnClickListener { playButtonSound(); showFriendsAndFoes(tab) }
            tabs.addView(b,LinearLayout.LayoutParams(0,dp(44),1f))
        }
        root.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(8) })
        text("Loading…",14,CYAN)
        val endpoint=if(category=="FRIENDS") "/api/relationships" else "/api/rivals"
        apiRequest(endpoint) { response,error -> if(screen!="friends_and_foes") return@apiRequest; root.removeAllViews(); root.addView(tabs,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8); bottomMargin=dp(8) })
            if(error!=null||response==null) text(error ?: "Unavailable",15,0xFFFFA0A0.toInt()) else when(category) {
                "FRIENDS" -> {
                    text("Friends · Together and Harmony partners",13,CYAN,true)
                    val rows=response.optJSONArray("friends")
                    if(rows==null||rows.length()==0) text("Completed Together or Harmony sessions will appear here.",15,0xFFD1CFDC.toInt()) else for(i in 0 until rows.length()) {
                        val p=rows.getJSONObject(i)
                        text("${zodiacAvatar(p.optInt("avatarId"))} ${p.optString("nickname")}\nTogether ${p.optInt("togetherMatches")} · Harmony ${p.optInt("harmonySessions")} · Last played ${p.optString("lastPlayedAt").take(10)}",15,0xFFF0EDF8.toInt())
                    }
                }
                else -> {
                    text(if(category=="FOES") "Foes · Versus opponents" else "Rivals · your most frequent opponents",13,CYAN,true)
                    val rows=response.optJSONArray("entries")
                    if(rows==null||rows.length()==0) text("Your first completed Versus match creates an opponent entry here.",15,0xFFD1CFDC.toInt()) else {
                        val limit=if(category=="RIVALS") minOf(rows.length(),5) else rows.length()
                        for(i in 0 until limit) { val rival=rows.getJSONObject(i); button("${zodiacAvatar(rival.optInt("avatarId"))} ${rival.optString("nickname")} · ${rival.optInt("myWins")}–${rival.optInt("theirWins")} · ${rival.optInt("matches")} matches · ${rival.optString("presence")}") { showRivalDetail(rival) } }
                    }
                    response.optJSONObject("categories")?.let { cats ->
                        val most=cats.optJSONObject("mostPlayed")
                        val closest=cats.optJSONObject("closest")
                        if(most!=null) text("Most played · ${most.optString("nickname")}",12,0xFFB9B1CA.toInt())
                        if(closest!=null) text("Closest stats · ${closest.optString("nickname")}",12,0xFFB9B1CA.toInt())
                    }
                }
            }
            button("Back") { showHome() }
            button("Refresh") { showFriendsAndFoes(category) }
        }
    }
    private fun showRivals() = showFriendsAndFoes("RIVALS")
    private fun showChallenges() {
        base("challenges","Invitations from rivals expire after two minutes")
        text("Loading invitations…",14,CYAN)
        apiRequest("/api/challenges") { response,error -> if(screen!="challenges") return@apiRequest; root.removeAllViews()
            if(error!=null||response==null) text(error ?: "Unavailable",15,0xFFFFA0A0.toInt()) else {
                val rows=response.optJSONArray("challenges")
                if(rows==null||rows.length()==0) text("No pending challenges.",15,0xFFD1CFDC.toInt()) else for(i in 0 until rows.length()) {
                    val challenge=rows.getJSONObject(i); val mode=challenge.optString("mode","DEATHMATCH"); val halves=challenge.optInt("halves",2)
                    val format=if(mode=="DEATHMATCH") " · $halves halves" else ""
                    text("${challenge.optString("nickname")} · ${modeTitle(mode)} · ${challenge.optInt("duration_seconds")}s$format",15,PURPLE,true)
                    button("Accept") { apiRequest("/api/challenges/${challenge.optString("id")}/respond","POST",JSONObject().put("accept",true)) { result,e -> if(e!=null) toastOnline(e) else { val data=result?.optJSONObject("challenge") ?: return@apiRequest; launchProfileMatch(true,data.optString("roomCode"),data.optString("mode"),data.optInt("durationSeconds",60),data.optInt("halves",2)) } } }
                    button("Decline") { apiRequest("/api/challenges/${challenge.optString("id")}/respond","POST",JSONObject().put("accept",false)) { _,e -> if(e!=null) toastOnline(e) else showChallenges() } }
                }
            }
            button("Back to profile") { showOnlineHome() }
        }
    }
    private fun showRivalDetail(rival:JSONObject) {
        base("rival_detail","Rival profile")
        val won=rival.optInt("myWins"); val lost=rival.optInt("theirWins"); val total=(won+lost+rival.optInt("draws")).coerceAtLeast(1)
        val streak=rival.optInt("currentStreak")
        val streakLabel=when { streak>0 -> "You have a $streak win streak"; streak<0 -> "${rival.optString("nickname")} has a ${-streak} win streak"; else -> "No active win streak" }
        text("YOU vs ${rival.optString("nickname").uppercase()}",22,PURPLE,true)
        text("$won wins — $lost losses — ${rival.optInt("draws")} draws\n${rival.optInt("matches")} matches played · Elo ${rival.optInt("rating")}\nLast encounter: ${rival.optString("lastWinner")} · $streakLabel",16,0xFFF0EDF8.toInt())
        val bar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=total; progress=won }; root.addView(bar,LinearLayout.LayoutParams(-1,dp(10)).apply { topMargin=dp(8); bottomMargin=dp(8) })
        button("Challenge your rival…") { showChallengeSetup(rival) }
        button("Back to Friends & Foes") { showFriendsAndFoes("RIVALS") }
    }
    private fun showChallengeSetup(rival:JSONObject) {
        val modes=arrayOf("Versus","Together","Harmony")
        val modeCodes=arrayOf("DEATHMATCH","CONNECTION","HARMONY")
        val durations=arrayOf("30 seconds","45 seconds","60 seconds")
        val durationValues=intArrayOf(30,45,60)
        val halves=arrayOf("2 halves","4 halves")
        val halfValues=intArrayOf(2,4)
        fun spinner(items:Array<String>)=Spinner(this).apply {
            adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,items)
        }
        val mode=spinner(modes); val duration=spinner(durations); val half=spinner(halves)
        val modeLabel=TextView(this).apply { text="Game mode"; setTextColor(Color.WHITE) }
        val durationLabel=TextView(this).apply { text="Round duration"; setTextColor(Color.WHITE) }
        val halfLabel=TextView(this).apply { text="Match length"; setTextColor(Color.WHITE) }
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),0)
            addView(modeLabel); addView(mode); addView(durationLabel); addView(duration)
            addView(halfLabel); addView(half)
        }
        fun updateHalves() {
            val visible=mode.selectedItemPosition==0
            halfLabel.visibility=if(visible) View.VISIBLE else View.GONE
            half.visibility=if(visible) View.VISIBLE else View.GONE
        }
        mode.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long)=updateHalves()
            override fun onNothingSelected(parent:AdapterView<*>?)=Unit
        }
        AlertDialog.Builder(this).setTitle("Challenge ${rival.optString("nickname")}")
            .setView(content).setNegativeButton("Cancel",null)
            .setPositiveButton("Send challenge",null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val selectedMode=modeCodes[mode.selectedItemPosition]
                        val settings=JSONObject().put("rivalId",rival.optString("id"))
                            .put("mode",selectedMode)
                            .put("durationSeconds",durationValues[duration.selectedItemPosition])
                            .put("halves",halfValues[half.selectedItemPosition])
                        apiRequest("/api/challenges","POST",settings) { _,error ->
                            if(error!=null) toastOnline(error) else toastOnline("${modeTitle(selectedMode)} challenge sent. It expires in two minutes.")
                        }
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }
    private fun showAchievements() {
        base("achievements","Rivalry milestones")
        apiRequest("/api/achievements") { response,error -> if(screen!="achievements") return@apiRequest; root.removeAllViews();
            if(error!=null||response==null) text(error ?: "Unavailable",15,0xFFFFA0A0.toInt()) else { val rows=response.optJSONArray("achievements"); if(rows==null||rows.length()==0) text("Achievements unlock as you play Versus matches with identified players.",15,0xFFD1CFDC.toInt()) else for(i in 0 until rows.length()) { val a=rows.getJSONObject(i); text("${a.optString("title")} · ${a.optString("rival_nickname")}\n${a.optString("description")}",14,CYAN) } }
            button("Back") { showOnlineHome() }
        }
    }
    private fun showMatchHistory() {
        base("match_history","Your latest completed online matches")
        text("Loading match history…",14,CYAN)
        apiRequest("/api/matches?limit=50") { response,error -> if(screen!="match_history") return@apiRequest; root.removeAllViews()
            if(error!=null||response==null) text(error ?: "Unavailable",15,0xFFFFA0A0.toInt()) else {
                val matches=response.optJSONArray("matches")
                if(matches==null||matches.length()==0) text("Completed online matches appear here.",15,0xFFD1CFDC.toInt()) else for(i in 0 until matches.length()) {
                    val m=matches.getJSONObject(i); val mode=when(m.optString("mode")) { "DEATHMATCH"->"Versus"; "CONNECTION"->"Together"; else->"Harmony" }
                    val result=when(m.optString("result")) { "win"->"Win"; "loss"->"Loss"; "draw"->"Draw"; else->"Co-op" }
                    val detail=if(mode=="Together") "Score ${m.optInt("shared_score")} · Sync ${m.optDouble("sync_seconds").toInt()}s" else "Score ${m.optInt("score")} · Elo ${m.optInt("rating_after")}"
                    text("$mode · $result\n${m.optString("opponent_nickname","Partner")} · $detail",14,0xFFF0EDF8.toInt())
                }
            }
            button("Back to profile") { showOnlineHome() }
        }
    }
    private fun toastOnline(message:String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
    private fun onPresenceMessage(message:JSONObject) {
        when(message.optString("type")) {
            "profile_auth_failed" -> { profileReady=false; toastOnline("Profile sign-in failed. Restore your profile from Settings.") }
            "challenge_received" -> {
                val challenge=message.optJSONObject("challenge") ?: return
                val challenger=challenge.optJSONObject("challenger")?.optString("nickname") ?: "A rival"
                if(!activityResumed) return
                AlertDialog.Builder(this).setTitle("Rival challenge")
                    .setMessage("$challenger challenged you to ${modeTitle(challenge.optString("mode","DEATHMATCH"))} · ${challenge.optInt("durationSeconds",60)} seconds${if(challenge.optString("mode")=="DEATHMATCH") " · ${challenge.optInt("halves",2)} halves" else ""}. Accept before the invitation expires?")
                    .setPositiveButton("Accept") { _,_ -> apiRequest("/api/challenges/${challenge.optString("id")}/respond","POST",JSONObject().put("accept",true)) { result,error ->
                        if(error!=null) toastOnline(error) else { val data=result?.optJSONObject("challenge") ?: return@apiRequest; launchProfileMatch(true,data.optString("roomCode"),data.optString("mode"),data.optInt("durationSeconds",60),data.optInt("halves",2)) }
                    } }.setNegativeButton("Decline") { _,_ -> apiRequest("/api/challenges/${challenge.optString("id")}/respond","POST",JSONObject().put("accept",false)) { _,error -> if(error!=null) toastOnline(error) } }.show()
            }
            "challenge_accepted" -> launchProfileMatch(false,message.optString("code"),message.optString("mode"),message.optInt("durationSec",60),message.optInt("matchHalves",2))
            "challenge_declined" -> toastOnline("Your rival declined the challenge.")
            "arcade_leaderboard_saved" -> toastOnline("${if(message.optString("mode")=="COOP_SURVIVAL") "Co-op Survival" else "Puzzle"} result added to the online leaderboard.")
            "achievement_unlocked" -> toastOnline("Achievement unlocked: ${message.optString("code").replace('_',' ')}")
        }
    }
    private fun launchProfileMatch(host:Boolean,code:String,mode:String,duration:Int,halves:Int) {
        if(code.isBlank()) { toastOnline("Challenge session code is missing."); return }
        if(host&&mode=="HARMONY") {
            val haptics=CheckBox(this).apply { text="Offer shared haptics"; setTextColor(0xFFCECDD6.toInt()) }
            val trails=CheckBox(this).apply { text="Offer shared touch trails"; setTextColor(0xFFCECDD6.toInt()) }
            val options=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),0); addView(haptics); addView(trails) }
            AlertDialog.Builder(this).setTitle("Harmony sharing")
                .setMessage("Your rival can accept or decline each option. Harmony still works if they decline.")
                .setView(options)
                .setPositiveButton("Continue") { _,_ -> connectProfileMatch(host,code,mode,duration,halves,haptics.isChecked,trails.isChecked) }
                .setCancelable(false).show()
            return
        }
        connectProfileMatch(host,code,mode,duration,halves,false,false)
    }
    private fun connectProfileMatch(host:Boolean,code:String,mode:String,duration:Int,halves:Int,allowHarmonyHaptics:Boolean,allowHarmonyTrails:Boolean=false) {
        gameMode=mode; durationSec=duration; matchHalves=halves; isHost=host; roomCode=code
        hostAllowsHarmonyHaptics=host&&mode=="HARMONY"&&allowHarmonyHaptics; hostAllowsHarmonyTrails=host&&mode=="HARMONY"&&allowHarmonyTrails; harmonySharedHaptics=false; harmonySharedTrails=false
        val prefs=getSharedPreferences("psychconnect",MODE_PRIVATE); serverUrl=prefs.getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!
        matchFinished=false; duelScores.clear(); client.connect(serverUrl,if(host) "host" else "join",code,durationSec,gameMode,matchHalves,profileCredentials?.profileId,profileCredentials?.token,hostAllowsHarmonyHaptics,hostAllowsHarmonyTrails)
        screen="waiting"; showWaiting()
    }
    private fun applyPuzzleLevel(message:JSONObject) {
        puzzleLevel=message.optInt("level",1); puzzleScore=message.optInt("score",1_000); puzzleTotalScore=message.optInt("totalScore",puzzleTotalScore)
        puzzleRemainingMs=message.optLong("remainingMs",60_000L).coerceIn(0L,60_000L); puzzleTimeAtMs=SystemClock.elapsedRealtime()
        puzzleStarted=false; puzzleTimerStarted=false; puzzleProjectiles=emptyList(); puzzleBlasts=emptyList(); puzzleMotionFactor=0.0
        val start=message.optJSONObject("start")
        if(start!=null) puzzleStart=Point(start.optDouble("x",.5),start.optDouble("y",.89))
        val walls=mutableListOf<PuzzleWall>(); val encodedWalls=message.optJSONArray("walls")
        if(encodedWalls!=null) for(i in 0 until encodedWalls.length()) {
            val wall=encodedWalls.optJSONObject(i)?:continue
            walls.add(PuzzleWall(wall.optDouble("x1"),wall.optDouble("y1"),wall.optDouble("x2"),wall.optDouble("y2")))
        }
        val checkpoints=mutableListOf<Point>(); val encodedCheckpoints=message.optJSONArray("checkpoints")
        if(encodedCheckpoints!=null) for(i in 0 until encodedCheckpoints.length()) {
            val point=encodedCheckpoints.optJSONObject(i)?:continue
            checkpoints.add(Point(point.optDouble("x"),point.optDouble("y")))
        }
        puzzleWalls=walls; puzzleCheckpoints=checkpoints; puzzleNeedsStart=true; puzzleStarted=false
        puzzleHoldMs=0L; puzzleCheckpointAccepted=false
        haptics.stop(); hapticsRunning=false
        if(::pad.isInitialized) pad.invalidate()
    }
    private fun applyPuzzleHazards(message:JSONObject) {
        puzzleRemainingMs=message.optLong("remainingMs",puzzleRemainingMs).coerceIn(0L,60_000L)
        puzzleTimeAtMs=SystemClock.elapsedRealtime(); puzzleMotionFactor=message.optDouble("motionFactor",0.0).coerceIn(0.0,1.0)
        val projectiles=mutableListOf<SurvivalProjectile>(); val encodedProjectiles=message.optJSONArray("projectiles")
        if(encodedProjectiles!=null) for(i in 0 until encodedProjectiles.length()) {
            val item=encodedProjectiles.optJSONObject(i)?:continue
            projectiles.add(SurvivalProjectile(Point(item.optDouble("x"),item.optDouble("y")),item.optDouble("vx"),item.optDouble("vy")))
        }
        val blasts=mutableListOf<PuzzleBlast>(); val encodedBlasts=message.optJSONArray("blasts")
        if(encodedBlasts!=null) for(i in 0 until encodedBlasts.length()) {
            val item=encodedBlasts.optJSONObject(i)?:continue
            blasts.add(PuzzleBlast(Point(item.optDouble("x"),item.optDouble("y")),item.optDouble("radius").coerceIn(0.0,1.0),item.optDouble("maxRadius",.72).coerceIn(.1,1.0),item.optBoolean("armed")))
        }
        puzzleProjectiles=projectiles; puzzleBlasts=blasts
        if(::pad.isInitialized) pad.invalidate()
    }
    private fun applyDuoSurvivalState(message:JSONObject) {
        duoSurvivalStarted=message.optBoolean("started",duoSurvivalStarted)
        duoReadyCount=message.optInt("readyCount",duoReadyCount)
        duoSurvivalElapsedMs=message.optLong("elapsedMs",duoSurvivalElapsedMs)
        duoSurvivalLevel=message.optInt("level",duoSurvivalLevel)
        duoStateAtMs=SystemClock.elapsedRealtime()
        val speedFactor=message.optDouble("speedFactor",1.0).coerceIn(1.0,2.35)
        val motionFactor=message.optDouble("motionFactor",1.0).coerceIn(0.0,1.0)
        duoSurvivalMotionFactor=motionFactor
        val projectiles=mutableListOf<SurvivalProjectile>(); val encodedProjectiles=message.optJSONArray("projectiles")
        if(encodedProjectiles!=null) for(i in 0 until encodedProjectiles.length()) {
            val item=encodedProjectiles.optJSONObject(i)?:continue
            projectiles.add(SurvivalProjectile(Point(item.optDouble("x"),item.optDouble("y")),item.optDouble("vx")*speedFactor*motionFactor,item.optDouble("vy")*speedFactor*motionFactor))
        }
        val blasts=mutableListOf<DuoBlast>(); val encodedBlasts=message.optJSONArray("blasts"); val now=SystemClock.elapsedRealtime()
        if(encodedBlasts!=null) for(i in 0 until encodedBlasts.length()) {
            val item=encodedBlasts.optJSONObject(i)?:continue
            blasts.add(DuoBlast(Point(item.optDouble("x"),item.optDouble("y")),item.optDouble("radius").coerceIn(0.0,1.0),item.optDouble("maxRadius",.78),item.optBoolean("armed"),item.optLong("remainingMs").coerceAtLeast(0L),item.optLong("durationMs",2300L).coerceAtLeast(1L),motionFactor,now))
        }
        duoProjectiles=projectiles; duoBlasts=blasts
        if(::pad.isInitialized) pad.invalidate()
    }
    private fun finishDuoSurvival(elapsedMs:Long) {
        if(screen!="game") return
        duoSurvivalElapsedMs=elapsedMs.coerceAtLeast(0L); duoSurvivalStarted=false
        roundInputGate.close(); haptics.stop(); hapticsRunning=false; window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.removeCallbacks(tick)
        val newBest=duoSurvivalElapsedMs>duoSurvivalBestMs
        duoSurvivalBestMs=maxOf(duoSurvivalBestMs,duoSurvivalElapsedMs)
        getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putLong("duoSurvivalHighScoreMs",duoSurvivalBestMs).apply()
        saveLocalLeaderboard("COOP_SURVIVAL",duoSurvivalElapsedMs)
        roundState.finish(true); screen="result"; base("result","Co-op Survival run over")
        text("SHARED SURVIVAL TIME",17,0xFFFFA478.toInt(),true)
        text(formatSurvivalTime(duoSurvivalElapsedMs),54,0xFFFF604F.toInt(),true)
        text(if(newBest) "NEW LOCAL PAIR BEST" else "LOCAL PAIR BEST  ·  ${formatSurvivalTime(duoSurvivalBestMs)}",14,0xFFFFBA70.toInt(),true)
        button("Run together again") { client.rematch(); showRematchWaiting() }
        button("Main menu") { client.leaveSession(); gameMode="DEATHMATCH"; showHome() }
    }
    private fun finishPuzzleRun(message:JSONObject) {
        if(screen!="game") return
        puzzleScore=message.optInt("levelScore",puzzleScore)
        puzzleTotalScore=message.optInt("totalScore",puzzleTotalScore)
        saveLocalLeaderboard("PUZZLE",puzzleTotalScore.toLong())
        puzzleTimerStarted=false; puzzleStarted=false; puzzleNeedsStart=true
        puzzleProjectiles=emptyList(); puzzleBlasts=emptyList(); puzzleMotionFactor=0.0
        roundInputGate.close(); haptics.stop(); hapticsRunning=false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.removeCallbacks(tick)
        roundState.finish(true); screen="result"; base("result","Puzzle run over")
        text("TOTAL SCORE",16,0xFF9CE9DD.toInt(),true)
        text(puzzleTotalScore.toString(),54,0xFFE2D9FF.toInt(),true)
        val reason=when(message.optString("reason")) { "time"->"Time ran out"; "score"->"Level buffer reached zero"; else->"Run complete" }
        text("$reason · reached level ${message.optInt("level",puzzleLevel)}",15,0xFFBAB6C7.toInt())
        text("Unbanked level buffer: ${puzzleScore} points",13,0xFF8F8B9D.toInt())
        button("Try again") { client.rematch(); showRematchWaiting() }
        button("Main menu") { client.leaveSession(); gameMode="DEATHMATCH"; showHome() }
    }
    private fun showHome() {
        base("home")
        val homeScale=homeArtworkScale()
        if(homeScale>1f) {
            root.post {
                root.pivotX=root.width/2f
                root.pivotY=root.height.toFloat()
                root.scaleX=homeScale
                root.scaleY=homeScale
            }
        }
        // The hands artwork contains the PsychConnect wordmark; place the tagline above the figures.
        val tagline=TextView(this).apply { text="Two minds. One connection."; textSize=15f; setTextColor(0xFFE2DDF0.toInt()); gravity=Gravity.CENTER; setShadowLayer(dp(8).toFloat(),0f,0f,0xCC090A10.toInt()) }
        screenFrame.addView(tagline,FrameLayout.LayoutParams(-1,dp(36),Gravity.TOP).apply { topMargin=(resources.displayMetrics.heightPixels*.17f).toInt()+dp(26) })
        val profileButton=FrameLayout(this).apply {
            isClickable=true; isFocusable=true; contentDescription="Profile"
            val artwork=SpriteImageView(this@MainActivity,assetBitmap(if(profileCredentials==null) "profile_button" else "zodiac_${(profileData?.optInt("avatarId",profileCredentials?.avatarId ?: 0) ?: profileCredentials?.avatarId ?: 0).coerceIn(0,11).toString().padStart(2,'0')}" )).apply {
                tag="profile_shortcut"; scaleType=ImageView.ScaleType.FIT_XY
            }
            addView(artwork,FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER))
            addView(TextView(this@MainActivity).apply {
                tag="profile_shortcut_name"; visibility=if(profileCredentials==null) View.GONE else View.VISIBLE
                text=profileData?.optString("nickname")?.takeIf { it.isNotBlank() } ?: profileCredentials?.nickname.orEmpty()
                setTextColor(0xFFFFFFFF.toInt()); setTypeface(typeface,android.graphics.Typeface.BOLD); gravity=Gravity.CENTER
                maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,when(text.length) { in 0..7->8f; in 8..12->7f; else->5.5f })
                setShadowLayer(dp(2).toFloat(),0f,dp(1).toFloat(),0xDD090A10.toInt())
            },FrameLayout.LayoutParams(-1,dp(15),Gravity.TOP).apply { topMargin=dp(48); leftMargin=dp(10); rightMargin=dp(10) })
            addView(View(this@MainActivity).apply {
                tag="profile_shortcut_presence"; visibility=if(profileCredentials==null) View.GONE else View.VISIBLE
                background=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL; setColor(if(profileReady) CYAN else 0xFFFFC16D.toInt()); setStroke(dp(1),0xFF090A10.toInt()) }
                elevation=dp(2).toFloat()
            },FrameLayout.LayoutParams(dp(8),dp(8),Gravity.TOP or Gravity.END).apply { topMargin=dp(8); rightMargin=dp(8) })
            tag="profile_button_container"; contentDescription=if(profileReady) "Profile" else "Profile (offline)"
            setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { (findViewWithTag<View>("profile_shortcut") as? SpriteImageView)?.setPressedHighlight(true); view.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { (findViewWithTag<View>("profile_shortcut") as? SpriteImageView)?.setPressedHighlight(false); view.animate().scaleX(1f).scaleY(1f).setDuration(130).start() }
                }
                false
            }
            setOnClickListener { playButtonSound(); showOnlineHome() }
        }
        profileButton.translationY=dp(29).toFloat()
        profileButton.elevation=dp(2).toFloat()
        root.addView(profileButton,LinearLayout.LayoutParams(dp(72),dp(72)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(1) })
        val electricTargets=mutableListOf<View>(profileButton)
        val modeWidth=if(homeScale>1f) dp(500) else -1
        val modeHeight=if(homeScale>1f) dp(72) else dp(60)
        val modeButtons=listOf("Versus" to "DEATHMATCH","Together" to "CONNECTION","Harmony" to "HARMONY")
        modeButtons.forEachIndexed { index,(label,mode) ->
            val sprite=SpriteImageView(this,assetBitmap("mode_${(index*3+1).toString().padStart(2,'0')}"))
            sprite.contentDescription=label
            sprite.isClickable=true; sprite.isFocusable=true
            sprite.translationY=dp(listOf(22,14,7)[index]).toFloat()
            sprite.setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { (view as? SpriteImageView)?.setPressedHighlight(true); view.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { (view as? SpriteImageView)?.setPressedHighlight(false); view.animate().scaleX(1f).scaleY(1f).setDuration(130).start() }
                }
                false
            }
            sprite.setOnClickListener { playButtonSound(); gameMode=mode; showingTogetherChoices=(mode=="CONNECTION"); showModeMenu() }
            root.addView(sprite,LinearLayout.LayoutParams(modeWidth,modeHeight).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=dp(2); bottomMargin=dp(2) })
            electricTargets.add(sprite)
        }
        val survivalButton=FrameLayout(this).apply {
            addView(SpriteImageView(this@MainActivity,assetBitmap("blank_large_button")),FrameLayout.LayoutParams(-1,-1))
            addView(FlameGradientTextView(this@MainActivity).apply {
                text="Survival"; textSize=20f; setTextColor(Color.WHITE); gravity=Gravity.CENTER
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.BOLD)
                setShadowLayer(dp(7).toFloat(),0f,0f,0xFFFF7043.toInt())
            },FrameLayout.LayoutParams(-1,-1))
            isClickable=true; isFocusable=true; contentDescription="Solo Survival"
            setOnTouchListener { view,event ->
                when(event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { view.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                    MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                false
            }
            setOnClickListener { playButtonSound(); gameMode="SURVIVAL"; showingTogetherChoices=false; showModeMenu() }
        }
        root.addView(survivalButton,LinearLayout.LayoutParams(modeWidth,modeHeight).apply { gravity=Gravity.CENTER_HORIZONTAL; topMargin=-dp(10); bottomMargin=dp(2) })
        electricTargets.add(survivalButton)
        val secondaryGrid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,dp(3),0,0) }
        val entries=listOf("Settings","Rules","Buy Me a Coffee","Quit","Leaderboards","Friends & Foes")
        for(row in 0..2) {
            val line=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            for(col in 0..1) {
                val index=row*2+col
                val label=entries[index]
                val sprite:View=if(index<4) SpriteImageView(this,assetBitmap("secondary_${(index*3+1).toString().padStart(2,'0')}")) else FrameLayout(this).apply {
                    val image=SpriteImageView(this@MainActivity,assetBitmap("blank_large_button"))
                    addView(image,FrameLayout.LayoutParams(-1,-1))
                    addView(TextView(this@MainActivity).apply { text=label; textSize=12f; setTextColor(0xFFF5F2FF.toInt()); gravity=Gravity.CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
                }
                sprite.contentDescription=label
                sprite.isClickable=true; sprite.isFocusable=true
                sprite.setOnTouchListener { view,event ->
                    when(event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { (view as? SpriteImageView)?.setPressedHighlight(true); view.animate().scaleX(.985f).scaleY(.985f).setDuration(90).start(); view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                        MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> { (view as? SpriteImageView)?.setPressedHighlight(false); view.animate().scaleX(1f).scaleY(1f).setDuration(130).start() }
                    }
                    false
                }
                sprite.setOnClickListener { playButtonSound(); when(label) { "Settings"->showSettings(); "Rules"->showRules(); "Buy Me a Coffee"->openSupport(); "Quit"->finish(); "Leaderboards"->showLeaderboards(); "Friends & Foes"->showFriendsAndFoes() } }
                line.addView(sprite,LinearLayout.LayoutParams(0,dp(42),1f).apply { if(col==0) rightMargin=dp(4) else leftMargin=dp(4) })
                electricTargets.add(sprite)
            }
            secondaryGrid.addView(line,LinearLayout.LayoutParams(-1,dp(42)))
        }
        root.addView(secondaryGrid,LinearLayout.LayoutParams(if(homeScale>1f) dp(540) else -1,-2).apply { gravity=Gravity.CENTER_HORIZONTAL })
        energyOverlay=MenuElectricOverlay(this,electricTargets).also { screenFrame.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        val version=try { packageManager.getPackageInfo(packageName,0).versionName ?: "" } catch (_:Exception) { "" }
        text("v$version",11,0xFF888397.toInt())
    }
    private fun modeName(mode:String)=when(mode) { "CONNECTION"->"2 Minds, 1 Connection"; "PUZZLE"->"Puzzle"; "HARMONY"->"Harmony sandbox"; "SURVIVAL"->"Solo Survival"; "COOP_SURVIVAL"->"Co-op Survival"; else->"Deathmatch" }
    private fun modeTitle(mode:String)=when(mode) { "CONNECTION"->"Together"; "PUZZLE"->"Puzzle"; "HARMONY"->"Harmony"; "SURVIVAL","COOP_SURVIVAL"->"Survival"; else->"Versus" }
    private var modeRoomCode:EditText?=null
    private var showingTogetherChoices=false
    private fun showModeMenu(enteringRoomCode:Boolean=false) {
        base("mode", modeTitle(gameMode))
        val description=when(gameMode) {
            "CONNECTION" -> if(showingTogetherChoices) "Choose a cooperative challenge: survive together or solve a maze." else "Work together to find and maintain connection."
            "PUZZLE" -> "Guide one another through a glowing maze. Find separate checkpoints, hold them together, and reach the next level."
            "HARMONY" -> "An experimental sensory mode focused on calm synchronization and connection."
            "SURVIVAL" -> "A solo run against escalating projectiles and soft expanding blast waves."
            "COOP_SURVIVAL" -> "Share one survival run. Both players dodge the same escalating blasts and projectiles; one hit ends the run for both."
            else -> "One leads, one follows. Outsmart your opponent through movement and distance."
        }
        text(description,16,0xFFD1CFDC.toInt())
        if(gameMode=="CONNECTION"&&showingTogetherChoices) {
            artworkButton("2 Player Survival","blank_large_button",72) { gameMode="COOP_SURVIVAL"; showModeMenu() }
            artworkButton("Puzzle","blank_large_button",72) { gameMode="PUZZLE"; showModeMenu() }
            artworkButton("Back","blank_small_button",54) { showingTogetherChoices=false; showHome() }
        } else if(gameMode=="SURVIVAL") {
            text("PERSONAL BEST  ·  ${formatSurvivalTime(survivalBestMs)}",18,0xFFFF9A68.toInt(),true)
            artworkButton("Solo Survival","blank_large_button",64) { startSurvivalRun() }
            artworkButton("Leaderboard","blank_large_button",62) { showLeaderboards("SOLO_SURVIVAL") }
            artworkButton("Back","blank_small_button",54) { gameMode="DEATHMATCH"; showHome() }
        } else if(gameMode in setOf("COOP_SURVIVAL","PUZZLE")) {
            if(gameMode=="COOP_SURVIVAL") text("LOCAL PAIR BEST  ·  ${formatSurvivalTime(duoSurvivalBestMs)}",16,0xFFFF9A68.toInt(),true)
            if(enteringRoomCode) {
                modeRoomCode=artworkCodeField()
                artworkButton("Connect","blank_large_button",72) {
                    val code=modeRoomCode?.text?.toString()?.trim()?.uppercase().orEmpty()
                    if(code.isBlank()) { modeRoomCode?.error="Enter the room code"; modeRoomCode?.requestFocus() }
                    else connectionDialog(false,code)
                }
                artworkButton("Back","blank_small_button",54) { modeRoomCode=null; showModeMenu() }
            } else {
                val title=if(gameMode=="COOP_SURVIVAL") "Co-op Survival" else "Puzzle"
                artworkButton("Host $title","blank_large_button",72) { connectionDialog(true) }
                artworkButton("Join $title","blank_large_button",72) { showModeMenu(enteringRoomCode=true) }
                artworkButton("Back","blank_small_button",54) { gameMode="CONNECTION"; showingTogetherChoices=true; showModeMenu() }
            }
        } else if(enteringRoomCode) {
            modeRoomCode=artworkCodeField()
            artworkButton("Connect","blank_large_button",72) {
                val code=modeRoomCode?.text?.toString()?.trim()?.uppercase().orEmpty()
                if(code.isBlank()) { modeRoomCode?.error="Enter the room code"; modeRoomCode?.requestFocus() }
                else connectionDialog(false,code)
            }
            artworkButton("Back","blank_small_button",54) { modeRoomCode=null; showModeMenu() }
        } else {
            modeRoomCode=null
            artworkButton("Host Game","blank_large_button",72) { connectionDialog(true) }
            artworkButton("Join Game","blank_large_button",72) { showModeMenu(enteringRoomCode=true) }
            artworkButton("Back","blank_small_button",54) { showHome() }
        }
        if(gameMode=="HARMONY") button("Offline haptic simulation") { beginGame(true) }
    }
    private fun artworkCodeField():EditText {
        val image=SpriteImageView(this,assetBitmap("blank_room_code_field")).apply { scaleType=ImageView.ScaleType.FIT_XY }
        val code=EditText(this).apply {
            hint="Room Code"; setSingleLine(true); textSize=17f; gravity=Gravity.CENTER
            setTextColor(0xFFF3F0FF.toInt()); setHintTextColor(0xFFB9B3CC.toInt()); setPadding(dp(18),0,dp(18),0)
            background=null; imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        val field=FrameLayout(this).apply { addView(image,FrameLayout.LayoutParams(-1,-1)); addView(code,FrameLayout.LayoutParams(-1,-1)) }
        root.addView(field,LinearLayout.LayoutParams(-1,dp(66)).apply { topMargin=dp(10); bottomMargin=dp(6) })
        return code
    }
    private fun showRules() {
        base("rules", "How to play ${modeName(gameMode)}")
        when(gameMode) {
            "CONNECTION" -> {
                text("2 Minds, 1 Connection",20,PURPLE,true)
                text("Both players keep one finger on the screen and move together. The heartbeat speeds up or slows down according to the selected tempo setting. Close alignment raises the shared score and sync; points fade while either player stays still. There is no winner or loser.",16,0xFFD1CFDC.toInt())
            }
            "PUZZLE" -> {
                text("Puzzle",20,PURPLE,true)
                text("Start together on the lower plate. Each maze has 60 seconds. Reach different checkpoints without seeing the other player's finger, then hold both for 2.5 seconds. Each level starts with a shared 1,000-point buffer. A wall, projectile, blast, or lifted finger costs 200 buffer points and sends both players back to start; the buffer does not drain with time. Zero buffer or zero time ends the run. Clearing a level banks the remaining buffer plus 100 points per full second left. A hit-free clear adds a separate 1,000-point Flawless bonus. Your total carries into the next level.",16,0xFFD1CFDC.toInt())
            }
            "HARMONY" -> {
                text("Harmony",20,PURPLE,true)
                text("Explore the sensory sandbox freely. Move, pause, and notice how touch points respond. Shared haptics are optional: the host may offer them and the joining player can accept or decline. Either way, Harmony still works.",16,0xFFD1CFDC.toInt())
            }
            "SURVIVAL" -> {
                text("Survival · Solo",20,0xFFFF785F.toInt(),true)
                text("Move one finger to dodge incoming projectiles and expanding blast waves. Both the hazards and survival timer pause when you hold still; difficulty rises with active survival time. Your active survival time is your score.",16,0xFFD1CFDC.toInt())
            }
            "COOP_SURVIVAL" -> {
                text("Survival · Co-op",20,0xFFFF785F.toInt(),true)
                text("Both players face the same escalating projectiles and expanding blast waves. Hazards and the shared timer pause while both are still; either player's movement advances them. The first player hit ends the run; active survival time is the score.",16,0xFFD1CFDC.toInt())
            }
            else -> {
                text("Versus · Deathmatch",20,PURPLE,true)
                text("The Runner leads and scores by keeping distance. The Chaser follows the heartbeat and scores by getting close. Roles alternate each half. The Runner sees the Chaser; the Chaser can spend up to two sonar taps per round to reveal the Runner briefly. The Runner can place two hidden decoys with a second finger. A sonar reveals the real position and one waiting decoy together; the decoy then fades away. Choose 30, 45, or 60 seconds per half and a two- or four-half match when hosting.",16,0xFFD1CFDC.toInt())
            }
        }
        button("Back") { showModeMenu() }
    }
    private fun openSupport() {
        val url=com.psychconnect.BuildConfig.SUPPORT_URL.trim()
        if(url.isBlank()) {
            AlertDialog.Builder(this).setTitle("Support PsychConnect")
                .setMessage("The support link has not been configured yet.")
                .setPositiveButton("OK",null).show()
            return
        }
        try {
            val uri=Uri.parse(url)
            if(uri.scheme!="https" && uri.scheme!="http") error("Unsupported support URL")
            startActivity(Intent(Intent.ACTION_VIEW,uri))
        } catch (_:Exception) { Toast.makeText(this,"The configured support link is invalid",Toast.LENGTH_LONG).show() }
    }
    private fun connectionDialog(host: Boolean, joinCode:String?=null) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(24,8,24,0) }
        val emulator=Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("Emulator",ignoreCase=true)
        val defaultUrl=if(emulator) "ws://10.0.2.2:8787" else "wss://psychconnect-09dn.onrender.com"
        val url=EditText(this).apply { hint="Server URL (wss://...)"; setSingleLine(); setText(getSharedPreferences("psychconnect",MODE_PRIVATE).getString("serverUrl",defaultUrl)) }
        val code=EditText(this).apply { hint="Session code"; setSingleLine(); setText(joinCode.orEmpty()); visibility=if(host || joinCode!=null) View.GONE else View.VISIBLE }
        val duration=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("30 seconds","45 seconds","60 seconds")); setSelection(2); visibility=if(host) View.VISIBLE else View.GONE }
        val halves=Spinner(this).apply { adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("2 halves","4 halves")); visibility=if(host) View.VISIBLE else View.GONE }
        val harmonyHaptics=CheckBox(this).apply {
            text="Allow shared haptics (joining player must agree)"
            setTextColor(0xFFCECDD6.toInt()); isChecked=false
            visibility=if(host&&gameMode=="HARMONY") View.VISIBLE else View.GONE
        }
        val harmonyTrails=CheckBox(this).apply {
            text="Allow shared touch trails (joining player must agree)"
            setTextColor(0xFFCECDD6.toInt()); isChecked=false
            visibility=if(host&&gameMode=="HARMONY") View.VISIBLE else View.GONE
        }
        box.addView(url); box.addView(code)
        if(host) {
            box.addView(TextView(this).apply { text="Mode: ${modeName(gameMode)}"; setTextColor(0xFFCECDD6.toInt()); setPadding(4,12,4,0) })
            val durationLabel=TextView(this).apply { text="Round length"; setTextColor(0xFFCECDD6.toInt()); setPadding(4,12,4,0) }
            val halvesLabel=TextView(this).apply { text="Deathmatch length"; setTextColor(0xFFCECDD6.toInt()); setPadding(4,12,4,0) }
            val timed=gameMode in setOf("DEATHMATCH","CONNECTION")
            durationLabel.visibility=if(timed) View.VISIBLE else View.GONE
            duration.visibility=if(timed) View.VISIBLE else View.GONE
            halvesLabel.visibility=if(gameMode=="DEATHMATCH") View.VISIBLE else View.GONE
            halves.visibility=if(gameMode=="DEATHMATCH") View.VISIBLE else View.GONE
            if(timed) { box.addView(durationLabel); box.addView(duration) }
            if(gameMode=="DEATHMATCH") { box.addView(halvesLabel); box.addView(halves) }
            if(gameMode=="HARMONY") {
                box.addView(harmonyHaptics)
                box.addView(harmonyTrails)
                box.addView(TextView(this).apply {
                    text="The other player can accept or decline each option. Touch points remain visible in Harmony; shared trails add a fading motion trace."
                    setTextColor(0xFF9A97A5.toInt()); textSize=12f; setPadding(4,0,4,8)
                })
            }
        }
        AlertDialog.Builder(this).setTitle(if(host) "Host a session" else "Join a session").setView(box)
            .setPositiveButton(if(host) "Create" else "Connect") { _,_ ->
                val serverUrl=url.text.toString().trim()
                if(!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) { url.error="Enter a ws:// or wss:// server address"; return@setPositiveButton }
                if(!host && code.text.isNullOrBlank()) { code.error="Enter the session code"; return@setPositiveButton }
                this@MainActivity.serverUrl=serverUrl
                onlineApi=OnlineApi(serverUrl)
                profileCredentials?.let { presenceClient.connect(serverUrl,it) }
                roomCode=code.text.toString().trim().uppercase()
                isHost=host
                if(host) {
                    durationSec=listOf(30,45,60)[duration.selectedItemPosition]
                    if(gameMode=="DEATHMATCH") matchHalves=if(halves.selectedItemPosition==1) 4 else 2
                }
                hostAllowsHarmonyHaptics=host&&gameMode=="HARMONY"&&harmonyHaptics.isChecked
                hostAllowsHarmonyTrails=host&&gameMode=="HARMONY"&&harmonyTrails.isChecked
                harmonySharedHaptics=false; harmonySharedTrails=false
                matchFinished=false; duelScores.clear(); getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putString("serverUrl",serverUrl).apply(); screen="waiting"; client.connect(serverUrl,if(host) "host" else "join",roomCode,durationSec,gameMode,matchHalves,profileCredentials?.profileId,profileCredentials?.token,hostAllowsHarmonyHaptics,hostAllowsHarmonyTrails); showWaiting()
            }
            .setNegativeButton("Cancel",null).show()
    }
    private fun showWaiting() {
        base("waiting", "Waiting for a player")
        status=text("Connecting…",16,CYAN)
        shareRoomButton=Button(this).apply {
            text="Share room code"; visibility=View.GONE
            setOnClickListener {
                playButtonSound()
                val share=Intent(Intent.ACTION_SEND).apply {
                    type="text/plain"
                    putExtra(Intent.EXTRA_SUBJECT,"PsychConnect room $roomCode")
                    putExtra(Intent.EXTRA_TEXT,"Join my PsychConnect ${gameMode.lowercase()} game.\nRoom code: $roomCode\nServer: $serverUrl")
                }
                startActivity(Intent.createChooser(share,"Share PsychConnect room"))
            }
        }
        root.addView(shareRoomButton,LinearLayout.LayoutParams(-1,-2).apply { topMargin=7; bottomMargin=7 })
        button("Back") { client.leaveSession(); showHome() }
    }
    private fun onServerMessage(raw: String) {
        val m=try { JSONObject(raw) } catch (_:Exception) { return }
        when(m.optString("type")) {
            "hosted","joined" -> { val code=m.optString("code"); roomCode=code; gameMode=m.optString("mode",gameMode); durationSec=m.optInt("durationSec",durationSec); matchHalves=m.optInt("matchHalves",matchHalves); if(screen=="waiting") { status.text="Code: $code\n${if(isHost)"Share this with the other player." else "Joined ${modeName(gameMode)}."}"; shareRoomButton?.visibility=if(isHost) View.VISIBLE else View.GONE } }
            "harmony_haptics_consent_required" -> { lastHarmonyConsentOffer=m; pendingHarmonyHapticsConsent=true; showHarmonyHapticsConsent() }
            "harmony_haptics_consent_waiting" -> if(screen=="waiting") status.text="The other player is choosing which Harmony effects to share."
            "harmony_haptics_state" -> {
                harmonySharedHaptics=m.optBoolean("enabled",false)
                if(!harmonySharedHaptics) { remotePalmContact=false; harmonyPalmSynchronizer.reset(); haptics.resetHarmonyShape() }
                if(screen=="waiting") status.text="Shared haptics ${if(harmonySharedHaptics)"on" else "off"} · trails ${if(harmonySharedTrails)"on" else "off"}.\nRoom code: $roomCode"
            }
            "harmony_trails_state" -> {
                harmonySharedTrails=m.optBoolean("enabled",false)
                if(!harmonySharedTrails&&::pad.isInitialized) pad.clearRemoteTrails()
                if(screen=="waiting") status.text="Shared haptics ${if(harmonySharedHaptics)"on" else "off"} · trails ${if(harmonySharedTrails)"on" else "off"}.\nRoom code: $roomCode"
            }
            "waiting" -> if(screen=="waiting") status.text="Connected. Waiting for the other player…"
            "ready" -> { connected=true; roundState.playersReady(); if(screen=="waiting") showReady() }
            "touch" -> {
                val sender=m.optString("playerId")
                if(sender==client.playerId) { if(m.optBoolean("speedLimited")) speedWarningUntil=SystemClock.elapsedRealtime()+900 }
                else {
                    val seq=if(m.has("seq")) m.optLong("seq") else null
                    if(remoteTouch.update(client.playerId,sender,m.optBoolean("active"),if(m.has("x") && m.has("y")) Point(m.optDouble("x"),m.optDouble("y")) else null,seq)) {
                        val wasRemoteActive=remoteActive
                        remoteActive=remoteTouch.active; remotePoint=remoteTouch.point
                        val mayShowRemoteTrail=gameMode=="CONNECTION" || gameMode=="DEATHMATCH"&&currentRole=="LEADER"
                        if(remoteActive && !wasRemoteActive && mayShowRemoteTrail) pad.beginTrail("remote")
                        if(remoteActive && remotePoint!=null && mayShowRemoteTrail) pad.addTrail("remote",remotePoint!!,if(gameMode=="DEATHMATCH") CYAN else 0xFFFF9BD6.toInt())
                        lastRemoteServerTs=if(m.has("serverTs")) m.optDouble("serverTs") else null
                        if(remoteActive && remotePoint!=null && lastRemoteServerTs!=null) remotePositions.add(lastRemoteServerTs!!,remotePoint!!)
                        if(!remoteActive) { pad.endTrail("remote"); remotePositions.clear(); interpolatedRemotePoint=null }
                    }
                }
            }
            "harmony_touches" -> if(gameMode=="HARMONY" && !simMode && m.optString("playerId")!=client.playerId) {
                val previousRemoteIds=remoteHarmonyPoints.keys.toSet()
                val received=linkedMapOf<Int,Point>(); val receivedSizes=linkedMapOf<Int,Int>()
                val points=m.optJSONArray("points")
                if(points!=null) for(i in 0 until points.length()) {
                    val point=points.optJSONObject(i) ?: continue
                    val id=point.optInt("id",-1)
                    if(id in 0..31) { received[id]=Point(point.optDouble("x",-1.0),point.optDouble("y",-1.0)); receivedSizes[id]=point.optInt("sizeClass",1).coerceIn(0,2) }
                }
                remoteHarmonyMotion.record(received,SystemClock.elapsedRealtime())
                remoteHarmonyRotation.record(received,SystemClock.elapsedRealtime())
                (previousRemoteIds-received.keys).forEach { pad.endTrail("remote:$it") }
                (received.keys-previousRemoteIds).forEach { pad.beginTrail("remote:$it") }
                remoteHarmonyPoints.clear(); remoteHarmonyPoints.putAll(received)
                remoteHarmonyFingerSizes.clear(); remoteHarmonyFingerSizes.putAll(receivedSizes)
                if(harmonySharedTrails&&m.optBoolean("trails",false)&&!m.optBoolean("stale",false)) {
                    received.forEach { (id,point) -> pad.addTrail("remote:$id",point,intArrayOf(CYAN,0xFFFF9BD6.toInt(),0xFF9CAEFF.toInt(),0xFF9BE8D7.toInt())[id.mod(4)]) }
                }
                remotePalmContact=harmonySharedHaptics&&m.optBoolean("palm",false)&&!m.optBoolean("stale",false)
                if(m.optBoolean("stale",false)) { remotePalmContact=false; remoteHarmonyFingerSizes.clear(); remoteHarmonyMotion.reset(); remoteHarmonyRotation.reset(); pad.clearRemoteTrails() }
                remoteActive=received.isNotEmpty(); remotePoint=received.values.firstOrNull()
                lastRemoteServerTs=if(m.has("serverTs")) m.optDouble("serverTs") else null
                if(::pad.isInitialized) pad.invalidate()
            }
            "harmony_haptic_event" -> if(gameMode=="HARMONY" && harmonySharedHaptics && m.optString("playerId")!=client.playerId) {
                when(m.optString("gesture")) {
                    "tap" -> haptics.harmonyTap(m.optInt("fingers",1))
                    "anchor_start" -> harmonyAnchorRemote=true
                    "anchor_end" -> harmonyAnchorRemote=false
                }
            }
            "live_score" -> if(screen=="game" && (gameMode!="DEATHMATCH" || m.optString("playerId")==client.playerId)) { liveScore=m.optInt("score",liveScore); if(gameMode=="DEATHMATCH") updateDuelScores(m.optJSONObject("scores")); if(::pad.isInitialized) pad.invalidate() }
            "round_start" -> { gameMode=m.optString("mode",gameMode); updateDuelScores(m.optJSONObject("scores")); matchHalves=m.optInt("matchHalves",matchHalves); val roles=m.optJSONArray("roles"); if(roles!=null) for(i in 0 until roles.length()) { val p=roles.getJSONObject(i); if(p.optString("id")==client.playerId) { currentRole=p.optString("role"); isHost=currentRole=="LEADER" } }; durationSec=m.optInt("durationSec",durationSec); networkPaused=false; startRound(false) }
            "ability_result" -> {
                abilityUsesRemaining=m.optInt("remaining",abilityUsesRemaining).coerceIn(0,2)
                if(m.optBoolean("accepted")) {
                    haptics.abilityPulse()
                    if(m.optString("ability")=="sonar" && m.has("x") && m.has("y")) {
                        val now=SystemClock.elapsedRealtime(); val duration=m.optLong("durationMs",1400L)
                        abilityEffects.add(AbilityEffect("sonar",Point(m.optDouble("x"),m.optDouble("y")),now,duration))
                        if(m.has("decoyX") && m.has("decoyY")) abilityEffects.add(AbilityEffect("sonar",Point(m.optDouble("decoyX"),m.optDouble("decoyY")),now,duration))
                    }
                } else {
                    val message=when(m.optString("reason")) { "depleted"->"No uses left this round"; "no_contact"->"Sonar needs the Leader to be touching the screen"; else->"That ability is not available for your role" }
                    Toast.makeText(this,message,Toast.LENGTH_SHORT).show()
                }
                if(::pad.isInitialized) pad.invalidate()
            }
            "together_waves" -> if(screen=="game" && gameMode=="CONNECTION") {
                val now=SystemClock.elapsedRealtime(); val waves=m.optJSONArray("waves")
                togetherWaveCue="Find an opening and move through it together"
                togetherWaveCueUntil=now+3_400L
                if(waves!=null) for(i in 0 until waves.length()) {
                    val wave=waves.optJSONObject(i)?:continue; val gaps=mutableListOf<Double>(); val encodedGaps=wave.optJSONArray("gaps")
                    if(encodedGaps!=null) for(j in 0 until encodedGaps.length()) { val angle=encodedGaps.optDouble(j,Double.NaN); if(angle.isFinite()) gaps.add(angle) }
                    if(gaps.isEmpty()) continue
                    val x=wave.optDouble("x",Double.NaN); val y=wave.optDouble("y",Double.NaN)
                    if(!x.isFinite()||!y.isFinite()||x !in 0.0..1.0||y !in 0.0..1.0) continue
                    val delay=wave.optLong("startDelayMs",0L).coerceIn(0L,5_000L)
                    togetherWaveEffects.add(TogetherWaveEffect(wave.optString("id"),Point(x,y),gaps,now+delay,
                        wave.optLong("durationMs",4_800L).coerceIn(1_000L,8_000L),wave.optDouble("maxRadius",0.76).coerceIn(0.2,1.0)))
                }
                if(::pad.isInitialized) pad.invalidate()
            }
            "together_wave_result" -> if(screen=="game" && gameMode=="CONNECTION") {
                liveScore=m.optInt("score",liveScore).coerceIn(0,100)
                togetherWaveCue=when(m.optString("cue")) {
                    "shared_passage"->"Beautiful — you found the same opening together"
                    "safe_passage"->"A lovely shared passage"
                    "one_safe"->"One of you found an opening — stay together"
                    "soft_bump"->"A soft wave brushed past — try an opening next time"
                    else->"A gentle wave drifted by"
                }
                togetherWaveCueUntil=SystemClock.elapsedRealtime()+2_700L
                if(m.optString("outcome")=="bumped") haptics.harmonyTap(1)
                if(::pad.isInitialized) pad.invalidate()
            }
            "puzzle_level" -> if(screen=="game"&&gameMode=="PUZZLE") applyPuzzleLevel(m)
            "puzzle_started" -> if(screen=="game"&&gameMode=="PUZZLE") { puzzleStarted=true; puzzleTimerStarted=true; puzzleNeedsStart=false; puzzleRemainingMs=m.optLong("remainingMs",60_000L); puzzleTimeAtMs=SystemClock.elapsedRealtime(); haptics.connect(); hapticsRunning=true; if(::pad.isInitialized) pad.invalidate() }
            "puzzle_checkpoint" -> if(screen=="game"&&gameMode=="PUZZLE") {
                val reached=m.optBoolean("reached"); val accepted=m.optBoolean("accepted")
                if(accepted&&!puzzleCheckpointAccepted) haptics.abilityPulse() else if(reached&&!accepted) haptics.harmonyTap(1)
                puzzleCheckpointAccepted=accepted
                if(::pad.isInitialized) pad.invalidate()
            }
            "puzzle_hold" -> if(gameMode=="PUZZLE") { puzzleHoldMs=m.optLong("holdMs",0L); if(::pad.isInitialized) pad.invalidate() }
            "puzzle_restart" -> if(gameMode=="PUZZLE") {
                puzzleScore=m.optInt("score",puzzleScore); puzzleTotalScore=m.optInt("totalScore",puzzleTotalScore); puzzleRemainingMs=m.optLong("remainingMs",puzzleRemainingMs); puzzleTimeAtMs=SystemClock.elapsedRealtime(); puzzleNeedsStart=true; puzzleStarted=false; puzzleHoldMs=0L; puzzleCheckpointAccepted=false
                if(hapticsEnabled) haptics.abilityPulse()
                val reason=when(m.optString("reason")) { "wall"->"Wall hit"; "projectile"->"Projectile hit"; "explosion"->"Wave hit"; else->"Finger lifted" }
                Toast.makeText(this,"$reason · −${m.optInt("penalty")} points · return to the start plate",Toast.LENGTH_SHORT).show()
                if(::pad.isInitialized) pad.invalidate()
            }
            "puzzle_level_complete" -> if(gameMode=="PUZZLE") {
                puzzleScore=m.optInt("score",puzzleScore); puzzleTotalScore=m.optInt("totalScore",puzzleTotalScore); puzzleRemainingMs=0L; puzzleTimeAtMs=SystemClock.elapsedRealtime(); puzzleHoldMs=2500L; puzzleCheckpointAccepted=true; haptics.abilityPulse()
                val points=m.optInt("levelPoints",0); val timePoints=m.optInt("timePoints",0); val flawless=m.optInt("flawlessBonus",0)
                val bonusLabel=if(flawless>0) " · Flawless +$flawless" else ""
                Toast.makeText(this,"Level clear · +$points ($timePoints time points$bonusLabel)",Toast.LENGTH_LONG).show()
                if(::pad.isInitialized) pad.invalidate()
            }
            "puzzle_hazards" -> if(screen=="game"&&gameMode=="PUZZLE") { puzzleScore=m.optInt("score",puzzleScore); puzzleTotalScore=m.optInt("totalScore",puzzleTotalScore); applyPuzzleHazards(m) }
            "puzzle_game_over" -> if(gameMode=="PUZZLE") finishPuzzleRun(m)
            "survival_duo_state" -> if(gameMode=="COOP_SURVIVAL") applyDuoSurvivalState(m)
            "survival_duo_start" -> if(gameMode=="COOP_SURVIVAL") { duoSurvivalStarted=true; duoSurvivalMotionFactor=0.0; duoStateAtMs=SystemClock.elapsedRealtime(); if(::pad.isInitialized) pad.invalidate() }
            "survival_duo_over" -> if(gameMode=="COOP_SURVIVAL") finishDuoSurvival(m.optLong("elapsedMs",duoSurvivalElapsedMs))
            "round_end" -> { val result=m.optJSONObject("result"); updateDuelScores(m.optJSONObject("scores")); finishRound(result,m.optJSONObject("scores")) }
            "match_end" -> { matchFinished=true; showMatchResult(m.optJSONObject("scores"),m.optString("winnerId")) }
            "rematch_waiting" -> showRematchWaiting()
            "rematch_ready" -> { matchFinished=false; duelScores.clear(); roundState.reset(); roundState.playersReady(); showReady() }
            "challenge_expired" -> { stopActive(); client.close(); showHome(); Toast.makeText(this,"Your rival did not join before the challenge room expired.",Toast.LENGTH_LONG).show() }
            "sandbox_end" -> finishSandbox()
            "reconnecting" -> pauseForNetwork("Connection interrupted · reconnecting…",m.optLong("remainingMs",-1L))
            "peer_reconnecting" -> { if(screen=="game") pauseForNetwork("Waiting for the other player to reconnect…",m.optLong("remainingMs",-1L)) else if(screen=="waiting") status.text="The other player lost connection. Keeping the room open briefly…" }
            "session_resumed" -> {
                gameMode=m.optString("mode",gameMode); durationSec=m.optInt("durationSec",durationSec); matchHalves=m.optInt("matchHalves",matchHalves)
                currentRole=m.optString("role",currentRole); isHost=currentRole=="LEADER"; updateDuelScores(m.optJSONObject("scores"))
                networkRemainingMs=m.optLong("remainingMs",networkRemainingMs).coerceAtLeast(0L)
                if(screen=="waiting") status.text="Room restored: $roomCode\nWaiting for the other player…"
                else if(m.optBoolean("waitingForPeer")) networkPauseMessage="Waiting for the other player to reconnect…" else resumeAfterNetworkDrop(networkRemainingMs)
            }
            "peer_reconnected" -> if(screen=="game") {
                if(m.optBoolean("waitingForPeer")) networkPauseMessage="Waiting for the other player to reconnect…"
                else resumeAfterNetworkDrop(m.optLong("remainingMs",networkRemainingMs).coerceAtLeast(0L))
                if(::pad.isInitialized) pad.invalidate()
            } else if(screen=="waiting"&&!m.optBoolean("waitingForPeer")) status.text="Connected. Waiting for the other player…"
            "resume_rejected" -> { stopActive(); networkPaused=false; connected=false; if(screen!="home") { showWaiting(); status.text=m.optString("message","This session can no longer be resumed. Go back and reconnect.") } }
            "disconnect" -> { stopActive(); networkPaused=false; if(screen!="home") { connected=false; showWaiting(); status.text="Connection lost. Go back and reconnect." } }
            "error" -> Toast.makeText(this,m.optString("message"),Toast.LENGTH_LONG).show()
        }
    }
    private fun updateDuelScores(scores:JSONObject?) {
        if(scores==null) return
        duelScores.clear()
        val keys=scores.keys()
        while(keys.hasNext()) { val id=keys.next(); duelScores[id]=scores.optInt(id) }
    }
    private fun showHarmonyHapticsConsent() {
        if(!activityResumed||screen!="waiting") return
        pendingHarmonyHapticsConsent=false
        val offeredHaptics=if(lastHarmonyConsentOffer?.has("haptics")==true) lastHarmonyConsentOffer?.optBoolean("haptics")==true else true
        val offeredTrails=lastHarmonyConsentOffer?.optBoolean("trails",false)==true
        val haptics=CheckBox(this).apply { text="Receive shared haptic feedback"; setTextColor(0xFFCECDD6.toInt()); visibility=if(offeredHaptics) View.VISIBLE else View.GONE }
        val trails=CheckBox(this).apply { text="Show my touch trail to the other player"; setTextColor(0xFFCECDD6.toInt()); visibility=if(offeredTrails) View.VISIBLE else View.GONE }
        val options=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(4),dp(20),0); addView(haptics); addView(trails) }
        AlertDialog.Builder(this).setTitle("Harmony sharing consent")
            .setMessage("Choose separately. A shared trail shows the fading path of your movement to the other player. You can still play if you leave both options off.")
            .setView(options)
            .setPositiveButton("Continue") { _,_ -> client.respondToHarmonySharingConsent(haptics.isChecked,trails.isChecked) }
            .setNegativeButton("Continue without sharing") { _,_ -> client.respondToHarmonySharingConsent(false,false) }
            .setCancelable(false).show()
    }
    private fun showReady() {
        base("ready", "Connection established")
        val instructions=when(gameMode) {
            "CONNECTION" -> "Work together. Stay close and keep both fingers moving to build your shared score; it fades when either of you stops. Soft waves drift across the screen. Look for their openings and try to pass through the same one together."
            "PUZZLE" -> "Both start on the lower plate. Reach different checkpoints and hold them for 2.5 seconds within 60 seconds. Each level has a shared 1,000-point hit buffer; a hit removes 200 and sends both back to start. The buffer does not drain with time. Zero time or zero buffer ends the run. Clearing banks the remaining buffer and 100 points per full second left; a hit-free clear adds a 1,000-point Flawless bonus."
            "COOP_SURVIVAL" -> "Both players dodge the same projectiles and expanding blasts. Hazards move while either player moves and freeze while both are still. If either is hit, the shared run ends."
            "HARMONY" -> "An open sensory experiment. Move, pause, and explore how the shared heartbeat responds. ${if(harmonySharedHaptics)"Shared haptics are enabled." else "Shared haptics are off."} ${if(harmonySharedTrails)"Touch trails are shared with both players' consent." else "Touch trails stay local."} There is no score or timer."
            else -> if(isHost) "You are the Leader (Runner). Stay away from the Seeker for points. Tap with a second finger to place a decoy for the Seeker. Two decoys per round."
                else "You are the Seeker (Chaser). Follow the heartbeat and get close to the Leader to score. Tap with a second finger to briefly reveal the Leader with sonar. Two sonar uses per round."
        }
        text("Take a comfortable position. Keep one finger on the screen.\n\n$instructions",17,0xFFD1CFDC.toInt())
        val readyStatus=text("The round begins when both players are ready.",13,0xFF81808D.toInt())
        button("Ready") { client.ready(); readyStatus.text="Ready — waiting for the other player" }
    }
    private fun beginGame(simulation: Boolean) { simMode=simulation; gameMode="CONNECTION"; connected=simulation; isHost=true; currentRole="LEADER"; matchHalves=1; matchFinished=false; roundState.reset(); roundState.playersReady(); startRound(true) }
    private fun formatSurvivalTime(milliseconds:Long):String {
        val tenths=milliseconds.coerceAtLeast(0L)/100L
        return "${(tenths/600L).toString().padStart(2,'0')}:${((tenths/10L)%60L).toString().padStart(2,'0')}.${tenths%10L}"
    }
    private fun startSurvivalRun() {
        gameMode="SURVIVAL"; simMode=false; isHost=true; currentRole="LEADER"; connected=false
        survivalRunActive=true; survivalPausedAtMs=0L; localLeader=Point(.5,.76)
        roundState.reset(); roundState.playersReady(); startRound(false)
    }
    private fun finishSurvival() {
        if(!survivalRunActive || screen!="game") return
        survivalRunActive=false; roundInputGate.close(); haptics.stop(); hapticsRunning=false; if(hapticsEnabled) haptics.abilityPulse()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.removeCallbacks(tick)
        val score=survivalEngine.elapsedMs
        val newBest=score>survivalBestMs
        survivalBestMs=maxOf(survivalBestMs,score)
        getSharedPreferences("psychconnect",MODE_PRIVATE).edit().putLong("survivalHighScoreMs",survivalBestMs).apply()
        saveLocalLeaderboard("SOLO_SURVIVAL",score)
        if(profileCredentials!=null) apiRequest("/api/leaderboards/solo-survival","POST",JSONObject().put("runId",UUID.randomUUID().toString()).put("scoreMs",score)) { _,error ->
            if(error==null) toastOnline("Solo Survival score synced to the online leaderboard.")
            else toastOnline("Saved locally. Online sync failed: $error")
        }
        roundState.finish(true); screen="result"; base("result","Survival run over")
        text("SURVIVED",18,0xFFFF8967.toInt(),true)
        text(formatSurvivalTime(score),56,0xFFFF604F.toInt(),true)
        text(if(newBest) "NEW PERSONAL BEST" else "PERSONAL BEST  ·  ${formatSurvivalTime(survivalBestMs)}",15,0xFFFFBA70.toInt(),true)
        button("Leaderboard") { showLeaderboards("SOLO_SURVIVAL") }
        button("Run again") { startSurvivalRun() }
        button("Main menu") { gameMode="DEATHMATCH"; showHome() }
    }
    private fun startRound(local: Boolean) {
        networkPaused=false; networkPauseMessage=""; roundState.start(); samples.clear(); roundStart=SystemClock.elapsedRealtime(); if(simMode) roundInputGate.close() else roundInputGate.open(); lastSharedScoreAt=roundStart; sharedScore=0.0; previousSharedLeader=null; previousSharedFollower=null; lastPingAt=0L; lastRemoteServerTs=null; remotePositions.clear(); interpolatedRemotePoint=null; remoteHarmonyPoints.clear(); remoteHarmonyFingerSizes.clear(); remotePalmContact=false; lastAdvertisedPalm=false; palmFeedbackMode=PalmFeedbackMode.FINGER; harmonyPalmSynchronizer.reset(); localHarmonyMotion.reset(); remoteHarmonyMotion.reset(); localHarmonyRotation.reset(); remoteHarmonyRotation.reset(); harmonyGestureSequence=0L; harmonyAnchorLocal=false; harmonyAnchorRemote=false; harmonyAnchorPointer=null; haptics.resetHarmonyShape(); beatHistory.clear(); abilityEffects.clear(); togetherWaveEffects.clear(); togetherWaveCue=""; togetherWaveCueUntil=0L; abilityUsesRemaining=2; currentBpm=65.0; liveScore=0; mapper=HeartbeatMapper(HeartbeatConfig(fasterWhenClose=fasterWhenClose)); mapper.reset(); speedLimiter.reset(); remoteTouch.reset(); localActive=false; remoteActive=false; remotePoint=null; synchronizedNow=false; connectionStartedAt=0L
        puzzleLevel=0; puzzleScore=1_000; puzzleTotalScore=0; puzzleWalls=emptyList(); puzzleCheckpoints=emptyList(); puzzleStarted=false; puzzleTimerStarted=false; puzzleNeedsStart=true; puzzleHoldMs=0L; puzzleCheckpointAccepted=false
        duoSurvivalStarted=false; duoSurvivalElapsedMs=0L; duoSurvivalMotionFactor=0.0; duoSurvivalLevel=1; duoReadyCount=0; duoProjectiles=emptyList(); duoBlasts=emptyList()
        if(gameMode=="SURVIVAL") { survivalEngine.reset(roundStart); survivalRunActive=true; localLeader=Point(.5,.76); survivalMotionPoint=localLeader; survivalMotionAtMs=roundStart }
        haptics.enabled=hapticsEnabled; hapticsRunning=false; screen="game"; syncMenuMusic(); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility=(View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        root=LinearLayout(this).apply { setBackgroundColor(BG) }; setContentView(root)
        pad=TouchPad(); root.addView(pad,LinearLayout.LayoutParams(-1,-1)); ui.removeCallbacks(tick); ui.post(tick)
    }
    private fun updateGame() {
        if(screen!="game") return
        if(networkPaused) { if(::pad.isInitialized) pad.invalidate(); return }
        val now=SystemClock.elapsedRealtime(); val elapsed=now-roundStart
        if(gameMode=="SURVIVAL") {
            val motionDelta=(now-survivalMotionAtMs).coerceAtLeast(1L)
            val movementSpeed=survivalMotionPoint.distanceTo(localLeader)*1000.0/motionDelta
            val movementFactor=((movementSpeed-.015)/.12).coerceIn(0.0,1.0)
            survivalMotionPoint=localLeader; survivalMotionAtMs=now
            val hit=survivalEngine.advance(now,localLeader,if(::pad.isInitialized) pad.width else 0,if(::pad.isInitialized) pad.height else 0,movementFactor)
            if(::pad.isInitialized) pad.invalidate()
            if(hit) finishSurvival()
            return
        }
        if(gameMode=="PUZZLE"||gameMode=="COOP_SURVIVAL") {
            if(now-lastPingAt>=2000L) { client.ping(now); lastPingAt=now }
            if(localActive && now-lastNetworkSendMs>=180L) {
                touchSequence++; client.sendTouch(TouchState(true,localLeader,now,touchSequence)); lastNetworkSendMs=now
            }
            updateSpecialModeHaptics(now)
            if(::pad.isInitialized) pad.invalidate()
            return
        }
        val inputOpen=roundInputGate.permits(gameMode,simMode,elapsed,durationSec*1000L)
        val harmonyMode=gameMode=="HARMONY"&&!simMode
        val palmSnapshot=if(harmonyMode&&::pad.isInitialized) pad.updatePalm(now) else null
        val palmFeedback=if(harmonyMode) harmonyPalmSynchronizer.update(palmSnapshot?.palmActive==true,remotePalmContact,harmonySharedHaptics,now) else PalmFeedback(PalmFeedbackMode.FINGER,0.0)
        palmFeedbackMode=palmFeedback.mode
        if(harmonyMode&&harmonySharedHaptics&&palmSnapshot?.palmActive!=lastAdvertisedPalm) { lastAdvertisedPalm=palmSnapshot?.palmActive==true; pad.sendHarmonySnapshot(now) }
        if(inputOpen && !simMode && gameMode=="HARMONY" && localActive && now-lastNetworkSendMs>=250L && ::pad.isInitialized) pad.sendHarmonySnapshot(now)
        if(!inputOpen && localActive) {
            touchSequence++; client.sendTouch(TouchState(false,null,now,touchSequence)); localActive=false
            if(::pad.isInitialized) pad.clearContacts()
        }
        if(!inputOpen && hapticsRunning) { haptics.stop(); hapticsRunning=false; mapper.reset(); synchronizedNow=false }
        if(now-lastPingAt>=2000L) { client.ping(now); lastPingAt=now }
        if(!simMode && remoteActive) {
            val serverNow=client.networkDiagnostics.estimatedServerTime(now)
            val delay=client.networkDiagnostics.interpolationDelayMs()
            interpolatedRemotePoint=if(serverNow!=null) remotePositions.sampleAt(serverNow-delay) ?: remotePoint else remotePoint
        }
        val liveRemote=interpolatedRemotePoint ?: remotePoint
        val leader=if(simMode || isHost) localLeader else liveRemote
        val follower=if(simMode) localFollower else if(isHost) liveRemote ?: localFollower else localLeader
        val both=localActive && if(simMode) pad.secondActive else remoteActive
        val canFeelHaptics=if(harmonyMode) inputOpen&&(localActive||remotePalmContact||palmFeedback.sharedBlend>0.0) else inputOpen&&leader!=null&&both
        if(canFeelHaptics) {
            val harmonyShape=if(harmonyMode) harmonyHapticMapper.update(pad.touchCount,remoteHarmonyPoints.size,localHarmonyMotion.speed(now),remoteHarmonyMotion.speed(now),includeRemote=harmonySharedHaptics,localRotationSpeed=localHarmonyRotation.radiansPerSecond(now),remoteRotationSpeed=remoteHarmonyRotation.radiansPerSecond(now),palmFeedback=palmFeedback) else null
            if(harmonyShape!=null) haptics.setHarmonyShape(harmonyHapticMapper.applyAnchor(harmonyShape,harmonyAnchorLocal||harmonyAnchorRemote))
            if(!hapticsRunning) { haptics.connect(delayInitialPulse=gameMode=="HARMONY"); hapticsRunning=true; if(connectionStartedAt==0L) connectionStartedAt=SystemClock.elapsedRealtime() }
            val distance=if(harmonyMode) 0.0 else leader!!.distanceTo(follower)
            val nowSynchronized=if(harmonyMode) false else distance<=0.035
            synchronizedNow=nowSynchronized
            val mapped=if(harmonyMode) Heartbeat(65.0+if(palmFeedback.mode==PalmFeedbackMode.SHARED_PALM)palmFeedback.sharedBlend*6.0 else 0.0,false) else mapper.update(distance)
            val heart=if(harmonyShape!=null) Heartbeat((mapped.bpm+harmonyShape.tempoOffsetBpm).coerceIn(50.0,160.0),mapped.synchronized) else mapped
            currentBpm=heart.bpm; haptics.update(heart)
            samples.add(Sample(SystemClock.elapsedRealtime(),distance))
        } else if(hapticsRunning) {
            haptics.stop(); hapticsRunning=false; mapper.reset(); synchronizedNow=false
        }
        if(simMode && gameMode=="CONNECTION") {
            val dt=(now-lastSharedScoreAt).coerceIn(0L,250L)
            val lMoving=previousSharedLeader?.distanceTo(leader ?: localLeader)?.let { it>=.001 } ?: false
            val fMoving=previousSharedFollower?.distanceTo(follower)?.let { it>=.001 } ?: false
            sharedScore=SharedConnectionScoring.step(sharedScore,if(leader!=null && both) leader.distanceTo(follower) else 1.0,lMoving && both,fMoving && both,dt)
            lastSharedScoreAt=now
            previousSharedLeader=if(both) leader else null
            previousSharedFollower=if(both) follower else null
            liveScore=sharedScore.toInt()
        }
        if(::pad.isInitialized) { pad.invalidate(); if(speedWarningUntil<=SystemClock.elapsedRealtime()) speedWarningUntil=0L }
        if(elapsed>=durationSec*1000L && simMode) finishRound(null)
    }
    private fun updateSpecialModeHaptics(now:Long) {
        val signal=when(gameMode) {
            "PUZZLE" -> {
                if(!puzzleStarted||!localActive||puzzleCheckpoints.isEmpty()) null else {
                    val nearest=puzzleCheckpoints.minOf { hypot(it.x-localLeader.x,it.y-localLeader.y) }
                    var urgency=(1.0-nearest/.42).coerceIn(0.0,1.0)*.62
                    val age=((now-puzzleTimeAtMs).coerceAtLeast(0L).coerceAtMost(130L))/1000.0
                    puzzleProjectiles.forEach { projectile ->
                        val point=Point(projectile.point.x+projectile.velocityX*age,projectile.point.y+projectile.velocityY*age)
                        urgency=maxOf(urgency,(1.0-hypot(point.x-localLeader.x,point.y-localLeader.y)/.30).coerceIn(0.0,1.0))
                    }
                    puzzleBlasts.forEach { blast ->
                        val radial=hypot(blast.center.x-localLeader.x,blast.center.y-localLeader.y)
                        val danger=if(blast.armed) radial-.12 else kotlin.math.abs(radial-blast.radius)-.045
                        urgency=maxOf(urgency,(1.0-danger/.25).coerceIn(0.0,1.0))
                    }
                    Heartbeat(58.0+urgency*88.0,puzzleCheckpointAccepted||urgency>.96)
                }
            }
            "COOP_SURVIVAL" -> {
                if(!duoSurvivalStarted) null else {
                    var danger=1.0
                    duoProjectiles.forEach { projectile ->
                        val age=((now-duoStateAtMs).coerceAtMost(180L))/1000.0
                        val point=Point(projectile.point.x+projectile.velocityX*age,projectile.point.y+projectile.velocityY*age)
                        danger=min(danger,hypot(point.x-localLeader.x,point.y-localLeader.y)-.045)
                    }
                    duoBlasts.forEach { blast ->
                        val distance=hypot(blast.center.x-localLeader.x,blast.center.y-localLeader.y)
                        val radius=if(blast.armed&&blast.radiusAt(now)<=0.0) .12 else blast.radiusAt(now)
                        danger=min(danger,if(blast.armed&&blast.radiusAt(now)<=0.0) distance-radius else kotlin.math.abs(distance-radius)-.045)
                    }
                    val urgency=(1.0-danger/.36).coerceIn(0.0,1.0)
                    Heartbeat(55.0+urgency*95.0,urgency>.94)
                }
            }
            else -> null
        }
        if(signal==null) {
            if(hapticsRunning) { haptics.stop(); hapticsRunning=false }
            return
        }
        if(!hapticsRunning) { haptics.connect(); hapticsRunning=true }
        currentBpm=signal.bpm; haptics.update(signal)
    }
    private fun pauseForNetwork(message:String,remainingMs:Long) {
        if(screen!="game") return
        networkPaused=true; networkPauseMessage=message
        if(remainingMs>=0L) networkRemainingMs=remainingMs
        roundInputGate.close(); haptics.stop(); hapticsRunning=false; mapper.reset(); synchronizedNow=false
        localActive=false; remoteActive=false; remotePoint=null; interpolatedRemotePoint=null; remoteHarmonyPoints.clear(); remoteHarmonyFingerSizes.clear(); remotePalmContact=false
        remotePositions.clear(); remoteTouch.reset(); if(::pad.isInitialized) { pad.clearContacts(); pad.invalidate() }
    }
    private fun resumeAfterNetworkDrop(remainingMs:Long) {
        if(screen!="game") return
        val now=SystemClock.elapsedRealtime(); networkRemainingMs=remainingMs
        if(gameMode!="HARMONY") roundStart=now-(durationSec*1000L-remainingMs.coerceAtMost(durationSec*1000L))
        networkPaused=false; networkPauseMessage=""; roundInputGate.open()
        localActive=false; remoteActive=false; remotePoint=null; interpolatedRemotePoint=null; remoteHarmonyPoints.clear(); remoteHarmonyFingerSizes.clear(); remotePalmContact=false
        remotePositions.clear(); remoteTouch.reset(); samples.clear(); lastSharedScoreAt=now; previousSharedLeader=null; previousSharedFollower=null
        haptics.stop(); hapticsRunning=false; mapper.reset(); synchronizedNow=false
        if(::pad.isInitialized) { pad.clearContacts(); pad.invalidate() }
    }
    private fun finishRound(server: JSONObject?,scores:JSONObject?=null) {
        if(screen!="game") return
        roundInputGate.close()
        haptics.stop(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); ui.removeCallbacks(tick)
        val calculated=ScoringEngine.score(samples,roundStart,SystemClock.elapsedRealtime())
        val result=server?.let { ScoreResult(it.optInt("score"),it.optInt("alignment"),it.optDouble("syncSeconds"),it.optDouble("longestSyncSeconds")) } ?: if(simMode && gameMode=="CONNECTION") calculated.copy(score=liveScore) else calculated
        if(gameMode=="CONNECTION") saveLocalLeaderboard("CONNECTION",result.score.toLong())
        roundState.finish(); screen="result"
        base("result",if(gameMode=="CONNECTION") "Shared connection round complete" else "Half ${roundState.round} complete")
        text("${result.score}",56,PURPLE,true)
        when(gameMode) {
            "CONNECTION" -> text("Shared score",17,0xFFCECDD6.toInt())
            else -> {
                updateDuelScores(scores)
                val own=duelScores[client.playerId] ?: result.score
                val opponent=duelScores.entries.firstOrNull { it.key!=client.playerId }?.value ?: 0
                text("You  $own   ·   Opponent  $opponent",21,PURPLE,true)
                text("Match points after this half",15,0xFFAAA9B5.toInt())
            }
        }
        if(gameMode=="CONNECTION" && simMode) button("Play again") { roundState.nextRound(); startRound(true) }
        else if(gameMode=="CONNECTION") button("Play together again") { client.rematch(); showRematchWaiting() }
        else if(simMode || roundState.round<matchHalves) button("Ready for half ${roundState.round+1} of $matchHalves") { if(simMode) { roundState.nextRound(); isHost=!isHost; currentRole=if(isHost) "LEADER" else "FOLLOWER"; startRound(true) } else { client.nextRound(); roundState.nextRound(); showReady() } }
        button("Main menu") { haptics.stop(); client.leaveSession(); showHome() }
    }
    private fun finishSandbox() {
        if(screen!="game") return
        stopActive(); roundState.finish(); screen="result"; base("harmony","Sandbox complete")
        text("No score. No winner.\nJust explore the pulse and movement.",18,0xFFCECDD6.toInt())
        button("Explore again") { client.rematch(); showRematchWaiting() }
        button("Main menu") { client.leaveSession(); showHome() }
    }
    private fun showMatchResult(scores: JSONObject?, winnerId: String) {
        if(screen!="result") return
        base("match result", "Deathmatch complete · $matchHalves halves")
        val own=client.playerId.orEmpty(); val myScore=scores?.optInt(own,duelScores[own]?:0)?:0
        val other=scores?.keys()?.asSequence()?.firstOrNull { it!=own }?.let { scores.optInt(it) }?:0
        text("You  $myScore   ·   Opponent  $other",24,PURPLE,true)
        val outcome=when { winnerId.isBlank() || winnerId=="null" -> "DRAW"; winnerId==own -> "YOU WIN"; else -> "PARTNER WINS" }
        saveLocalLeaderboard("DEATHMATCH",myScore.toLong())
        text(when(outcome) { "DRAW"->"An even match"; "YOU WIN"->"You win"; else->"Your partner wins" },18,0xFFD1CFDC.toInt())
        button("Share result screenshot") { shareMatchResult(myScore,other,outcome) }
        button("Rematch") { client.rematch(); showRematchWaiting() }
        button("Main menu") { client.leaveSession(); showHome() }
    }
    private fun shareMatchResult(myScore:Int,opponentScore:Int,outcome:String) {
        try {
            val bitmap=Bitmap.createBitmap(1080,1350,Bitmap.Config.ARGB_8888)
            val canvas=Canvas(bitmap); canvas.drawColor(BG)
            val accent=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x304F3B7C; style=Paint.Style.FILL }
            canvas.drawCircle(120f,180f,300f,accent); canvas.drawCircle(980f,1170f,340f,accent)
            val cardPaint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF12121C.toInt() }
            canvas.drawRoundRect(RectF(64f,80f,1016f,1270f),44f,44f,cardPaint)
            fun centered(value:String,y:Float,size:Float,color:Int,bold:Boolean=false) {
                val p=Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color=color; textSize=size; textAlign=Paint.Align.CENTER; typeface=PaintTypeface(bold) }
                canvas.drawText(value,540f,y,p)
            }
            centered("PsychConnect",260f,68f,PURPLE,true)
            centered("DEATHMATCH · $matchHalves HALVES",352f,28f,0xFFAAA9B5.toInt())
            val line=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xFF34313F.toInt(); strokeWidth=2f }
            canvas.drawLine(160f,430f,920f,430f,line)
            centered("FINAL SCORE",515f,25f,0xFF888694.toInt())
            centered("$myScore  —  $opponentScore",650f,74f,0xFFE6E2EF.toInt(),true)
            centered("YOU                         OPPONENT",710f,23f,0xFFAAA9B5.toInt())
            centered(outcome,890f,58f,if(outcome=="YOU WIN") CYAN else PURPLE,true)
            centered("Two minds. One connection.",1120f,30f,0xFFAAA9B5.toInt())
            val folder=File(cacheDir,"shared").apply { mkdirs() }
            val image=File(folder,"psychconnect-result.png")
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
            val uri=FileProvider.getUriForFile(this,"$packageName.fileprovider",image)
            val share=Intent(Intent.ACTION_SEND).apply {
                type="image/png"; putExtra(Intent.EXTRA_STREAM,uri); putExtra(Intent.EXTRA_SUBJECT,"PsychConnect Deathmatch result")
                clipData=ClipData.newUri(contentResolver,"PsychConnect result",uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share,"Share match result"))
        } catch (_:Exception) { Toast.makeText(this,"Could not create the result image",Toast.LENGTH_LONG).show() }
    }
    private fun PaintTypeface(bold:Boolean)=android.graphics.Typeface.create("sans-serif",if(bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
    private fun showRematchWaiting() {
        base("rematch", "Rematch requested")
        text("Waiting for your partner to join the rematch.",16,0xFFD1CFDC.toInt())
        button("Main menu") { client.leaveSession(); showHome() }
    }
    private fun showSettings() {
        base("settings", "Haptics should feel calm and clear.")
        val prefs=getSharedPreferences("psychconnect",MODE_PRIVATE)
        val musicSwitch=Switch(this).apply { text="Background music"; setTextColor(Color.WHITE); isChecked=menuMusicEnabled; setOnCheckedChangeListener { _,enabled -> menuMusicEnabled=enabled; prefs.edit().putBoolean("menuMusicEnabled",enabled).apply(); syncMenuMusic() } }; root.addView(musicSwitch)
        text("Music continues during rounds. Add menu music at app/src/main/assets/audio/menu_music.ogg and the faster Survival track at app/src/main/assets/audio/survival_music.ogg.",12,0xFFD1CFDC.toInt())
        val soundSwitch=Switch(this).apply { text="Button sounds"; setTextColor(Color.WHITE); isChecked=buttonSoundsEnabled; setOnCheckedChangeListener { _,enabled -> buttonSoundsEnabled=enabled; prefs.edit().putBoolean("buttonSoundsEnabled",enabled).apply() } }; root.addView(soundSwitch)
        val toggle=Switch(this).apply { text="Haptics"; setTextColor(Color.WHITE); isChecked=hapticsEnabled; setOnCheckedChangeListener { _,v -> hapticsEnabled=v; haptics.enabled=v } }; root.addView(toggle)
        text("Intensity",14,0xFFCECDD6.toInt()); val seek=SeekBar(this).apply { max=100; progress=(prefs.getInt("hapticIntensity",45)).coerceIn(10,100); haptics.intensity=progress/100f; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener { override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean){ haptics.intensity=(p.coerceAtLeast(10)/100f); if(u) prefs.edit().putInt("hapticIntensity",p.coerceAtLeast(10)).apply() }; override fun onStartTrackingTouch(s:SeekBar?){}; override fun onStopTrackingTouch(s:SeekBar?){ } }) }; root.addView(seek,LinearLayout.LayoutParams(-1,-2))
        text("Heartbeat tempo",14,0xFFCECDD6.toInt())
        val tempo=RadioGroup(this).apply { orientation=RadioGroup.VERTICAL; setPadding(8,0,8,8) }
        val closeChoice=RadioButton(this).apply { id=View.generateViewId(); text="Faster as you get closer"; setTextColor(0xFFE6E2EF.toInt()) }
        val farChoice=RadioButton(this).apply { id=View.generateViewId(); text="Faster when you are farther away"; setTextColor(0xFFE6E2EF.toInt()) }
        tempo.addView(closeChoice); tempo.addView(farChoice); tempo.check(if(fasterWhenClose) closeChoice.id else farChoice.id)
        tempo.setOnCheckedChangeListener { _,checked -> fasterWhenClose=checked==closeChoice.id; prefs.edit().putBoolean("fasterWhenClose",fasterWhenClose).apply() }
        root.addView(tempo,LinearLayout.LayoutParams(-1,-2))
        text("The pink ripples follow the selected heartbeat tempo.",12,0xFF898794.toInt())
        text("Choose round length and match halves when hosting a Deathmatch.",12,0xFF898794.toInt())
        fun visualSwitch(label:String, checked:Boolean, key:String, changed:(Boolean)->Unit) {
            val control=Switch(this).apply { text=label; setTextColor(Color.WHITE); isChecked=checked; setOnCheckedChangeListener { _,value -> prefs.edit().putBoolean(key,value).apply(); changed(value); animationUi.removeCallbacks(animationTick); if(activityResumed && screen!="game") animationUi.post(animationTick) } }
            root.addView(control)
        }
        visualSwitch("Decorative animations",animatedBackground,"animatedBackground") { animatedBackground=it; showSettings() }
        visualSwitch("Reduced visual effects",reducedVisualEffects,"reducedVisualEffects") { reducedVisualEffects=it }
        visualSwitch("Reduced motion",reducedMotion,"reducedMotion") { reducedMotion=it }
        visualSwitch("Touch trails",touchTrails,"touchTrails") { touchTrails=it; if(::pad.isInitialized) pad.invalidate() }
        text("Trail visibility is adjustable below and can be disabled to reduce visual effects.",12,0xFFD1CFDC.toInt())
        val trailFadeLabel=text("Trail fade time · ${trailLifetimeMs/1_000} seconds",14,0xFFCECDD6.toInt())
        val trailFade=SeekBar(this).apply {
            max=11; progress=(trailLifetimeMs/1_000-1).coerceIn(0,11)
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s:SeekBar?,p:Int,u:Boolean) {
                    trailLifetimeMs=(p+1)*1_000
                    trailFadeLabel.text="Trail fade time · ${p+1} seconds"
                    if(u) prefs.edit().putInt("trailLifetimeMs",trailLifetimeMs).apply()
                }
                override fun onStartTrackingTouch(s:SeekBar?){}
                override fun onStopTrackingTouch(s:SeekBar?) { prefs.edit().putInt("trailLifetimeMs",trailLifetimeMs).apply() }
            })
        }
        root.addView(trailFade,LinearLayout.LayoutParams(-1,-2))
        text("Longer trails can be used to draw simple shapes or letters. Shared Harmony trails use this duration on each device.",12,0xFFD1CFDC.toInt())
        val dev=Switch(this).apply { text="Developer diagnostics"; setTextColor(Color.WHITE); isChecked=developer; setOnCheckedChangeListener { _,v -> developer=v } }; root.addView(dev)
        button("Test heartbeat") { haptics.enabled=hapticsEnabled; haptics.test() }
        button("Restore profile with recovery code") { showRecoveryPrompt() }
        button("Offline haptic simulation") { beginGame(true) }
        text(if(!haptics.hasVibrator) "No vibrator detected" else if(haptics.hasAmplitudeControl) "Amplitude control available" else "Simple vibration motor mode",13,if(haptics.hasVibrator) CYAN else 0xFFFF8181.toInt())
        button("Back") { showHome() }
    }
    private fun stopActive() { if(harmonyAnchorLocal&&harmonySharedHaptics) { harmonyGestureSequence++; client.sendHarmonyGesture("anchor_end",1,harmonyGestureSequence) }; harmonyAnchorLocal=false; harmonyAnchorRemote=false; remotePalmContact=false; palmFeedbackMode=PalmFeedbackMode.FINGER; harmonyPalmSynchronizer.reset(); harmonyAnchorPointer=null; roundInputGate.close(); haptics.stop(); haptics.resetHarmonyShape(); localHarmonyMotion.reset(); remoteHarmonyMotion.reset(); localHarmonyRotation.reset(); remoteHarmonyRotation.reset(); hapticsRunning=false; ui.removeCallbacks(tick); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    @Deprecated("Use the in-game exit prompt for the Harmony sandbox")
    override fun onBackPressed() {
        if(screen=="game" && gameMode=="HARMONY" && !simMode) {
            AlertDialog.Builder(this).setTitle("Leave Harmony sandbox?")
                .setMessage("This ends the open sensory session for both players. You can also stop sharing haptic interactions without leaving.")
                .setPositiveButton("Leave") { _,_ -> client.endSandbox() }
                .setNeutralButton("Stop sharing effects") { _,_ -> client.revokeHarmonyHaptics(); client.revokeHarmonyTrails(); harmonySharedHaptics=false; harmonySharedTrails=false; remotePalmContact=false; pad.clearRemoteTrails(); harmonyPalmSynchronizer.reset(); haptics.resetHarmonyShape() }
                .setNegativeButton("Stay",null).show()
        } else if(screen=="game" && gameMode in setOf("PUZZLE","COOP_SURVIVAL")) {
            AlertDialog.Builder(this).setTitle(if(gameMode=="PUZZLE") "Leave Puzzle?" else "Leave Co-op Survival?")
                .setMessage("Leaving will end this shared session for both players.")
                .setPositiveButton("Leave") { _,_ -> stopActive(); client.leaveSession(); gameMode="DEATHMATCH"; showHome() }
                .setNegativeButton("Stay",null).show()
        } else super.onBackPressed()
    }
    override fun onPause() { activityResumed=false; if(screen=="game"&&gameMode=="SURVIVAL"&&survivalRunActive) survivalPausedAtMs=SystemClock.elapsedRealtime(); stopMenuMusic(); animationUi.removeCallbacks(animationTick); presenceClient.close(); if(screen=="game") { if(localActive && !simMode && gameMode!="SURVIVAL") { touchSequence++; if(gameMode=="HARMONY") client.sendHarmonyTouches(emptyList(),touchSequence) else client.sendTouch(TouchState(false,null,SystemClock.elapsedRealtime(),touchSequence)) }; localActive=false; remoteActive=false; if(::pad.isInitialized) pad.clearContacts(); stopActive() }; super.onPause() }
    override fun onResume() { super.onResume(); activityResumed=true; if(gameMode=="SURVIVAL"&&survivalRunActive&&survivalPausedAtMs>0L) { val paused=SystemClock.elapsedRealtime()-survivalPausedAtMs; survivalEngine.shiftTime(paused); roundStart+=paused; survivalPausedAtMs=0L; roundInputGate.open(); survivalMotionPoint=localLeader; survivalMotionAtMs=SystemClock.elapsedRealtime() }; syncMenuMusic(); profileCredentials?.let { presenceClient.connect(getSharedPreferences("psychconnect",MODE_PRIVATE).getString("serverUrl","wss://psychconnect-09dn.onrender.com/ws")!!,it) }; if(pendingHarmonyHapticsConsent) showHarmonyHapticsConsent(); if(screen=="game") ui.post(tick) else if(screen!="loading") animationUi.post(animationTick) }
    override fun onDestroy() { activityResumed=false; stopMenuMusic(); soundPool?.release(); soundPool=null; animationUi.removeCallbacks(animationTick); stopActive(); presenceClient.close(); client.close(); onlineWorker.shutdownNow(); spriteLoader.shutdownNow(); spriteCache.values.forEach { if(!it.isRecycled) it.recycle() }; spriteCache.clear(); super.onDestroy() }

    private inner class TouchPad : View(this@MainActivity) {
        private val pointers=mutableMapOf<Int,Point>(); var secondActive=false
        private val palmStateMachine=PalmContactStateMachine()
        private val contactMeasurements=mutableMapOf<Int,PalmTouchMeasurement>()
        var palmSnapshot=PalmTouchSnapshot(false,0.0,false,emptyMap()); private set
        private val anchorHold=Runnable {
            val id=harmonyAnchorPointer
            if(id!=null&&pointers.containsKey(id)&&!harmonyAnchorLocal) {
                harmonyAnchorLocal=true
                if(harmonySharedHaptics) { harmonyGestureSequence++; client.sendHarmonyGesture("anchor_start",1,harmonyGestureSequence) }
            }
        }
        val touchCount get()=pointers.size
        private var abilityPointerId:Int?=null
        private val glowPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val ringPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style=android.graphics.Paint.Style.STROKE }
        private val glintPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val fingerPadPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val fingerRidgePaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style=android.graphics.Paint.Style.STROKE; strokeCap=android.graphics.Paint.Cap.ROUND; strokeJoin=android.graphics.Paint.Join.ROUND }
        private val fingerPadPath=android.graphics.Path()
        private val fingerRidgePath=android.graphics.Path()
        private val fingerPadBounds=RectF()
        private val trailPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val trailSamples=ArrayList<TouchTrailSample>(200)
        private val trailSegments=HashMap<String,Long>()
        private val harmonyColors=intArrayOf(PURPLE,CYAN,0xFFFF9BD6.toInt(),0xFF9CAEFF.toInt(),0xFF9BE8D7.toInt())
        val rawPalmMeasurements get()=contactMeasurements.toMap()
        fun clearRemoteTrails() { trailSamples.removeAll { it.owner.startsWith("remote") }; invalidate() }
        fun beginTrail(owner:String) { trailSegments[owner]=(trailSegments[owner]?:0L)+1L }
        fun endTrail(owner:String) { trailSegments[owner]=(trailSegments[owner]?:0L)+1L }
        fun addTrail(owner:String,point:Point,color:Int) {
            if(!touchTrails || reducedMotion || reducedVisualEffects) return
            val now=SystemClock.elapsedRealtime()
            val segmentOwner="$owner#${trailSegments[owner]?:0L}"
            val last=trailSamples.lastOrNull { it.owner==segmentOwner }
            if(last!=null && now-last.at<24L && hypot(point.x-last.point.x,point.y-last.point.y)<.009) return
            trailSamples.add(TouchTrailSample(segmentOwner,point,now,color))
            val capacity=((trailLifetimeMs/24L)*MAX_HARMONY_TOUCHES*2+128).coerceIn(360L,5_200L).toInt()
            while(trailSamples.size>capacity) trailSamples.removeAt(0)
        }
        fun clearContacts() { pointers.clear(); contactMeasurements.clear(); trailSamples.clear(); palmStateMachine.reset(); palmSnapshot=PalmTouchSnapshot(false,0.0,false,emptyMap()); secondActive=false; invalidate() }
        fun updatePalm(nowMs:Long):PalmTouchSnapshot {
            val observations=pointers.map { (id,point)->PalmTouchObservation(id,point,contactMeasurements[id]?:PalmTouchMeasurement(null,null,null,null)) }
            palmSnapshot=palmStateMachine.update(observations,min(width,height),nowMs)
            return palmSnapshot
        }
        fun sendHarmonySnapshot(now:Long) {
            touchSequence++
            client.sendHarmonyTouches(pointers.map { (id,point)->HarmonyTouch(id,point,harmonyFingerSizeClass(contactMeasurements[id],min(width,height))) },touchSequence,harmonySharedHaptics&&palmSnapshot.palmActive)
            lastNetworkSendMs=now
        }
        init { isFocusable=true; setBackgroundColor(BG) }
        override fun onTouchEvent(e:MotionEvent):Boolean {
            if(!roundInputGate.permits(gameMode,simMode,SystemClock.elapsedRealtime()-roundStart,durationSec*1000L)) {
                if(localActive) { touchSequence++; client.sendTouch(TouchState(false,null,SystemClock.elapsedRealtime(),touchSequence)) }
                pointers.clear(); abilityPointerId=null; localActive=false; haptics.stop(); hapticsRunning=false; invalidate()
                return true
            }
            fun point(index:Int)=Point.normalize(e.getX(index),e.getY(index),width.coerceAtLeast(1),height.coerceAtLeast(1))
            when(e.actionMasked) {
                MotionEvent.ACTION_DOWN,MotionEvent.ACTION_POINTER_DOWN -> {
                    val id=e.getPointerId(e.actionIndex)
                    val maxTouches=if(simMode) 2 else if(gameMode=="HARMONY") MAX_HARMONY_TOUCHES else 1
                    val tracked=pointers.size<maxTouches
                    if(tracked) {
                        pointers[id]=point(e.actionIndex)
                        if(gameMode=="HARMONY"&&!simMode) beginTrail("local:$id")
                        else if(pointers.size==1) beginTrail("local")
                    }
                    if(tracked&&!simMode&&gameMode=="HARMONY") {
                        val fingers=pointers.size.coerceIn(1,2)
                        haptics.harmonyTap(fingers)
                        if(harmonySharedHaptics) { harmonyGestureSequence++; client.sendHarmonyGesture("tap",fingers,harmonyGestureSequence) }
                        if(fingers==1) { harmonyAnchorPointer=id; ui.removeCallbacks(anchorHold); ui.postDelayed(anchorHold,550L) }
                    }
                    if(e.actionMasked==MotionEvent.ACTION_POINTER_DOWN && !simMode && gameMode=="DEATHMATCH" && pointers.isNotEmpty() && abilityPointerId==null) {
                        abilityPointerId=id
                        val abilityPoint=point(e.actionIndex)
                        if(currentRole=="FOLLOWER") client.useAbility("sonar") else client.useAbility("decoy",abilityPoint)
                    }
                }
                MotionEvent.ACTION_MOVE -> for(i in 0 until e.pointerCount) { val id=e.getPointerId(i); if(pointers.containsKey(id)) pointers[id]=point(i) }
                MotionEvent.ACTION_POINTER_UP,MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                    if(e.actionMasked==MotionEvent.ACTION_CANCEL || e.getPointerId(e.actionIndex)==harmonyAnchorPointer) {
                        ui.removeCallbacks(anchorHold); harmonyAnchorPointer=null
                        if(harmonyAnchorLocal) { harmonyAnchorLocal=false; if(harmonySharedHaptics) { harmonyGestureSequence++; client.sendHarmonyGesture("anchor_end",1,harmonyGestureSequence) } }
                    }
                    if(e.actionMasked==MotionEvent.ACTION_CANCEL) {
                        if(gameMode=="HARMONY"&&!simMode) pointers.keys.forEach { endTrail("local:$it") } else endTrail("local")
                        pointers.clear(); contactMeasurements.clear(); palmStateMachine.reset(); palmSnapshot=PalmTouchSnapshot(false,0.0,false,emptyMap()); abilityPointerId=null
                    }
                    else {
                        val id=e.getPointerId(e.actionIndex)
                        if(gameMode=="HARMONY" && !simMode) {
                            val eventIds=(0 until e.pointerCount).map { e.getPointerId(it) }
                            val nextIds=harmonyTouchIdsAfterPointerUp(pointers.keys.toList(),eventIds,id)
                            pointers.keys.toList().filter { it !in nextIds }.forEach { removedId -> endTrail("local:$removedId"); pointers.remove(removedId) }
                            contactMeasurements.keys.toList().filter { it !in nextIds }.forEach(contactMeasurements::remove)
                            nextIds.forEach { nextId ->
                                if(nextId !in pointers) {
                                    val index=(0 until e.pointerCount).firstOrNull { e.getPointerId(it)==nextId }
                                    if(index!=null) { beginTrail("local:$nextId"); pointers[nextId]=point(index) }
                                }
                            }
                        } else { if(pointers.remove(id)!=null && pointers.isEmpty()) endTrail("local") }
                        if(id==abilityPointerId) abilityPointerId=null
                    }
                }
            }
            if(gameMode=="HARMONY"&&!simMode) updateMeasurements(e)
            if(simMode) {
                val entries=pointers.entries.toList(); localActive=entries.isNotEmpty(); secondActive=entries.size>=2
                if(entries.isNotEmpty()) { val limited=speedLimiter.update(entries[0].value,SystemClock.elapsedRealtime()); localLeader=limited.point; if(limited.limited) speedWarningUntil=SystemClock.elapsedRealtime()+900 }
                if(entries.size>=2) localFollower=entries[1].value
            } else if(gameMode=="HARMONY") {
                val now=SystemClock.elapsedRealtime()
                localHarmonyMotion.record(pointers,now)
                localHarmonyRotation.record(pointers,now)
                updatePalm(now)
                localActive=pointers.isNotEmpty(); pointers.values.firstOrNull()?.let { localLeader=it }
                if(now-lastNetworkSendMs>=33L || e.actionMasked!=MotionEvent.ACTION_MOVE) {
                    sendHarmonySnapshot(now)
                }
            } else if(gameMode=="SURVIVAL") {
                localActive=pointers.isNotEmpty()
                pointers.values.firstOrNull()?.let { localLeader=it }
            } else if(pointers.isNotEmpty()) {
                val raw=pointers.values.first(); localActive=true
                val now=SystemClock.elapsedRealtime()
                val limited=if(isHost && gameMode=="DEATHMATCH") speedLimiter.update(raw,now) else LimitedMovement(raw,false)
                val p=limited.point; localLeader=p; if(limited.limited) { speedWarningUntil=now+900; invalidate() }
                if(now-lastNetworkSendMs>=33L || e.actionMasked!=MotionEvent.ACTION_MOVE) { touchSequence++; client.sendTouch(TouchState(true,p,now,touchSequence)); lastNetworkSendMs=now }
            } else { if(localActive) { touchSequence++; client.sendTouch(TouchState(false,null,SystemClock.elapsedRealtime(),touchSequence)) }; localActive=false }
            if(gameMode=="HARMONY"&&!simMode) pointers.forEach { (id,p) -> addTrail("local:$id",p,harmonyColors[id.mod(harmonyColors.size)]) }
            else if(pointers.isNotEmpty()) addTrail("local",pointers.values.first(),when(gameMode) { "DEATHMATCH"->PURPLE; "SURVIVAL"->0xFFFF654F.toInt(); else->0xFFFF9BD6.toInt() })
            invalidate(); return true
        }
        private fun updateMeasurements(event:MotionEvent) {
            contactMeasurements.keys.toList().filter { it !in pointers.keys }.forEach(contactMeasurements::remove)
            for(index in 0 until event.pointerCount) {
                val id=event.getPointerId(index)
                if(id !in pointers) continue
                contactMeasurements[id]=PalmTouchMeasurement(event.getSize(index),event.getTouchMajor(index),event.getTouchMinor(index),event.getPressure(index))
            }
        }
        override fun onDraw(c:android.graphics.Canvas) { super.onDraw(c)
            val roundInputOpen=simMode || roundInputGate.isOpen
            drawTouchTrails(c,roundInputOpen)
            val initialHint=connectionStartedAt!=0L && SystemClock.elapsedRealtime()-connectionStartedAt<3000L
            val showTarget=roundInputOpen && (if(simMode) secondActive else gameMode!="HARMONY" && remoteActive && when(gameMode) { "DEATHMATCH"->currentRole=="LEADER"; else->initialHint })
            val target=if(simMode) localFollower else interpolatedRemotePoint ?: remotePoint
            if(showTarget && target!=null) drawConnectionRing(c,target)
            if(roundInputOpen && !simMode && gameMode=="HARMONY") for((id,point) in remoteHarmonyPoints) drawHarmonyTouch(c,id,point,remoteHarmonyFingerSizes[id]?:1)
            if(roundInputOpen && !simMode && gameMode=="DEATHMATCH" && currentRole=="FOLLOWER") drawAbilityEffects(c)
            if(roundInputOpen && gameMode=="CONNECTION") drawTogetherWaves(c)
            if(roundInputOpen && gameMode=="SURVIVAL") drawSurvival(c)
            if(roundInputOpen && gameMode=="COOP_SURVIVAL") drawDuoSurvival(c)
            if(roundInputOpen && gameMode=="PUZZLE") drawPuzzle(c)
            drawLiveMetrics(c)
            drawTogetherCue(c)
            drawAbilityStatus(c)
            drawRoundClock(c)
            if(networkPaused) {
                val d=resources.displayMetrics.density
                c.drawColor(0xB8000000.toInt())
                val title=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFF3EFFA.toInt(); textSize=17f*d; textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) }
                val caption=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFB8B4C4.toInt(); textSize=12f*d; textAlign=android.graphics.Paint.Align.CENTER }
                c.drawText(networkPauseMessage,width/2f,height/2f,title)
                c.drawText("The round is paused. Touch again when play resumes.",width/2f,height/2f+28f*d,caption)
            }
            if(speedWarningUntil>SystemClock.elapsedRealtime()) { val p=android.graphics.Paint().apply { color=0xFFAAA1C5.toInt(); textSize=28f; textAlign=android.graphics.Paint.Align.CENTER }; c.drawText("Move a little more slowly",width/2f,52f,p) }
            if(developer) {
                if(gameMode=="PUZZLE") {
                    val paint=android.graphics.Paint().apply { color=CYAN; textSize=12f*resources.displayMetrics.density }
                    c.drawText("PUZZLE level=$puzzleLevel score=$puzzleScore hold=${puzzleHoldMs}ms walls=${puzzleWalls.size}",16f,112f,paint)
                } else if(gameMode=="HARMONY") drawHarmonyDiagnostics(c) else {
                    val paint=android.graphics.Paint().apply { color=CYAN; textSize=26f }
                    c.drawText("L ${"%.2f".format(localLeader.x)},${"%.2f".format(localLeader.y)}   R $remotePoint",24f,100f,paint)
                    c.drawText("touch=$localActive/$remoteActive · score ${ScoringEngine.score(samples,roundStart,SystemClock.elapsedRealtime()).score}",24f,138f,paint)
                    val net=client.networkDiagnostics.snapshot(lastRemoteServerTs,SystemClock.elapsedRealtime())
                    val rtt=net.rttMs?.let { "${it} ms" } ?: "—"
                    val jitter=net.jitterMs?.let { "${it} ms" } ?: "—"
                    val age=net.updateAgeMs?.let { "${it} ms" } ?: "—"
                    c.drawText("RTT $rtt · jitter $jitter · update age $age",24f,176f,paint)
                    c.drawText("Smoothing buffer ${client.networkDiagnostics.interpolationDelayMs()} ms",24f,214f,paint)
                }
            }
        }
        private fun drawTouchTrails(c:android.graphics.Canvas,roundInputOpen:Boolean) {
            if(!touchTrails || reducedMotion || reducedVisualEffects || !roundInputOpen) { trailSamples.clear(); return }
            val now=SystemClock.elapsedRealtime(); val life=trailLifetimeMs.toLong()
            trailSamples.removeAll { now-it.at>=life }
            val d=resources.displayMetrics.density
            val previous=HashMap<String,TouchTrailSample>()
            val particleSamples=trailSamples.count { now-it.at<520L }
            val particleStride=(particleSamples/48).coerceAtLeast(1)
            trailPaint.style=android.graphics.Paint.Style.STROKE; trailPaint.strokeCap=android.graphics.Paint.Cap.ROUND
            for((sampleIndex,sample) in trailSamples.withIndex()) {
                val age=(now-sample.at).toFloat()/life; val alpha=((1f-age)*106f).toInt().coerceIn(0,106)
                val x=(sample.point.x*width).toFloat(); val y=(sample.point.y*height).toFloat()
                val prior=previous[sample.owner]
                if(prior!=null) {
                    val px=(prior.point.x*width).toFloat(); val py=(prior.point.y*height).toFloat()
                    val red=android.graphics.Color.red(sample.color); val green=android.graphics.Color.green(sample.color); val blue=android.graphics.Color.blue(sample.color)
                    // A soft outer edge makes the touch path read as a flowing trail rather than a hairline.
                    trailPaint.color=android.graphics.Color.argb((alpha*.42f).toInt(),red,green,blue)
                    trailPaint.strokeWidth=(7f+2f*(1f-age))*d
                    c.drawLine(px,py,x,y,trailPaint)
                    trailPaint.color=android.graphics.Color.argb(alpha,red,green,blue)
                    trailPaint.strokeWidth=(3f+1.5f*(1f-age))*d
                    c.drawLine(px,py,x,y,trailPaint)

                    // Each fresh trail sample sheds one tiny droplet that drifts gently away from its path.
                    val particleLife=520L
                    val particleAge=(now-sample.at).toFloat()/particleLife
                    if(particleAge in 0f..1f&&sampleIndex%particleStride==0) {
                        val dx=x-px; val dy=y-py; val length=hypot(dx.toDouble(),dy.toDouble()).toFloat().coerceAtLeast(.001f)
                        val direction=if(((sample.at xor sample.owner.hashCode().toLong()) and 1L)==0L) 1f else -1f
                        trailPaint.style=android.graphics.Paint.Style.FILL
                        val fade=(1f-particleAge)
                        for(side in 0..1) {
                            val sign=if(side==0) direction else -direction
                            val drift=(particleAge*18f+side*2.5f)*d*sign
                            val particleX=x-(dy/length)*drift+(dx/length)*side*d*1.5f
                            val particleY=y+(dx/length)*drift+(dy/length)*side*d*1.5f
                            trailPaint.color=android.graphics.Color.argb((fade*68f).toInt(),red,green,blue)
                            c.drawCircle(particleX,particleY,(5f-1.5f*particleAge)*d,trailPaint)
                            trailPaint.color=android.graphics.Color.argb((fade*190f).toInt(),red,green,blue)
                            c.drawCircle(particleX,particleY,(2.1f-.7f*particleAge)*d,trailPaint)
                        }
                        trailPaint.style=android.graphics.Paint.Style.STROKE
                    }
                }
                previous[sample.owner]=sample
            }
            if(trailSamples.isNotEmpty()) postInvalidateOnAnimation()
        }
        private fun drawHarmonyDiagnostics(c:android.graphics.Canvas) {
            val d=resources.displayMetrics.density
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=CYAN; textSize=10f*d; typeface=android.graphics.Typeface.create("monospace",android.graphics.Typeface.NORMAL)
            }
            var y=68f*d
            c.drawText("LOCAL ${pointers.size}/5  ·  REMOTE ${remoteHarmonyPoints.size}/5",16f*d,y,paint); y+=14f*d
            pointers.entries.forEachIndexed { index,entry ->
                c.drawText("L${index+1}  ${"%.3f".format(entry.value.x)}, ${"%.3f".format(entry.value.y)}",16f*d,y,paint); y+=13f*d
            }
            remoteHarmonyPoints.values.forEachIndexed { index,point ->
                c.drawText("R${index+1}  ${"%.3f".format(point.x)}, ${"%.3f".format(point.y)}",16f*d,y,paint); y+=13f*d
            }
            val net=client.networkDiagnostics.snapshot(lastRemoteServerTs,SystemClock.elapsedRealtime())
            val rtt=net.rttMs?.let { "${it}ms" } ?: "—"
            val jitter=net.jitterMs?.let { "${it}ms" } ?: "—"
            val age=net.updateAgeMs?.let { "${it}ms" } ?: "—"
            c.drawText("RTT $rtt · jitter $jitter · update $age",16f*d,y,paint)
            y+=14f*d
            val palmClass=when { palmSnapshot.contacts.values.any { it.classification==PalmContactClass.PALM }->"PALM"; palmSnapshot.contacts.values.any { it.classification==PalmContactClass.CANDIDATE }->"CANDIDATE"; else->"FINGER" }
            c.drawText("Palm confidence ${"%.2f".format(palmSnapshot.confidence)} · local $palmClass · remote ${if(remotePalmContact)"PALM" else "FINGER"}",16f*d,y,paint); y+=14f*d
            c.drawText("sensor data ${if(palmSnapshot.sensorDataAvailable)"available" else "unavailable"} · feedback $palmFeedbackMode",16f*d,y,paint); y+=14f*d
            contactMeasurements.forEach { (id,m)->
                c.drawText("raw p$id size ${m.size?.let { "%.2f".format(it) }?:"—"} major ${m.touchMajor?.let { "%.1f".format(it) }?:"—"} minor ${m.touchMinor?.let { "%.1f".format(it) }?:"—"} pressure ${m.pressure?.let { "%.2f".format(it) }?:"—"}",16f*d,y,paint)
                y+=12f*d
            }
        }
        private fun drawLiveMetrics(c:android.graphics.Canvas) {
            val d=resources.displayMetrics.density
            val labels=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFF888694.toInt(); textSize=11f*d; textAlign=android.graphics.Paint.Align.CENTER }
            if(gameMode=="SURVIVAL") {
                labels.color=0xFFFF8769.toInt(); labels.textSize=11f*d
                c.drawText("SURVIVAL  ·  SOLO",width/2f,29f*d,labels)
                val values=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFFF6252.toInt(); textSize=27f*d; textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.NORMAL) }
                c.drawText(formatSurvivalTime(survivalEngine.elapsedMs),width/2f,65f*d,values)
                labels.color=0xFFFFB16D.toInt(); labels.textSize=10f*d
                c.drawText("THREAT LEVEL ${survivalEngine.level}",width/2f,84f*d,labels)
                return
            }
            if(gameMode=="COOP_SURVIVAL") {
                labels.color=0xFFFF9B70.toInt(); labels.textSize=11f*d
                c.drawText("SURVIVAL  ·  CO-OP",width/2f,29f*d,labels)
                val values=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFFF6252.toInt(); textSize=27f*d; textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-black",android.graphics.Typeface.NORMAL) }
                val elapsed=duoSurvivalElapsedMs+if(duoSurvivalStarted) ((SystemClock.elapsedRealtime()-duoStateAtMs).coerceAtLeast(0L).coerceAtMost(150L)*duoSurvivalMotionFactor).toLong() else 0L
                c.drawText(formatSurvivalTime(elapsed),width/2f,65f*d,values)
                labels.color=0xFFFFB16D.toInt(); labels.textSize=10f*d
                c.drawText(if(duoSurvivalStarted) "THREAT LEVEL $duoSurvivalLevel" else "$duoReadyCount/2 TOUCHES READY",width/2f,84f*d,labels)
                return
            }
            if(gameMode=="PUZZLE") {
                labels.color=0xFF9CE9DD.toInt(); labels.textSize=11f*d
                c.drawText("PUZZLE  ·  LEVEL $puzzleLevel",width/2f,29f*d,labels)
                val values=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFE2D9FF.toInt(); textSize=21f*d; textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) }
                val remaining=if(puzzleTimerStarted) (puzzleRemainingMs-(SystemClock.elapsedRealtime()-puzzleTimeAtMs)).coerceAtLeast(0L) else 60_000L
                c.drawText("TOTAL  $puzzleTotalScore",width/2f,58f*d,values)
                values.textSize=14f*d; values.color=0xFFCABDE9.toInt()
                c.drawText("LEVEL BUFFER  $puzzleScore  ·  ${formatSurvivalTime(remaining)}",width/2f,80f*d,values)
                labels.color=0xFFBAB6C7.toInt(); labels.textSize=9f*d
                val status=when { puzzleNeedsStart->"BOTH TOUCH THE START PLATE"; puzzleHoldMs>0->"BOTH CHECKPOINTS · HOLD"; else->"CHOOSE DIFFERENT CHECKPOINTS" }
                c.drawText(status,width/2f,98f*d,labels)
                return
            }
            if(gameMode=="HARMONY") {
                labels.color=0xFFB5A7D9.toInt()
                c.drawText("HARMONY · SENSORY SANDBOX · BACK TO EXIT",width/2f,30f*d,labels)
                labels.textSize=9f*d; labels.color=0xFF777581.toInt()
                c.drawText("UP TO FIVE FINGERS · USES THE TOUCHES THIS DEVICE REPORTS",width/2f,47f*d,labels)
                return
            }
            val values=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize=22f*d; textAlign=android.graphics.Paint.Align.CENTER; typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL) }
            val labelY=35f*d; val valueY=62f*d
            if(gameMode=="CONNECTION") {
                labels.color=0xFFAAA0CC.toInt(); c.drawText("TOGETHER · SHARED POINTS",width/2f,labelY,labels)
                values.color=PURPLE; c.drawText(liveScore.toString(),width/2f,valueY,values)
            } else {
                val centers=floatArrayOf(width*.3f,width*.7f)
                val ownId=client.playerId.orEmpty()
                val readings=intArrayOf(duelScores[ownId] ?: liveScore,duelScores.entries.firstOrNull { it.key!=ownId }?.value ?: 0)
                val names=arrayOf("YOU","OPPONENT")
                for(i in names.indices) {
                    labels.color=0xFF888694.toInt(); c.drawText(names[i],centers[i],labelY,labels)
                    values.color=if(i==0) PURPLE else CYAN; c.drawText(readings[i].toString(),centers[i],valueY,values)
                }
            }
        }
        private fun drawAbilityStatus(c:android.graphics.Canvas) {
            if(gameMode!="DEATHMATCH") return
            val d=resources.displayMetrics.density
            val name=if(currentRole=="LEADER") "DECOY" else "SONAR"
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=if(currentRole=="LEADER") PURPLE else CYAN
                textSize=10f*d; textAlign=android.graphics.Paint.Align.CENTER
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            }
            c.drawText("$name  $abilityUsesRemaining LEFT  ·  SECOND FINGER",width/2f,96f*d,paint)
        }
        private fun drawAbilityEffects(c:android.graphics.Canvas) {
            val now=SystemClock.elapsedRealtime(); abilityEffects.removeAll { now-it.startedAt>=it.durationMs }
            val d=resources.displayMetrics.density
            for(effect in abilityEffects) {
                val progress=((now-effect.startedAt).toFloat()/effect.durationMs.coerceAtLeast(1L)).coerceIn(0f,1f)
                val x=(effect.point.x*width).toFloat(); val y=(effect.point.y*height).toFloat()
                val color=if(effect.type=="sonar") CYAN else PURPLE
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style=android.graphics.Paint.Style.STROKE }
                for(ring in 0..2) {
                    val phase=((progress+ring/3f)%1f)
                    paint.color=android.graphics.Color.argb(((1f-phase)*170f).toInt(),android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color))
                    paint.strokeWidth=(2.2f-phase)*d
                    c.drawCircle(x,y,(18f+phase*90f)*d,paint)
                }
                paint.style=android.graphics.Paint.Style.FILL; paint.color=android.graphics.Color.argb(((1f-progress)*230f).toInt(),android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color))
                c.drawCircle(x,y,3f*d,paint)
            }
            if(abilityEffects.isNotEmpty()) postInvalidateOnAnimation()
        }
        private fun drawSurvival(c:android.graphics.Canvas) {
            val now=survivalEngine.hazardTimeMs; val d=resources.displayMetrics.density; val minDimension=min(width,height).toFloat()
            val fire=0xFFFF594F.toInt(); val amber=0xFFFFB04A.toInt()
            for(blast in survivalEngine.explosions()) {
                val x=(blast.center.x*width).toFloat(); val y=(blast.center.y*height).toFloat()
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                if(now<blast.detonatesAtMs) {
                    val pulse=.5f+.5f*kotlin.math.sin((now/95.0).toFloat())
                    paint.color=android.graphics.Color.argb((22f+pulse*25f).toInt(),255,70,56)
                    c.drawCircle(x,y,22f*d,paint)
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=(1.5f+pulse)*d; paint.color=android.graphics.Color.argb((120f+pulse*90f).toInt(),255,128,83)
                    c.drawCircle(x,y,(15f+pulse*5f)*d,paint)
                    paint.style=android.graphics.Paint.Style.FILL; paint.color=amber; c.drawCircle(x,y,2.6f*d,paint)
                } else {
                    val progress=((now-blast.detonatesAtMs).toFloat()/blast.durationMs).coerceIn(0f,1f)
                    val radius=blast.maxRadius.toFloat()*progress*minDimension
                    val fade=(1f-progress)
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=(5f*fade+1f)*d
                    paint.color=android.graphics.Color.argb((150f*fade).toInt(),255,76,66); c.drawCircle(x,y,radius,paint)
                    paint.strokeWidth=1.4f*d; paint.color=android.graphics.Color.argb((205f*fade).toInt(),255,177,83); c.drawCircle(x,y,(radius-4f*d).coerceAtLeast(0f),paint)
                    paint.style=android.graphics.Paint.Style.FILL; paint.color=android.graphics.Color.argb((55f*fade).toInt(),255,65,55)
                    c.drawCircle(x,y,(7f+22f*fade)*d,paint)
                }
            }
            for(projectile in survivalEngine.projectiles()) {
                val x=(projectile.point.x*width).toFloat(); val y=(projectile.point.y*height).toFloat()
                val vx=(projectile.velocityX*width).toFloat(); val vy=(projectile.velocityY*height).toFloat(); val speed=hypot(vx.toDouble(),vy.toDouble()).toFloat().coerceAtLeast(1f)
                val ux=vx/speed; val uy=vy/speed
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap=android.graphics.Paint.Cap.ROUND }
                paint.color=0x66FF3F55; paint.strokeWidth=11f*d; c.drawLine(x-ux*18f*d,y-uy*18f*d,x+ux*5f*d,y+uy*5f*d,paint)
                paint.color=fire; paint.strokeWidth=4f*d; c.drawLine(x-ux*15f*d,y-uy*15f*d,x+ux*3f*d,y+uy*3f*d,paint)
                paint.style=android.graphics.Paint.Style.FILL; paint.color=0xFFFFD18A.toInt(); c.drawCircle(x,y,2.3f*d,paint)
            }
            val px=(localLeader.x*width).toFloat(); val py=(localLeader.y*height).toFloat()
            val playerPaint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            playerPaint.color=0x40FF5148; c.drawCircle(px,py,30f*d,playerPaint)
            playerPaint.style=android.graphics.Paint.Style.STROKE; playerPaint.strokeWidth=2.3f*d; playerPaint.color=0xFFFF8C65.toInt(); c.drawCircle(px,py,17f*d,playerPaint)
            playerPaint.style=android.graphics.Paint.Style.FILL; playerPaint.color=0xFFFFE6CC.toInt(); c.drawCircle(px,py,4f*d,playerPaint)
            postInvalidateOnAnimation()
        }
        private fun drawDuoSurvival(c:android.graphics.Canvas) {
            val now=SystemClock.elapsedRealtime(); val d=resources.displayMetrics.density
            val fire=0xFFFF594F.toInt(); val amber=0xFFFFB04A.toInt()
            for(blast in duoBlasts) {
                val x=(blast.center.x*width).toFloat(); val y=(blast.center.y*height).toFloat(); val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                val radius=blast.radiusAt(now).toFloat()
                val isArmed=blast.armed&&radius<=0f
                if(isArmed) {
                    val pulse=.5f+.5f*kotlin.math.sin((now/95.0).toFloat())
                    paint.color=android.graphics.Color.argb((20f+pulse*24f).toInt(),255,70,56); c.drawCircle(x,y,22f*d,paint)
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=(1.5f+pulse)*d; paint.color=android.graphics.Color.argb((115f+pulse*85f).toInt(),255,128,83); c.drawCircle(x,y,(15f+pulse*5f)*d,paint)
                } else {
                    val progress=(radius/blast.maxRadius.toFloat().coerceAtLeast(.01f)).coerceIn(0f,1f); val fade=1f-progress
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=(5f*fade+1f)*d; paint.color=android.graphics.Color.argb((150f*fade).toInt(),255,76,66)
                    c.drawOval(RectF(x-radius*width,y-radius*height,x+radius*width,y+radius*height),paint)
                    paint.strokeWidth=1.4f*d; paint.color=android.graphics.Color.argb((205f*fade).toInt(),255,177,83)
                    val inner=(radius-4f*d/min(width,height)).coerceAtLeast(0f); c.drawOval(RectF(x-inner*width,y-inner*height,x+inner*width,y+inner*height),paint)
                }
            }
            val age=((now-duoStateAtMs).coerceAtMost(130L))/1000.0
            for(projectile in duoProjectiles) {
                val x=((projectile.point.x+projectile.velocityX*age)*width).toFloat(); val y=((projectile.point.y+projectile.velocityY*age)*height).toFloat()
                val vx=(projectile.velocityX*width).toFloat(); val vy=(projectile.velocityY*height).toFloat(); val speed=hypot(vx.toDouble(),vy.toDouble()).toFloat().coerceAtLeast(1f); val ux=vx/speed; val uy=vy/speed
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { strokeCap=android.graphics.Paint.Cap.ROUND; color=0x66FF3F55; strokeWidth=11f*d }
                c.drawLine(x-ux*18f*d,y-uy*18f*d,x+ux*5f*d,y+uy*5f*d,paint)
                paint.color=fire; paint.strokeWidth=4f*d; c.drawLine(x-ux*15f*d,y-uy*15f*d,x+ux*3f*d,y+uy*3f*d,paint)
                paint.style=android.graphics.Paint.Style.FILL; paint.color=0xFFFFD18A.toInt(); c.drawCircle(x,y,2.3f*d,paint)
            }
            drawPlayerPulse(c,localLeader,0xFFFF9C6B.toInt(),now)
            postInvalidateOnAnimation()
        }
        private fun drawPuzzle(c:android.graphics.Canvas) {
            val now=SystemClock.elapsedRealtime(); val d=resources.displayMetrics.density
            val hazardAge=((now-puzzleTimeAtMs).coerceAtLeast(0L).coerceAtMost(130L))/1000.0
            puzzleBlasts.forEach { blast ->
                val x=(blast.center.x*width).toFloat(); val y=(blast.center.y*height).toFloat()
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                if(blast.armed) {
                    paint.color=0x30FF9EBD; c.drawCircle(x,y,17f*d,paint)
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=2f*d; paint.color=0xD9FFC5DC.toInt(); c.drawCircle(x,y,13f*d,paint)
                } else {
                    val radius=blast.radius.toFloat()
                    paint.style=android.graphics.Paint.Style.STROKE; paint.strokeWidth=4f*d; paint.color=0x99FF91C7.toInt()
                    c.drawOval(RectF(x-radius*width,y-radius*height,x+radius*width,y+radius*height),paint)
                    paint.strokeWidth=1.5f*d; paint.color=0xB8FFD4E8.toInt()
                    val inner=(radius-.012f).coerceAtLeast(0f)
                    c.drawOval(RectF(x-inner*width,y-inner*height,x+inner*width,y+inner*height),paint)
                }
            }
            puzzleProjectiles.forEach { projectile ->
                val x=((projectile.point.x+projectile.velocityX*hazardAge)*width).toFloat(); val y=((projectile.point.y+projectile.velocityY*hazardAge)*height).toFloat()
                val vx=(projectile.velocityX*width).toFloat(); val vy=(projectile.velocityY*height).toFloat(); val length=hypot(vx.toDouble(),vy.toDouble()).toFloat().coerceAtLeast(1f)
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0x66F58BD0; strokeWidth=10f*d; strokeCap=android.graphics.Paint.Cap.ROUND }
                c.drawLine(x-vx/length*15f*d,y-vy/length*15f*d,x+vx/length*3f*d,y+vy/length*3f*d,paint)
                paint.color=0xFFF2B7FF.toInt(); paint.strokeWidth=3f*d; c.drawLine(x-vx/length*12f*d,y-vy/length*12f*d,x,y,paint)
                paint.style=android.graphics.Paint.Style.FILL; paint.color=0xFFFFFFFF.toInt(); c.drawCircle(x,y,2f*d,paint)
            }
            var sparkle=0
            for(wall in puzzleWalls) {
                val x1=(wall.x1*width).toFloat(); val y1=(wall.y1*height).toFloat(); val x2=(wall.x2*width).toFloat(); val y2=(wall.y2*height).toFloat()
                val outer=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0x403E9CAB; strokeWidth=10f*d; strokeCap=android.graphics.Paint.Cap.ROUND }
                c.drawLine(x1,y1,x2,y2,outer)
                val line=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xCC82DDE1.toInt(); strokeWidth=2.7f*d; strokeCap=android.graphics.Paint.Cap.ROUND }
                c.drawLine(x1,y1,x2,y2,line)
                val length=hypot((x2-x1).toDouble(),(y2-y1).toDouble()).toFloat(); val count=(length/(78f*d)).toInt().coerceAtLeast(1)
                val glint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFFFFDCF6.toInt(); style=android.graphics.Paint.Style.FILL }
                for(i in 0..count) {
                    val phase=(now/680.0+sparkle*.73+i*1.81); val alpha=(90+100*(.5+.5*kotlin.math.sin(phase))).toInt().coerceIn(0,190)
                    glint.alpha=alpha; val t=i.toFloat()/count; c.drawCircle(x1+(x2-x1)*t,y1+(y2-y1)*t,1.35f*d,glint)
                }
                sparkle++
            }
            val plateX=(puzzleStart.x*width).toFloat(); val plateY=(puzzleStart.y*height).toFloat()
            val plate=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            plate.color=0x344FDED2; c.drawCircle(plateX,plateY,34f*d,plate)
            plate.style=android.graphics.Paint.Style.STROKE; plate.strokeWidth=2f*d; plate.color=if(puzzleStarted) 0xFF85E7D1.toInt() else 0xFFB8A5FF.toInt(); c.drawCircle(plateX,plateY,25f*d,plate)
            plate.color=0x88FFFFFF.toInt(); plate.strokeWidth=1f*d; c.drawCircle(plateX,plateY,18f*d,plate)
            puzzleCheckpoints.forEachIndexed { index,point ->
                val x=(point.x*width).toFloat(); val y=(point.y*height).toFloat(); val pulse=.5f+.5f*kotlin.math.sin((now/530.0+index*Math.PI).toFloat())
                plate.style=android.graphics.Paint.Style.FILL; plate.color=android.graphics.Color.argb((18+pulse*26).toInt(),if(index==0) 112 else 190,if(index==0) 226 else 155,if(index==0) 222 else 255); c.drawCircle(x,y,26f*d,plate)
                plate.style=android.graphics.Paint.Style.STROKE; plate.strokeWidth=(1.6f+pulse*.7f)*d; plate.color=if(index==0) 0xFFD5FFFA.toInt() else 0xFFE5D7FF.toInt(); c.drawCircle(x,y,(13f+pulse*3f)*d,plate)
                plate.style=android.graphics.Paint.Style.FILL; plate.color=if(index==0) 0xFF78E4D9.toInt() else 0xFFBE9CFF.toInt(); c.drawCircle(x,y,3.2f*d,plate)
            }
            if(localActive) drawPlayerPulse(c,localLeader,if(puzzleCheckpointAccepted) 0xFFBDFBF0.toInt() else 0xFFF5E9FF.toInt(),now)
            if(puzzleHoldMs>0) {
                val left=width*.2f; val right=width*.8f; val y=height*.115f; val progress=(puzzleHoldMs/2500f).coerceIn(0f,1f)
                val track=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0x554E4A5D.toInt(); strokeWidth=5f*d; strokeCap=android.graphics.Paint.Cap.ROUND }
                c.drawLine(left,y,right,y,track); track.color=0xFF9BE7D6.toInt(); c.drawLine(left,y,left+(right-left)*progress,y,track)
            }
            postInvalidateOnAnimation()
        }
        private fun drawPlayerPulse(c:android.graphics.Canvas,point:Point,color:Int,now:Long) {
            val d=resources.displayMetrics.density; val x=(point.x*width).toFloat(); val y=(point.y*height).toFloat()
            val phase=(now%1150L)/1150f
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style=android.graphics.Paint.Style.STROKE; strokeWidth=2f*d; this.color=android.graphics.Color.argb(((1f-phase)*155f).toInt(),android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color)) }
            c.drawCircle(x,y,(8f+phase*22f)*d,paint)
            paint.style=android.graphics.Paint.Style.FILL; paint.color=android.graphics.Color.argb(55,android.graphics.Color.red(color),android.graphics.Color.green(color),android.graphics.Color.blue(color)); c.drawCircle(x,y,17f*d,paint)
            paint.color=color; c.drawCircle(x,y,5f*d,paint)
        }
        private fun drawTogetherWaves(c:android.graphics.Canvas) {
            val now=SystemClock.elapsedRealtime()
            togetherWaveEffects.removeAll { now>=it.startedAt+it.durationMs }
            val d=resources.displayMetrics.density
            val colors=intArrayOf(0xFFFFB8D7.toInt(),0xFFBFB3FF.toInt(),0xFF9EE6DF.toInt())
            for(wave in togetherWaveEffects) {
                if(now<wave.startedAt) continue
                val progress=((now-wave.startedAt).toFloat()/wave.durationMs).coerceIn(0f,1f)
                val cx=(wave.point.x*width).toFloat(); val cy=(wave.point.y*height).toFloat()
                val gaps=wave.gaps.map { ((it%(Math.PI*2.0))+(Math.PI*2.0))%(Math.PI*2.0) }.sorted()
                for(layer in 0..2) {
                    val radius=(progress*wave.maxRadius.toFloat()-layer*0.035f).coerceAtLeast(0f)
                    if(radius<0.01f) continue
                    val fade=(1f-progress)*if(layer==0) 0.52f else 0.28f
                    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        style=android.graphics.Paint.Style.STROKE; strokeWidth=(2.4f-layer*.35f)*d
                        color=android.graphics.Color.argb((fade*255).toInt(),android.graphics.Color.red(colors[layer]),android.graphics.Color.green(colors[layer]),android.graphics.Color.blue(colors[layer]))
                    }
                    val oval=android.graphics.RectF(cx-radius*width,cy-radius*height,cx+radius*width,cy+radius*height)
                    gaps.forEachIndexed { index,gap ->
                        val next=if(index==gaps.lastIndex) gaps.first()+Math.PI*2.0 else gaps[index+1]
                        val start=Math.toDegrees(gap+0.48).toFloat()
                        val sweep=Math.toDegrees((next-gap)-0.96).toFloat().coerceAtLeast(0f)
                        if(sweep>1f) c.drawArc(oval,start,sweep,false,paint)
                    }
                }
            }
            if(togetherWaveEffects.isNotEmpty()) postInvalidateOnAnimation()
        }
        private fun drawTogetherCue(c:android.graphics.Canvas) {
            if(gameMode!="CONNECTION"||togetherWaveCueUntil<=SystemClock.elapsedRealtime()) return
            val d=resources.displayMetrics.density
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=0xFFFFD0E5.toInt(); alpha=180; textSize=11f*d; textAlign=android.graphics.Paint.Align.CENTER
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            }
            c.drawText(togetherWaveCue,width/2f,88f*d,paint)
        }
        private fun drawHarmonyTouch(c:android.graphics.Canvas,id:Int,point:Point,sizeClass:Int) {
            val d=resources.displayMetrics.density
            val x=(point.x*width).toFloat(); val y=(point.y*height).toFloat()
            val color=harmonyColors[id%harmonyColors.size]
            val scale=when(sizeClass.coerceIn(0,2)) { 0->.78f; 2->1.26f; else->1f }
            val r=android.graphics.Color.red(color); val g=android.graphics.Color.green(color); val b=android.graphics.Color.blue(color)
            // A lit fingertip pad, rather than a floating fingerprint icon: soft contact halo,
            // rounded skin-like silhouette and fine curved ridge lines inside the pad.
            glowPaint.color=android.graphics.Color.argb(38,r,g,b)
            c.drawOval(x-20f*d*scale,y-25f*d*scale,x+20f*d*scale,y+25f*d*scale,glowPaint)

            val halfW=12.5f*d*scale; val halfH=17f*d*scale
            fingerPadPath.reset()
            fingerPadPath.moveTo(x,y-halfH)
            fingerPadPath.cubicTo(x-halfW*.78f,y-halfH,x-halfW,y-halfH*.53f,x-halfW,y-halfH*.12f)
            fingerPadPath.cubicTo(x-halfW,y+halfH*.55f,x-halfW*.55f,y+halfH,x,y+halfH)
            fingerPadPath.cubicTo(x+halfW*.55f,y+halfH,x+halfW,y+halfH*.55f,x+halfW,y-halfH*.12f)
            fingerPadPath.cubicTo(x+halfW,y-halfH*.53f,x+halfW*.78f,y-halfH,x,y-halfH)
            fingerPadPath.close()
            fingerPadBounds.set(x-halfW,y-halfH,x+halfW,y+halfH)
            fingerPadPaint.color=android.graphics.Color.argb(158,r,g,b)
            c.drawPath(fingerPadPath,fingerPadPaint)
            fingerRidgePaint.color=android.graphics.Color.argb(190,r,g,b)
            fingerRidgePaint.strokeWidth=1.05f*d*scale
            c.save(); c.clipPath(fingerPadPath)
            // Nested, flowing ridges read as the actual whorls of a fingertip at the contact point.
            for(index in 0..5) {
                val inset=index*2.65f*d*scale
                fingerPadBounds.set(x-halfW+inset,y-halfH+inset,x+halfW-inset,y+halfH-inset)
                if(fingerPadBounds.width()>2f*d && fingerPadBounds.height()>3f*d)
                    c.drawArc(fingerPadBounds,206f,128f,false,fingerRidgePaint)
            }
            fingerRidgePaint.color=0xE9FFFFFF.toInt(); fingerRidgePaint.strokeWidth=.85f*d*scale
            fingerRidgePath.reset(); fingerRidgePath.moveTo(x-3f*d*scale,y+4f*d*scale)
            fingerRidgePath.cubicTo(x-7f*d*scale,y-5f*d*scale,x+6f*d*scale,y-12f*d*scale,x+5f*d*scale,y-2f*d*scale)
            fingerRidgePath.cubicTo(x+4f*d*scale,y+5f*d*scale,x-4f*d*scale,y+8f*d*scale,x-4f*d*scale,y+1f*d*scale)
            c.drawPath(fingerRidgePath,fingerRidgePaint)
            c.restore()
            ringPaint.color=android.graphics.Color.argb(205,255,255,255); ringPaint.strokeWidth=.9f*d*scale
            c.drawPath(fingerPadPath,ringPaint)
            // Small highlight on the rounded pad gives the impression of a finger gently resting on glass.
            glowPaint.color=0x65FFFFFF.toInt()
            c.drawOval(x-5f*d*scale,y-12f*d*scale,x+2f*d*scale,y-8f*d*scale,glowPaint)
        }
        private fun drawRoundClock(c:android.graphics.Canvas) {
            if(gameMode in setOf("HARMONY","SURVIVAL","COOP_SURVIVAL","PUZZLE")) return
            val d=resources.displayMetrics.density
            val durationMs=durationSec*1000L
            val elapsed=(SystemClock.elapsedRealtime()-roundStart).coerceIn(0L,durationMs)
            val remaining=((durationMs-elapsed)/1000L).coerceAtLeast(0L)
            val clock=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color=0xFFAAA9B5.toInt(); textSize=14f*d; textAlign=android.graphics.Paint.Align.CENTER
                typeface=android.graphics.Typeface.create("sans-serif-medium",android.graphics.Typeface.NORMAL)
            }
            c.drawText("${remaining/60}:${(remaining%60).toString().padStart(2,'0')}",width/2f,height-23f*d,clock)
            val track=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=0xFF25232D.toInt() }
            val progress=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=CYAN }
            val bottom=height.toFloat()
            c.drawRect(0f,bottom-2f*d,width.toFloat(),bottom,track)
            c.drawRect(0f,bottom-2f*d,width*(elapsed.toFloat()/durationMs),bottom,progress)
        }
        private fun drawConnectionRing(c:android.graphics.Canvas,target:Point) {
            val x=(target.x*width).toFloat(); val y=(target.y*height).toFloat(); val d=resources.displayMetrics.density
            val glowRadius=50f*d
            val glowAlpha=58
            glowPaint.shader=android.graphics.RadialGradient(x,y,glowRadius,
                intArrayOf(android.graphics.Color.argb(glowAlpha,255,189,231),android.graphics.Color.argb(glowAlpha/2,245,118,210),0x00F576D2),floatArrayOf(0f,.55f,1f),android.graphics.Shader.TileMode.CLAMP)
            c.drawCircle(x,y,glowRadius,glowPaint); glowPaint.shader=null
            ringPaint.color=0xC8FFBAE7.toInt(); ringPaint.strokeWidth=2f*d; c.drawCircle(x,y,18f*d,ringPaint)
            ringPaint.color=0x70F17BCB.toInt(); ringPaint.strokeWidth=1.3f*d; c.drawCircle(x,y,27f*d,ringPaint)
            drawWaterRipples(c,x,y,d,SystemClock.elapsedRealtime())
            for(i in 0..4) {
                val angle=(SystemClock.elapsedRealtime()/420.0+i*Math.PI*2/5).toFloat()
                val sparkle=27f*d; val sx=x+kotlin.math.cos(angle)*sparkle; val sy=y+kotlin.math.sin(angle)*sparkle
                glintPaint.color=if(i%2==0) 0xDFFFE7F7.toInt() else 0xBFFF91D6.toInt()
                c.drawCircle(sx,sy,1.6f*d,glintPaint)
            }
        }
        private fun drawWaterRipples(c:android.graphics.Canvas,x:Float,y:Float,d:Float,now:Long) {
            val lifetime=(60_000.0/currentBpm*1.04).toLong().coerceAtLeast(280L)
            beatHistory.removeAll { now-it.first>lifetime }
            for((startedAt,strength) in beatHistory) {
                val age=now-startedAt
                if(age !in 0L..lifetime) continue
                val progress=age.toFloat()/lifetime
                val radius=(32f+progress*130f)*d
                val alpha=((1f-progress)*82f*strength).toInt().coerceIn(0,96)
                ringPaint.color=android.graphics.Color.argb(alpha,255,157,220)
                ringPaint.strokeWidth=(1.8f*(1f-progress*.45f))*d
                c.drawCircle(x,y,radius,ringPaint)
            }
        }
    }
}
