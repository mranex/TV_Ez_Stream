package vn.aquastream.tv

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import java.util.concurrent.Executors

class ServerActivity:Activity() {
    private val io=Executors.newSingleThreadExecutor()
    private val servers=linkedMapOf<String,LanServer>()
    private lateinit var rows:LinearLayout
    private lateinit var message:TextView
    private var discovery:LanDiscovery?=null
    private var active=false
    private var busy=false
    private var network:ConnectivityManager.NetworkCallback?=null
    private var lastRefresh=0L
    private var popup:Dialog?=null
    override fun onCreate(state:Bundle?){
        super.onCreate(state);Ui.fullscreen(this)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=BrowserBackdrop();setPadding(Ui.dp(context,60),Ui.dp(context,24),Ui.dp(context,60),Ui.dp(context,20))}
        root.addView(BrowserStyle.text(this,"AquaStream",20f,true));root.addView(BrowserStyle.text(this,"Máy tính trong mạng",30f,true))
        message=BrowserStyle.text(this,"",15f);root.addView(message)
        val modes=LinearLayout(this)
        modes.addView(button("Direct LAN"){BridgeConnection.setMode(TransportMode.DIRECT_LAN);refresh()},LinearLayout.LayoutParams(0,Ui.dp(this,55),1f))
        modes.addView(button("ADB Reverse (Legacy)"){BridgeConnection.setMode(TransportMode.ADB_REVERSE_LEGACY);setResult(RESULT_OK);finish()},LinearLayout.LayoutParams(0,Ui.dp(this,55),1f))
        root.addView(modes)
        rows=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        root.addView(ScrollView(this).apply{addView(rows)},LinearLayout.LayoutParams(-1,0,1f))
        val actions=LinearLayout(this)
        actions.addView(button("Làm mới"){refresh()},LinearLayout.LayoutParams(0,Ui.dp(this,55),1f))
        actions.addView(button("Nhập địa chỉ thủ công"){manual()},LinearLayout.LayoutParams(0,Ui.dp(this,55),1f))
        root.addView(actions)
        if(intent.getBooleanExtra("settings",false))root.addView(button("Cổng Legacy: ${BridgeConnection.legacyPort}"){
            form("Cổng ADB Reverse trên TV",listOf("Cổng (mặc định 8765)" to true)){v->
                try{BridgeConnection.setLegacyPort(v[0].ifBlank{"8765"}.toInt());message.text="Đã lưu cổng Legacy. PC và TV phải dùng cùng cổng."}catch(_:Exception){message.text="Cổng phải từ 1 đến 65535"}
            }
        },LinearLayout.LayoutParams(-1,Ui.dp(this,55)))
        root.addView(BrowserStyle.text(this,"Trên PC: chọn Direct LAN → Ghép đôi thiết bị mới. HTTP LAN không mã hóa; chỉ dùng mạng đáng tin cậy.",13f))
        setContentView(root);modes.getChildAt(0).requestFocus()
    }
    private fun button(text:String,click:()->Unit)=BrowserStyle.nav(this,"computer",text){if(!busy)click()}
    private fun refresh(){
        if(!active)return
        discovery?.stop();servers.clear();render();lastRefresh=android.os.SystemClock.elapsedRealtime()
        message.text="Đang tìm PC…  •  ${BridgeConnection.snapshot().mode}  •  ${BridgeConnection.selectedName()}"
        discovery=LanDiscovery(this,{server->if(active && servers.size<32){servers[server.id]=server;render()}},{id->servers.remove(id);render()},{text->message.text=text}).also{it.start()}
    }
    private fun render(){
        val focused=currentFocus?.tag as?String
        rows.removeAllViews()
        servers.values.forEach{server->
            val b=button("${server.name}   •   ${if(BridgeConnection.isPaired(server.id))"Đã ghép đôi" else "Chưa ghép đôi"}"){choose(server)}.apply{tag=server.id}
            rows.addView(b,LinearLayout.LayoutParams(-1,Ui.dp(this,65)))
            if(focused==server.id)b.requestFocus()
        }
        if(servers.isEmpty())rows.addView(BrowserStyle.text(this,"Chưa tìm thấy PC. Bật Bridge cùng mạng hoặc nhập địa chỉ thủ công.",17f))
    }
    private fun work(action:()->Unit,success:()->Unit,failed:(Exception)->Unit={}){
        busy=true;message.text="Đang kết nối…"
        io.execute{try{action();runOnUiThread{if(active){busy=false;success()}}}catch(e:Exception){runOnUiThread{if(active){busy=false;message.text=e.message?:"Không kết nối được PC. Kiểm tra mode và firewall Private Network.";failed(e)}}}}
    }
    private fun choose(server:LanServer){
        if(BridgeConnection.isPaired(server.id))work({BridgeConnection.select(server)},{setResult(RESULT_OK);finish()},{e->if(e is BridgeFailure && e.status==401 || e.message?.contains("Credential")==true)pin(server)})
        else pin(server)
    }
    private fun form(title:String,fields:List<Pair<String,Boolean>>,submit:(List<String>)->Unit){
        val dialog=Dialog(this);popup=dialog
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;background=SmokySurface(this@ServerActivity,16f);setPadding(30,26,30,26)}
        body.addView(BrowserStyle.text(this,title,23f,true))
        val inputs=fields.map{(hint,numeric)->EditText(this).apply{this.hint=hint;setTextColor(Color.WHITE);setHintTextColor(Color.LTGRAY);textSize=20f;inputType=if(numeric)InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT;setSingleLine();if(hint=="PIN 6 chữ số")filters=arrayOf(InputFilter.LengthFilter(6));body.addView(this,LinearLayout.LayoutParams(-1,Ui.dp(context,54)))}}
        val connect=button("Kết nối"){val values=inputs.map{it.text.toString().trim()};dialog.dismiss();submit(values)}.apply{tag="form-connect"}
        inputs.forEachIndexed{index,input->
            input.imeOptions=(if(index==inputs.lastIndex)EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT) or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            input.setOnEditorActionListener{_,action,_->
                when(action){
                    EditorInfo.IME_ACTION_NEXT->{inputs.getOrNull(index+1)?.requestFocus();true}
                    EditorInfo.IME_ACTION_DONE->{
                        (getSystemService(Context.INPUT_METHOD_SERVICE)as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0)
                        connect.requestFocus();true
                    }
                    else->false
                }
            }
        }
        body.addView(connect,LinearLayout.LayoutParams(-1,Ui.dp(this,60)))
        body.addView(button("Hủy"){dialog.dismiss()},LinearLayout.LayoutParams(-1,Ui.dp(this,55)))
        dialog.setContentView(body);dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT));dialog.window?.setLayout(Ui.dp(this,540),-2)
        dialog.show();dialog.window?.setLayout(Ui.dp(this,540),-2);inputs.first().requestFocus()
    }
    private fun pin(server:LanServer)=form("Ghép đôi ${server.name}",listOf("PIN 6 chữ số" to true)){values->work({BridgeConnection.pair(server,values[0])},{setResult(RESULT_OK);finish()})}
    private fun manual()=form("Kết nối thủ công",listOf("IP hoặc hostname" to false,"Cổng (mặc định 8765)" to true)){values->
        var server:LanServer?=null
        work({server=BridgeConnection.identity(BridgeConnection.endpoint(values[0],values[1].ifBlank{"8765"}.toInt())).copy(manualHost=values[0])},{choose(server!!)})
    }
    override fun onResume(){super.onResume();active=true;refresh()
        val manager=getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager
        network=object:ConnectivityManager.NetworkCallback(){override fun onAvailable(n:Network){runOnUiThread{if(active && android.os.SystemClock.elapsedRealtime()-lastRefresh>2000)refresh()}}}
        manager.registerNetworkCallback(NetworkRequest.Builder().build(),network!!)
    }
    override fun onPause(){active=false;discovery?.stop();discovery=null;popup?.dismiss();network?.let{try{(getSystemService(Context.CONNECTIVITY_SERVICE)as ConnectivityManager).unregisterNetworkCallback(it)}catch(_:Exception){}};network=null;super.onPause()}
    override fun onDestroy(){io.shutdownNow();super.onDestroy()}
}
