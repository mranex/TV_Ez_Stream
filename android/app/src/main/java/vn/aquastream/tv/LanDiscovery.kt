package vn.aquastream.tv

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** API 23/28-compatible bounded DNS-SD, one resolver and one multicast lock. */
class LanDiscovery(private val context:Context,private val found:(LanServer)->Unit,private val lost:(String)->Unit={},private val failure:(String)->Unit={}) {
    companion object {
        private var active:LanDiscovery?=null
        fun find(c:Context,id:String,timeout:Long):LanServer? {
            check(Looper.myLooper()!=Looper.getMainLooper())
            val latch=CountDownLatch(1);var result:LanServer?=null
            val session=LanDiscovery(c,{if(it.id==id){result=it;latch.countDown()}})
            val main=Handler(Looper.getMainLooper());main.post{session.start()}
            try{latch.await(timeout,TimeUnit.MILLISECONDS)}finally{main.post{session.stop()}}
            return result
        }
    }
    private val main=Handler(Looper.getMainLooper())
    private val manager=context.getSystemService(Context.NSD_SERVICE)as NsdManager
    private val queue=ArrayDeque<NsdServiceInfo>()
    private val names=HashSet<String>()
    private val identities=HashMap<String,String>()
    private var resolving=false
    private var running=false
    private var lock:WifiManager.MulticastLock?=null
    private val timeout=Runnable{stop()}
    private val listener=object:NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type:String){android.util.Log.d("AquaDiscovery","started $type")}
        override fun onDiscoveryStopped(type:String){}
        override fun onStartDiscoveryFailed(type:String,code:Int){android.util.Log.w("AquaDiscovery","start failed $code");main.post{failure("Không tìm được PC ($code). Thử nhập địa chỉ thủ công.");stop()}}
        override fun onStopDiscoveryFailed(type:String,code:Int){}
        override fun onServiceFound(info:NsdServiceInfo){main.post{
            android.util.Log.d("AquaDiscovery","found type=${info.serviceType}")
            if(running && info.serviceType.trimEnd('.')=="_aquastream._tcp" && names.size<64 && names.add(info.serviceName)){queue.addLast(info);resolveNext()}
        }}
        override fun onServiceLost(info:NsdServiceInfo){main.post{if(running){names.remove(info.serviceName);identities.remove(info.serviceName)?.let(lost)}}}
    }
    fun start(){
        active?.stop();active=this;running=true
        try{
            val wifi=context.applicationContext.getSystemService(Context.WIFI_SERVICE)as WifiManager
            lock=wifi.createMulticastLock("AquaStream.discovery").apply{setReferenceCounted(false);acquire()}
            manager.discoverServices("_aquastream._tcp.",NsdManager.PROTOCOL_DNS_SD,listener);main.postDelayed(timeout,30000)
        }catch(_:Exception){failure("Không thể tìm PC. Thử nhập địa chỉ thủ công.");stop()}
    }
    private fun resolveNext(){
        if(!running || resolving || queue.isEmpty())return
        resolving=true;val info=queue.removeFirst()
        try{manager.resolveService(info,object:NsdManager.ResolveListener {
            override fun onResolveFailed(service:NsdServiceInfo,code:Int){android.util.Log.w("AquaDiscovery","resolve failed $code");main.post{if(running){resolving=false;names.remove(info.serviceName);resolveNext()}}}
            override fun onServiceResolved(service:NsdServiceInfo){main.post{
                if(!running)return@post
                resolving=false
                try{
                    val id=UUID.fromString(String(service.attributes["server_id"]?:byteArrayOf(),Charsets.UTF_8)).toString()
                    val version=String(service.attributes["version"]?:byteArrayOf(),Charsets.UTF_8);val host=service.host
                    if(version=="1" && host is Inet4Address && host.isSiteLocalAddress && service.port in 1..65535){
                        identities[service.serviceName]=id
                        val name=String(service.attributes["name"]?:service.serviceName.toByteArray(),Charsets.UTF_8).take(80)
                        found(LanServer(id,name,host.hostAddress!!,service.port))
                    }
                }catch(_:Exception){}
                resolveNext()
            }}
        })}catch(_:Exception){resolving=false;resolveNext()}
    }
    fun stop(){
        if(!running)return
        running=false;main.removeCallbacks(timeout);try{manager.stopServiceDiscovery(listener)}catch(_:Exception){}
        if(lock?.isHeld==true)lock?.release();lock=null;queue.clear();names.clear();identities.clear();resolving=false
        if(active===this)active=null
    }
}
