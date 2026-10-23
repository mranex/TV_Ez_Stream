package vn.aquastream.tv

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.*

/** A Dialog keeps PlayerActivity resumed, so opening settings never recreates the decoder. */
object SettingsPanel {
    private fun neutralFocus(a: Activity, color: Int) = android.graphics.drawable.StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), Ui.rounded(a, 0xFF343B44.toInt(), stroke = 0xFFE1E7EF.toInt()))
        addState(intArrayOf(), Ui.rounded(a, color))
    }
    private val popups = java.util.WeakHashMap<Activity, MutableSet<Dialog>>()
    internal fun visiblePopupRoots(a: Activity): List<View> = popups[a]?.filter { it.isShowing }?.mapNotNull { it.window?.decorView } ?: emptyList()
    fun track(a: Activity, dialog: Dialog) { popups.getOrPut(a) { LinkedHashSet() }.add(dialog) }
    fun untrack(a: Activity, dialog: Dialog) { popups[a]?.remove(dialog) }
    fun dismissPopups(a: Activity) { popups.remove(a)?.toList()?.forEach { it.dismiss() } }
    fun show(a: Activity, changed: () -> Unit, dismissed: () -> Unit): Dialog {
        val dialog = Dialog(a)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(content(a, { dialog.dismiss() }, changed))
        dialog.setOnKeyListener { _, _, event -> (a as? PlayerActivity)?.consumeDialogRelease(event) == true }
        dialog.setOnDismissListener { dismissed() }
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.END); setLayout(Ui.dp(a, 370), -1)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); setDimAmount(.3f)
            decorView.systemUiVisibility = a.window.decorView.systemUiVisibility
            decorView.alpha = 0f; decorView.translationX = Ui.dp(a, 18).toFloat()
            decorView.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }
        return dialog
    }

    fun content(a: Activity, close: () -> Unit, changed: () -> Unit): View {
        val settings = PlayerSettings(a)
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Ui.surface)
            setPadding(Ui.dp(a, 22), Ui.dp(a, 24), Ui.dp(a, 22), Ui.dp(a, 20))
        }
        val heading = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(Ui.text(a, "Phát video", 25f, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
        heading.addView(PlayerControl(a, R.drawable.ic_player_close, "Đóng cài đặt", click = close), LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42)))
        root.addView(heading); root.addView(Ui.gap(a, height = 8))
        root.addView(Ui.text(a, "Thay đổi áp dụng ngay và tự lưu.", 15f, Ui.muted))
        root.addView(Ui.gap(a, height = 24))
        val scroll = ScrollView(a).apply { isFillViewport = false; clipToPadding = false }
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val refreshers = ArrayList<() -> Unit>()
        fun updated() { refreshers.forEach { it() }; changed() }
        fun row(title: String, description: String, value: () -> String, click: () -> Unit) {
            val button = LinearLayout(a).apply {
                id = View.generateViewId(); isFocusable = true; isClickable = true; orientation = LinearLayout.VERTICAL
                background = neutralFocus(a, Ui.ground)
                setPadding(Ui.dp(a, 16), Ui.dp(a, 14), Ui.dp(a, 16), Ui.dp(a, 14))
                setOnClickListener { click() }
            }
            val line = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL }
            line.addView(Ui.text(a, title, 18f, medium = true), LinearLayout.LayoutParams(0, -2, 1f))
            val settingValue = Ui.text(a, value(), 18f, 0xFFE1E7EF.toInt())
            line.addView(settingValue)
            button.addView(line); button.addView(Ui.gap(a, height = 7))
            button.addView(Ui.text(a, description, 14f, Ui.muted))
            refreshers.add { settingValue.text = value(); button.contentDescription = "$title, ${value()}. $description" }
            rows.addView(button, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(a, 10) })
        }
        row("Tua tiến", "Một lần bấm RIGHT", { "${settings.step(true)}s" }) {
            choose(a, "Bước tua tiến", settings.step(true), intArrayOf(1, 2, 3, 5, 10), Int.MAX_VALUE) { settings.saveStep(true, it); updated() }
        }
        row("Tua lùi", "Một lần bấm LEFT", { "${settings.step(false)}s" }) {
            choose(a, "Bước tua lùi", settings.step(false), intArrayOf(1, 2, 3, 5, 10), Int.MAX_VALUE) { settings.saveStep(false, it); updated() }
        }
        row("Phản hồi tua", "Hiện số giây và vị trí mục tiêu", { if (settings.feedback) "Bật" else "Tắt" }) {
            settings.feedback = !settings.feedback; updated()
        }
        row("Tự ẩn điều khiển", "Khi đang phát và không thao tác", { "${settings.timeoutSeconds}s" }) {
            choose(a, "Tự ẩn điều khiển", settings.timeoutSeconds, intArrayOf(3, 4, 5, 8, 10), 120) { settings.timeoutSeconds = it; updated() }
        }
        rows.addView(Ui.gap(a, height = 12))
        rows.addView(SpeedControls.content(a, settings) { updated() })
        rows.addView(Ui.gap(a, height = 18))
        row("Tự động phát tập tiếp theo", "Chỉ tiếp tục khi tên tập cùng bộ được nhận diện", { if(settings.autoNext) "Bật" else "Tắt" }) {
            settings.autoNext = !settings.autoNext; updated()
        }

        rows.addView(Ui.text(a, "Giữ nút để tua liên tiếp. Điểm dừng hình ảnh còn phụ thuộc video và khả năng giải mã của TV.", 14f, Ui.muted))
        scroll.addView(rows); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        rows.post { rows.getChildAt(0)?.requestFocus() }
        refreshers.forEach { it() }
        return root
    }

    private fun popup(a: Activity, content: View, width: Int): Dialog = Dialog(a).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(content); show()
        popups.getOrPut(a) { LinkedHashSet() }.add(this)
        setOnDismissListener { popups[a]?.let { dialogs -> dialogs.remove(this); if (dialogs.isEmpty()) popups.remove(a) } }
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(Ui.dp(a, width), -2)
            setDimAmount(.5f); decorView.systemUiVisibility = a.window.decorView.systemUiVisibility
            decorView.alpha = 0f; decorView.translationX = Ui.dp(a, 18).toFloat()
            decorView.animate().alpha(1f).translationX(0f).setDuration(180).start()
        }
    }
    private fun choose(a: Activity, title: String, current: Int, presets: IntArray, max: Int, save: (Int) -> Unit) {
        val root = Ui.column(a).apply { background = Ui.rounded(a, Ui.surface, 20) }
        root.addView(Ui.text(a, title, 24f, medium = true)); root.addView(Ui.gap(a, height = 10))
        root.addView(Ui.text(a, "Hiện tại: $current giây", 16f, Ui.muted)); root.addView(Ui.gap(a, height = 20))
        val group = LinearLayout(a)
        lateinit var dialog: Dialog
        presets.forEach { seconds ->
            group.addView(Ui.button(a, "${seconds}s") { save(seconds); dialog.dismiss() }.apply { background = neutralFocus(a, Ui.ground) }, LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f).apply { rightMargin = Ui.dp(a, 5) })
        }
        root.addView(group); root.addView(Ui.gap(a, height = 12))
        root.addView(Ui.button(a, "Nhập số giây…") { dialog.dismiss(); number(a, title, current, max, save) }.apply { background = neutralFocus(a, Ui.ground) }, LinearLayout.LayoutParams(-1, Ui.dp(a, 48)))
        dialog = popup(a, root, 500)
        group.getChildAt(presets.indexOf(current).coerceAtLeast(0)).requestFocus()
    }
    /** D-pad numeric pad avoids requiring a physical keyboard or a TV IME. */
    private fun number(a: Activity, title: String, current: Int, max: Int, save: (Int) -> Unit) {
        val root = Ui.column(a).apply { background = Ui.rounded(a, Ui.surface, 20) }
        root.addView(Ui.text(a, title, 22f, medium = true)); root.addView(Ui.gap(a, height = 12))
        var digits = current.toString(); var replace = true
        val display = Ui.text(a, digits, 30f, 0xFFE1E7EF.toInt()).apply { gravity = Gravity.CENTER; contentDescription = "Số giây" }
        root.addView(display, LinearLayout.LayoutParams(-1, Ui.dp(a, 44)))
        val error = Ui.text(a, "Nhập số nguyên dương, đơn vị giây.", 14f, Ui.muted)
        root.addView(error); root.addView(Ui.gap(a, height = 12))
        lateinit var dialog: Dialog
        fun enter(key: String) {
            if (key == "Xóa") { digits = if (replace) "" else digits.dropLast(1); replace = false }
            else { if (replace) digits = ""; replace = false; if (digits.length < 10) digits += key }
            display.text = digits.ifEmpty { "0" }; error.setTextColor(Ui.muted)
        }
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("Xóa", "0", "Lưu"))
        for (row in keys) {
            val line = LinearLayout(a)
            for (key in row) line.addView(Ui.button(a, key) {
                if (key != "Lưu") enter(key)
                else {
                    val value = digits.toIntOrNull()
                    if (value == null || value < 1 || value > max) { error.text = "Nhập từ 1 đến $max giây."; error.setTextColor(Ui.error) }
                    else { save(value); dialog.dismiss() }
                }
            }.apply { background = neutralFocus(a, Ui.ground) }, LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f).apply { setMargins(Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3), Ui.dp(a, 3)) })
            root.addView(line)
        }
        dialog = popup(a, root, 400)
        (root.getChildAt(5) as? LinearLayout)?.getChildAt(0)?.requestFocus()
    }
}
