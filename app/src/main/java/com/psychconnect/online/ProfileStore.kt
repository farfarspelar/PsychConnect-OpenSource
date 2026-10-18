package com.psychconnect.online

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the bearer credential encrypted with a non-exportable Android Keystore key. */
class ProfileStore(context: Context) {
    private val prefs=context.getSharedPreferences("online_profile",Context.MODE_PRIVATE)
    private val alias="psychconnect-profile-v1"
    data class Credentials(val profileId:String,val profileCode:String,val nickname:String,val avatarId:Int,val token:String)

    fun get():Credentials? = try {
        val token=decrypt(prefs.getString("credential",null)) ?: throw IllegalStateException("Missing credential")
        Credentials(prefs.getString("id","")!!,prefs.getString("code","")!!,prefs.getString("nickname","")!!,prefs.getInt("avatar",0),token).takeIf { it.profileId.isNotBlank() }
    } catch (_:Exception) { null }

    fun save(profileId:String,profileCode:String,nickname:String,avatarId:Int,token:String) {
        prefs.edit().putString("credential",encrypt(token)).putString("id",profileId).putString("code",profileCode).putString("nickname",nickname).putInt("avatar",avatarId).apply()
    }
    fun saveRecoveryCode(code:String) { prefs.edit().putString("pending_recovery",encrypt(code)).apply() }
    fun pendingRecoveryCode():String? = try { decrypt(prefs.getString("pending_recovery",null)) } catch (_:Exception) { null }
    fun clearRecoveryCode() { prefs.edit().remove("pending_recovery").apply() }
    fun clear(){ prefs.edit().clear().apply() }
    private fun encrypt(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE,key()) }
        val ciphertext=cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed=ByteBuffer.allocate(4+cipher.iv.size+ciphertext.size).putInt(cipher.iv.size).put(cipher.iv).put(ciphertext).array()
        return Base64.encodeToString(packed,Base64.NO_WRAP)
    }
    private fun decrypt(value:String?):String? {
        if(value==null) return null
        val buffer=ByteBuffer.wrap(Base64.decode(value,Base64.NO_WRAP))
        val iv=ByteArray(buffer.int).also(buffer::get)
        val ciphertext=ByteArray(buffer.remaining()).also(buffer::get)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,iv)) }
        return String(cipher.doFinal(ciphertext),Charsets.UTF_8)
    }
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        val generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setRandomizedEncryptionRequired(true).build())
        return generator.generateKey()
    }
}
