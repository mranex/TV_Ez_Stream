package vn.aquastream.tv

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

class SettingsActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Ui.fullscreen(this)
        val root = LinearLayout(this).apply {
            setBackgroundColor(Ui.ground); gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(context, 48), Ui.dp(context, 28), Ui.dp(context, 48), Ui.dp(context, 28))
        }
        val summary = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, Ui.dp(context, 40), 0) }
        summary.addView(BrowserStyle.nav(this,"computer","Chế độ kết nối / Máy tính"){startActivity(android.content.Intent(this,ServerActivity::class.java).putExtra("settings",true))},LinearLayout.LayoutParams(-1,Ui.dp(this,62)))
        summary.addView(Ui.text(this, "Cài đặt", 32f, medium = true)); summary.addView(Ui.gap(this, height = 18))
        summary.addView(Ui.text(this, "Từng giây,\ntheo ý của bạn.", 24f)); summary.addView(Ui.gap(this, height = 20))
        summary.addView(Ui.text(this, "Tua tiến và lùi độc lập. Mọi lựa chọn được giữ lại cho lần xem sau.", 17f, Ui.muted))
        summary.addView(Ui.gap(this, height = 30)); summary.addView(Ui.button(this, "Quay lại") { finish() })
        root.addView(summary, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(SettingsPanel.content(this, { finish() }, {}), LinearLayout.LayoutParams(Ui.dp(this, 410), -1))
        setContentView(root)
    }
    override fun onStop() { SettingsPanel.dismissPopups(this); super.onStop() }
}
