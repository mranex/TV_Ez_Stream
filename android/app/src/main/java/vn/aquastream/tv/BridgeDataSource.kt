package vn.aquastream.tv

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.*
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Transport adapter only. Legacy keeps its original DefaultHttpDataSource. */
@UnstableApi
class BridgeDataSource(private val owner:BridgeSession):DataSource {
    companion object {fun factory(listener:TransferListener)=DataSource.Factory {BridgeDataSource(BridgeConnection.snapshot()).apply{addTransferListener(listener)}}}
    private val listeners=ArrayList<TransferListener>()
    private var source:DataSource?=null
    private var spec:DataSpec?=null
    private var delivered=0L
    private var recovered=false
    private fun openCurrent(value:DataSpec):Long {
        val session=BridgeConnection.requireSame(owner)
        val path=value.uri.encodedPath?:throw IOException("Invalid stream path")
        if(!path.startsWith("/stream/") || value.uri.encodedQuery!=null)throw IOException("Invalid media endpoint")
        val actual=value.buildUpon().setUri(session.baseUrl+path).build()
        val data=if(session.mode==TransportMode.ADB_REVERSE_LEGACY) DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(5000).setReadTimeoutMs(8000).setUserAgent("AquaStream/1.0").createDataSource() else LanHttpSource(session)
        listeners.forEach{data.addTransferListener(it)};source=data
        return data.open(actual)
    }
    override fun open(value:DataSpec):Long {
        spec=value;delivered=0;recovered=false
        return try{openCurrent(value)}catch(e:IOException){
            if(!retry(e))throw e
            openCurrent(value)
        }
    }
    private fun retry(error:IOException):Boolean {
        if(recovered || error is BridgeFailure)return false
        recovered=true
        try{source?.close()}catch(_:IOException){}
        return BridgeConnection.recover(owner)
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        val n=try{source!!.read(buffer,offset,length)}catch(e:IOException){
            if(!retry(e))throw e
            openCurrent(spec!!.subrange(delivered));source!!.read(buffer,offset,length)
        }
        if(n>0)delivered+=n
        return n
    }
    override fun close(){try{source?.close()}finally{source=null;spec=null}}
    override fun getUri():Uri?=source?.uri
    override fun getResponseHeaders():Map<String,List<String>> = source?.responseHeaders?:emptyMap()
    override fun addTransferListener(listener:TransferListener){listeners.add(listener)}
}

/** Direct LAN HTTP Range source: no redirect forwarding of credentials, no cache/full download.
 * Uses the same platform HTTP stack. Media3 still owns extraction, allocator, decoder and retries.
 */
@UnstableApi
private class LanHttpSource(private val session:BridgeSession):BaseDataSource(true) {
    private var connection:HttpURLConnection?=null
    private var input:InputStream?=null
    private var remaining=C.LENGTH_UNSET.toLong()
    private var started=false
    override fun open(spec:DataSpec):Long {
        transferInitializing(spec)
        val url=URL(spec.uri.toString())
        if(!url.toString().startsWith(session.baseUrl+"/stream/"))throw IOException("Media origin changed")
        val c=url.openConnection()as HttpURLConnection;connection=c
        c.connectTimeout=5000;c.readTimeout=8000;c.instanceFollowRedirects=false
        c.setRequestProperty("User-Agent","AquaStream/1.0");c.setRequestProperty("Accept-Encoding","identity")
        session.headers.forEach{(k,v)->c.setRequestProperty(k,v)}
        if(spec.position>0 || spec.length!=C.LENGTH_UNSET.toLong()) c.setRequestProperty("Range","bytes=${spec.position}-"+if(spec.length==C.LENGTH_UNSET.toLong())"" else "${spec.position+spec.length-1}")
        val code=c.responseCode
        if(code==416 && c.getHeaderField("Content-Range")=="bytes */${spec.position}"){remaining=0;transferStarted(spec);started=true;return 0}
        if(code!=200 && code!=206)throw BridgeFailure(code,if(code==401)"Quyền truy cập đã bị thu hồi. Ghép đôi lại với PC." else "HTTP $code từ PC")
        if(spec.position>0 && code!=206)throw IOException("PC không trả HTTP Range")
        remaining=if(spec.length!=C.LENGTH_UNSET.toLong())spec.length else c.getHeaderField("Content-Length")?.toLongOrNull()?:C.LENGTH_UNSET.toLong()
        input=c.inputStream;transferStarted(spec);started=true;return remaining
    }
    override fun read(buffer:ByteArray,offset:Int,length:Int):Int {
        if(length==0)return 0
        if(remaining==0L)return C.RESULT_END_OF_INPUT
        val count=input!!.read(buffer,offset,if(remaining<0)length else minOf(remaining,length.toLong()).toInt())
        if(count<0){if(remaining>0)throw EOFException("Stream interrupted");return C.RESULT_END_OF_INPUT}
        if(remaining>0)remaining-=count
        bytesTransferred(count);return count
    }
    override fun getUri():Uri?=connection?.url?.toString()?.let(Uri::parse)
    override fun getResponseHeaders():Map<String,List<String>> = connection?.headerFields?.filterKeys{it!=null}?:emptyMap()
    override fun close(){try{input?.close()}finally{input=null;connection?.disconnect();connection=null;if(started){started=false;transferEnded()}}}
}
