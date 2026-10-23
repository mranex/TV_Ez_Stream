package vn.aquastream.tv

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class BridgeFailure(val status:Int,message:String):IOException(message)
object BridgeApi {
    val BASE get()=BridgeConnection.snapshot().baseUrl
    fun get(path:String)=request(path)
    fun put(path:String,value:JSONObject)=request(path,"PUT",value)
    fun raw(base:String,path:String,method:String="GET",value:JSONObject?=null,headers:Map<String,String> = emptyMap()):JSONObject {
        if(base.isBlank())throw IOException("Chọn máy tính trong mạng trước")
        require(path.startsWith('/') && !path.startsWith("//"))
        val c=URL(base+path).openConnection()as HttpURLConnection
        c.connectTimeout=4000;c.readTimeout=6000;c.useCaches=false;c.instanceFollowRedirects=false
        try{
            c.requestMethod=method;headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            if(value!=null){val bytes=value.toString().toByteArray(Charsets.UTF_8);c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setFixedLengthStreamingMode(bytes.size);c.outputStream.use{it.write(bytes)}}
            val code=c.responseCode;val stream=if(code in 200..299)c.inputStream else c.errorStream
            val result=stream?.bufferedReader(Charsets.UTF_8)?.use{reader->
                val text=StringBuilder();val chunk=CharArray(8192)
                while(true){val n=reader.read(chunk);if(n<0)break;if(text.length+n>2*1024*1024)throw IOException("Phản hồi Bridge quá lớn");text.append(chunk,0,n)}
                JSONObject(text.toString())
            }?:JSONObject()
            if(code !in 200..299)throw BridgeFailure(code,result.optString("error","PC trả HTTP $code"))
            return result
        }finally{c.disconnect()}
    }
    fun request(path:String,method:String="GET",value:JSONObject?=null):JSONObject {
        val owner=BridgeConnection.snapshot()
        return try{raw(owner.baseUrl,path,method,value,owner.headers)}catch(e:IOException){
            if(e is BridgeFailure || method!="GET" || !BridgeConnection.recover(owner))throw e
            val latest=BridgeConnection.requireSame(owner);raw(latest.baseUrl,path,method,value,latest.headers)
        }
    }
    fun exists(token:String):Boolean {
        val owner=BridgeConnection.snapshot()
        fun check(s:BridgeSession):Boolean {
            val c=URL("${s.baseUrl}/stream/$token").openConnection()as HttpURLConnection
            c.connectTimeout=4000;c.readTimeout=6000;c.requestMethod="HEAD";c.instanceFollowRedirects=false
            s.headers.forEach{(k,v)->c.setRequestProperty(k,v)}
            return try{c.responseCode in 200..299}finally{c.disconnect()}
        }
        return try{check(owner)}catch(e:IOException){if(BridgeConnection.recover(owner))check(BridgeConnection.requireSame(owner))else throw e}
    }
}
