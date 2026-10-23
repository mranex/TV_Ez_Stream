package vn.aquastream.tv

import android.app.Application
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AquaApplication:Application(){override fun onCreate(){super.onCreate();BridgeConnection.initialize(this)}}
enum class TransportMode { DIRECT_LAN, ADB_REVERSE_LEGACY }
data class BridgeSession(val mode:TransportMode,val baseUrl:String,val serverId:String,val credential:String) {
    override fun toString()="BridgeSession(mode=$mode, serverId=$serverId, credential=<redacted>)"
    val headers get()=if(mode==TransportMode.DIRECT_LAN && credential.isNotEmpty())mapOf("Authorization" to "Bearer $credential")else emptyMap()
}
data class LanServer(val id:String,val name:String,val host:String,val port:Int,val manualHost:String="") {val baseUrl get()="http://$host:$port"}

/** One endpoint/credential owner for browse, progress, HEAD and Media3. */
object BridgeConnection {
    private lateinit var context:Context
    private val prefs get()=context.getSharedPreferences("bridge_connection",0)
    @Volatile private var current=BridgeSession(TransportMode.DIRECT_LAN,"","","")
    private var lastRecovery=0L
    private val recoveryLock=Any()
    val legacyPort get()=prefs.getInt("legacy_port",8765)
    fun setLegacyPort(port:Int){require(port in 1..65535);prefs.edit().putInt("legacy_port",port).apply();restore()}
    fun initialize(c:Context){
        context=c.applicationContext
        if(!prefs.contains("mode")){
            val info=c.packageManager.getPackageInfo(c.packageName,0)
            val existing=c.getSharedPreferences("seek",0).all.isNotEmpty() || info.lastUpdateTime-info.firstInstallTime>1000
            prefs.edit().putString("mode",if(existing)TransportMode.ADB_REVERSE_LEGACY.name else TransportMode.DIRECT_LAN.name).commit()
        }
        restore()
    }
    @Synchronized private fun restore(){
        val mode=TransportMode.valueOf(prefs.getString("mode",TransportMode.DIRECT_LAN.name)!!)
        if(mode==TransportMode.ADB_REVERSE_LEGACY){current=BridgeSession(mode,"http://127.0.0.1:$legacyPort","","");return}
        val id=prefs.getString("selected","")!!;val item=known().optJSONObject(id)
        val token=try{item?.optString("credential")?.takeIf{it.isNotBlank()}?.let(::decrypt)?:""}catch(_:Exception){""}
        current=BridgeSession(mode,item?.optString("url")?:"",id,token)
    }
    fun snapshot()=current
    val needsSetup get()=current.mode==TransportMode.DIRECT_LAN && (current.serverId.isBlank() || current.credential.isBlank())
    @Synchronized fun setMode(mode:TransportMode){prefs.edit().putString("mode",mode.name).apply();restore()}
    private fun known()=JSONObject(prefs.getString("servers","{}")!!)
    fun isPaired(id:String)=known().optJSONObject(id)?.optString("credential")?.isNotBlank()==true
    fun selectedName()=known().optJSONObject(current.serverId)?.optString("name")?:""
    fun deviceId():String=prefs.getString("device_id",null)?:UUID.randomUUID().toString().also{prefs.edit().putString("device_id",it).commit()}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        (store.getKey("AquaStream.Connection",null)as?SecretKey)?.let{return it}
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("AquaStream.Connection",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun encrypt(token:String):String {val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());return Base64.encodeToString(c.iv+c.doFinal(token.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)}
    private fun decrypt(value:String):String {val b=Base64.decode(value,Base64.NO_WRAP);val c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,b.copyOfRange(0,12)));return String(c.doFinal(b.copyOfRange(12,b.size)),Charsets.UTF_8)}
    fun endpoint(host:String,port:Int):String {
        require(port in 1..65535 && host.isNotBlank() && host.length<=253 && !host.contains('/') && !host.contains('@')){"Địa chỉ hoặc cổng không hợp lệ"}
        val address=InetAddress.getAllByName(host).filterIsInstance<Inet4Address>().firstOrNull{it.isSiteLocalAddress || it.isLinkLocalAddress}
            ?:throw IOException("Chỉ cho phép địa chỉ IPv4 trong mạng nội bộ")
        return "http://${address.hostAddress}:$port"
    }
    fun identity(base:String,expected:String?=null):LanServer {
        val uri=URL(base);val safe=endpoint(uri.host,uri.port.takeIf{it>0}?:80)
        val result=BridgeApi.raw(safe,"/identity")
        if(result.optString("mode")!="DIRECT_LAN")throw IOException("PC đang ở Legacy. Chọn cùng chế độ trên PC và TV.")
        if(result.optInt("protocol_version")!=1)throw IOException("Phiên bản Bridge không tương thích")
        val id=UUID.fromString(result.getString("server_id")).toString()
        if(expected!=null && id!=expected)throw IOException("Danh tính máy tính không khớp; không gửi credential")
        val address=URL(safe);return LanServer(id,result.getString("name"),address.host,address.port)
    }
    fun select(server:LanServer,recoveryOwner:BridgeSession?=null){
        val actual=identity(server.baseUrl,server.id);val all=known()
        val item=all.optJSONObject(server.id)?:throw IOException("Chưa ghép đôi máy tính này")
        val token=try{decrypt(item.getString("credential"))}catch(_:Exception){throw IOException("Credential không đọc được. Hãy ghép đôi lại.")}
        val session=BridgeSession(TransportMode.DIRECT_LAN,actual.baseUrl,actual.id,token)
        BridgeApi.raw(session.baseUrl,"/session",headers=session.headers)
        if(Thread.currentThread().isInterrupted)throw IOException("Đã hủy kết nối")
        synchronized(this){
            if(recoveryOwner!=null)requireSame(recoveryOwner)
            item.put("url",actual.baseUrl).put("name",actual.name)
            if(server.manualHost.isNotBlank())item.put("manual_host",server.manualHost)
            all.put(server.id,item)
            prefs.edit().putString("servers",all.toString()).putString("selected",server.id).putString("mode",TransportMode.DIRECT_LAN.name).commit();current=session
        }
    }
    fun pair(server:LanServer,pin:String){
        require(pin.matches(Regex("[0-9]{6}"))){"Nhập đủ 6 chữ số"}
        val actual=identity(server.baseUrl,server.id)
        val result=BridgeApi.raw(actual.baseUrl,"/pair","POST",JSONObject().put("pin",pin).put("device_id",deviceId()).put("device_name",Build.MODEL.take(80)))
        if(result.getString("server_id")!=actual.id)throw IOException("Danh tính phản hồi không khớp")
        if(Thread.currentThread().isInterrupted)throw IOException("Đã hủy ghép đôi")
        val all=known().put(actual.id,JSONObject().put("url",actual.baseUrl).put("name",actual.name).put("manual_host",server.manualHost).put("credential",encrypt(result.getString("credential"))))
        prefs.edit().putString("servers",all.toString()).commit();select(actual)
    }
    fun recover(owner:BridgeSession):Boolean = synchronized(recoveryLock) {
        if(owner.mode!=TransportMode.DIRECT_LAN || owner.serverId.isBlank() || current.mode!=owner.mode || current.serverId!=owner.serverId)return false
        if(current.baseUrl!=owner.baseUrl)return true
        val now=android.os.SystemClock.elapsedRealtime();if(now-lastRecovery<5000)return false;lastRecovery=now
        val server=LanDiscovery.find(context,owner.serverId,8000)?:try{
            val item=known().optJSONObject(owner.serverId);val hint=item?.optString("manual_host")?:""
            if(hint.isBlank())null else identity(endpoint(hint,URL(item!!.getString("url")).port),owner.serverId).copy(manualHost=hint)
        }catch(_:Exception){null}?:return false
        return try{select(server,owner);true}catch(_:Exception){false}
    }
    fun requireSame(owner:BridgeSession):BridgeSession {
        val value=current
        if(owner.mode!=value.mode || owner.serverId!=value.serverId)throw IOException("Kết nối đã đổi. Mở lại video.")
        return value
    }
}
