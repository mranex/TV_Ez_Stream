package vn.aquastream.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.*
import android.widget.*

/** Every activation owns its key-up, including when the dialog closes on key-down. */
class PlaybackDialog(private val activity: Activity) : Dialog(activity) {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code=event.keyCode
        if(code==KeyEvent.KEYCODE_MEDIA_STOP) {
            if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) { (activity as? PlayerActivity)?.intelligence?.userStopped(); cancel() }
            return true
        }
        if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_BACK) {
            if(event.action==KeyEvent.ACTION_UP) { (activity as? PlayerActivity)?.dialogKeyReleased(code); return true }
            if(event.action==KeyEvent.ACTION_DOWN) {
                if(event.repeatCount==0) {
                    (activity as? PlayerActivity)?.blockDialogRelease(code)
                    if(code==KeyEvent.KEYCODE_BACK) cancel() else currentFocus?.performClick()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

object PlaybackDialogs {
    data class Handle(val dialog: Dialog, val body: TextView, val first: View)
    fun button(a: Activity, label: String, action: () -> Unit) = Ui.button(a,label,action).apply {
        background=SmokySurface(a,10f)
    }
    fun panel(a: Activity, title: String): LinearLayout = LinearLayout(a).apply {
        orientation=LinearLayout.VERTICAL; background=SmokySurface(a,18f)
        setPadding(Ui.dp(a,28),Ui.dp(a,26),Ui.dp(a,28),Ui.dp(a,26))
        addView(Ui.text(a,title,24f,medium=true)); addView(Ui.gap(a,height=16))
    }
    fun show(a: Activity, root: View, width: Int): PlaybackDialog = PlaybackDialog(a).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root)
        (a as? PlayerActivity)?.beginPlaybackDialog()
        setOnDismissListener { (a as? PlayerActivity)?.endPlaybackDialog(); SettingsPanel.untrack(a,this) }
        SettingsPanel.track(a,this)
        show()
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(Ui.dp(a,width),-2)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.45f)
            decorView.systemUiVisibility=a.window.decorView.systemUiVisibility
        }
    }
    fun choice(a: Activity, title: String, message: String, firstLabel: String, secondLabel: String,
               first: () -> Unit, second: () -> Unit, cancel: () -> Unit): Handle {
        val root=panel(a,title)
        val body=Ui.text(a,message,17f,Ui.muted).apply { maxLines=5; setLineSpacing(Ui.dp(a,5).toFloat(),1f) }
        root.addView(body); root.addView(Ui.gap(a,height=22))
        val row=LinearLayout(a); lateinit var dialog: PlaybackDialog
        val primary=button(a,firstLabel) { dialog.dismiss(); first() }
        val secondary=button(a,secondLabel) { dialog.dismiss(); second() }
        row.addView(primary,LinearLayout.LayoutParams(0,Ui.dp(a,54),1f).apply { rightMargin=Ui.dp(a,8) })
        row.addView(secondary,LinearLayout.LayoutParams(0,Ui.dp(a,54),1f))
        root.addView(row); dialog=show(a,root,510)
        dialog.setOnCancelListener { cancel() }; primary.requestFocus()
        return Handle(dialog,body,primary)
    }
    fun speedNumber(a: Activity, settings: PlayerSettings, changed: () -> Unit) {
        val root=panel(a,"Nhập tốc độ phát")
        var digits=String.format(java.util.Locale.ROOT,"%.2f",settings.speedHundredths/100.0); var replace=true
        val display=Ui.text(a,digits+"×",30f).apply { gravity=Gravity.CENTER; contentDescription="Tốc độ nhập" }
        val error=Ui.text(a,"Nhập từ 0.25 đến 4.00, tối đa hai số lẻ.",14f,Ui.muted)
        root.addView(display,LinearLayout.LayoutParams(-1,Ui.dp(a,48))); root.addView(error)
        lateinit var dialog: PlaybackDialog
        var first: View?=null
        for(keys in listOf(listOf("1","2","3"),listOf("4","5","6"),listOf("7","8","9"),listOf(".","0","Xóa"))) {
            val row=LinearLayout(a)
            for(key in keys) {
                val b=button(a,key) {
                    if(replace) { digits=""; replace=false }
                    if(key=="Xóa") digits=digits.dropLast(1)
                    else if(digits.length<5 && (key!="." || !digits.contains('.'))) digits+=key
                    display.text=digits.ifEmpty{"0"}+"×"
                }
                if(first==null) first=b
                row.addView(b,LinearLayout.LayoutParams(0,Ui.dp(a,46),1f).apply { setMargins(3,3,3,3) })
            }
            root.addView(row)
        }
        root.addView(button(a,"Lưu tốc độ") {
            val speed=PlaybackMath.parseSpeed(digits)
            if(speed==null) error.text="Giá trị không hợp lệ: 0.25–4.00, tối đa hai số lẻ."
            else { settings.speedHundredths=speed; changed(); dialog.dismiss() }
        },LinearLayout.LayoutParams(-1,Ui.dp(a,48)))
        dialog=show(a,root,420); first?.requestFocus()
    }
}

object SpeedControls {
    fun content(a: Activity, settings: PlayerSettings, changed: () -> Unit): View {
        val root=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL }
        root.addView(Ui.text(a,"Tốc độ phát",18f,medium=true)); root.addView(Ui.gap(a,height=10))
        val row=LinearLayout(a)
        val minus=PlaybackDialogs.button(a,"−") {}
        val value=PlaybackDialogs.button(a,"") {}
        val plus=PlaybackDialogs.button(a,"+") {}
        val track=SeekBar(a).apply {
            max=375; progressTintList=android.content.res.ColorStateList.valueOf(0xFFDDE2E9.toInt())
            thumbTintList=progressTintList; contentDescription="Thanh tốc độ, trái phải mỗi bước 0.05"
        }
        fun update() { value.text=PlaybackMath.speedLabel(settings.speedHundredths); value.contentDescription="Nhập tốc độ ${value.text}"; track.progress=settings.speedHundredths-25 }
        fun save(delta: Int) { settings.speedHundredths+=delta; changed(); update() }
        minus.contentDescription="Giảm tốc độ"; plus.contentDescription="Tăng tốc độ"
        minus.setOnClickListener { save(-5) }; plus.setOnClickListener { save(5) }
        value.setOnClickListener { PlaybackDialogs.speedNumber(a,settings) { changed(); update() } }
        fun stepKeys(v: View, code: Int, event: KeyEvent): Boolean {
            val direction=when(code) { KeyEvent.KEYCODE_DPAD_LEFT -> -5; KeyEvent.KEYCODE_DPAD_RIGHT -> 5; else -> 0 }
            if(direction==0) return false
            if(event.action==KeyEvent.ACTION_DOWN) save(direction)
            return true
        }
        track.setOnKeyListener { v,code,event -> stepKeys(v,code,event) }
        for(b in listOf(minus,plus)) b.setOnKeyListener { _,code,event ->
            if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER) {
                if(event.action==KeyEvent.ACTION_DOWN) save(if(b===minus) -5 else 5)
                true
            } else false
        }
        row.addView(minus,LinearLayout.LayoutParams(Ui.dp(a,48),Ui.dp(a,48)))
        row.addView(value,LinearLayout.LayoutParams(0,Ui.dp(a,48),1f))
        row.addView(plus,LinearLayout.LayoutParams(Ui.dp(a,48),Ui.dp(a,48)))
        root.addView(row); root.addView(track,LinearLayout.LayoutParams(-1,Ui.dp(a,32)))
        root.addView(PlaybackDialogs.button(a,"Đặt lại 1.00×") { settings.speedHundredths=100; changed(); update() },LinearLayout.LayoutParams(-1,Ui.dp(a,44)))
        root.addView(Ui.text(a,"0.25×–4.00× · OK vào số để nhập tùy ý.",13f,Ui.muted))
        update(); return root
    }
}
