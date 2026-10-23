package vn.aquastream.tv

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private data class Frame(val id: String?, val name: String, var offset: Int = 0,
        var selected: Int = 0, var first: Int = 0, var top: Int = 0, var focusKey: String? = null)
    private val stack = ArrayList<Frame>()
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: MockupLayout
    internal lateinit var list: RecyclerView
        private set
    internal lateinit var driveNav: View
        private set
    internal lateinit var settingsNav: View
        private set
    internal lateinit var refresh: View
        private set
    private lateinit var back: View
    private lateinit var status: TextView
    private lateinit var connection: TextView
    private lateinit var heading: TextView
    private lateinit var breadcrumb: TextView
    private lateinit var selectedName: TextView
    private lateinit var selectedMeta: TextView
    private lateinit var blank: LinearLayout
    private lateinit var blankTitle: TextView
    private lateinit var blankDescription: TextView
    private lateinit var retry: View
    internal lateinit var adapter: BrowserAdapter
        private set
    private var generation = 0
    private var active = false
    private var connectionKey=""
    private var loading = false
    internal var transitioning = true
        private set
    private var showingDrives = true
    private var restoreListener: ViewTreeObserver.OnPreDrawListener? = null
    private val pressedConfirm = HashSet<Int>()
    private val suppressedKeys = HashSet<Int>()
    private var backHeld = false
    private var resumeChrome: View? = null
    private val current get() = stack.last()
    internal val locationDepth get() = stack.size
    internal val locationId get() = current.id
    internal val focusedKey get() = currentFocus?.let { list.findContainingViewHolder(it)?.itemView?.tag as? String }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (current.id == null && !loading && !transitioning) load(quiet = true)
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state); Ui.fullscreen(this)
        connectionKey=state?.getString("connection_key")?:BridgeConnection.snapshot().let{"${it.mode}:${it.serverId}"}
        state?.getString("stack")?.let { org.json.JSONArray(it) }?.let { saved ->
            for (n in 0 until saved.length()) {
                val f = saved.getJSONObject(n)
                stack.add(Frame(if(f.isNull("id")) null else f.getString("id"), f.getString("name"),
                    f.optInt("offset"), f.optInt("selected"), f.optInt("first"), f.optInt("top"), if(f.isNull("key")) null else f.getString("key")))
            }
        }
        if(stack.isEmpty()) stack.add(Frame(null,"Ổ đĩa"))
        // One nonvisual focus owner exists BEFORE any sidebar/content can be attached.
        root = MockupLayout(this).apply {
            background = BrowserBackdrop(); isFocusable = true; isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        root.place(BrowserIcon(this,"logo"),36f,40f,24f,24f)
        root.place(BrowserStyle.text(this,"AquaStream",20f,true),70f,42f,125f,27f,20f)
        root.place(BrowserStyle.text(this,"Nguồn phát",14f).apply { setTextColor(BrowserStyle.muted) },36f,111f,152f,22f,14f)
        driveNav = BrowserStyle.nav(this,"drive","Ổ đĩa") {
            if(!transitioning) { remember(); if(stack.size>1) stack.subList(1,stack.size).clear(); load() }
        }.apply { isSelected = true }
        settingsNav = BrowserStyle.nav(this,"settings","Cài đặt") {
            if(!transitioning) { remember(); resumeChrome = settingsNav; startActivity(Intent(this,SettingsActivity::class.java)) }
        }
        root.place(driveNav,29f,132f,165f,65f)
        root.place(settingsNav,29f,196f,165f,61f)
        root.place(BrowserIcon(this,"computer"),36f,450f,18f,18f)
        connection = BrowserStyle.text(this,"Đang kết nối PC",13f,true)
        root.place(connection,67f,451f,132f,22f,13f)
        root.place(BrowserStyle.text(this,"Video trên máy tính.\nThưởng thức trên TV.",12.5f).apply { setTextColor(BrowserStyle.muted) },36f,478f,160f,39f,12.5f)

        back = BrowserStyle.iconButton(this,"back","Thư mục cha") { if(!transitioning) goBack() }
        refresh = BrowserStyle.iconButton(this,"refresh","Làm mới") { refreshBrowser() }
        root.place(back,231f,27f,52f,52f)
        breadcrumb = BrowserStyle.text(this,"Máy tính",15f).apply { setTextColor(BrowserStyle.muted); gravity = Gravity.CENTER_VERTICAL; Ui.ellipsize(this) }
        root.place(breadcrumb,292f,35f,570f,35f,15f)
        root.place(refresh,873f,26f,56f,56f)
        heading = BrowserStyle.text(this,"Ổ đĩa",34f,true).apply { Ui.ellipsize(this) }
        root.place(heading,237f,109f,680f,44f,34f)
        status = BrowserStyle.text(this,"Chọn ổ đĩa để bắt đầu xem.",16f).apply { setTextColor(BrowserStyle.muted); Ui.ellipsize(this) }
        root.place(status,237f,159f,680f,26f,16f)
        adapter = BrowserAdapter(::open) { position,row ->
            if(!transitioning) { current.selected = position; current.focusKey = row.key; showSelection(row) }
        }
        list = RecyclerView(this).apply {
            id = View.generateViewId(); itemAnimator = null; setHasFixedSize(true)
            isFocusable = false; preserveFocusAfterLayout = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            clipChildren = true; clipToPadding = true
            setPadding(Ui.dp(context,2),Ui.dp(context,2),Ui.dp(context,2),Ui.dp(context,4))
            layoutManager = GridLayoutManager(this@MainActivity,2)
            adapter = this@MainActivity.adapter
        }
        // Fixed two-column grid: one drive stays the same size; 3+ drives wrap without shifting columns.
        root.place(list,227f,191f,699f,251f)
        blank = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.GONE }
        blankTitle = BrowserStyle.text(this,"",23f,true).apply { gravity = Gravity.CENTER }
        blankDescription = BrowserStyle.text(this,"",15f).apply { gravity = Gravity.CENTER; maxLines = 3; setTextColor(BrowserStyle.muted) }
        blank.addView(blankTitle); blank.addView(Ui.gap(this,height=12)); blank.addView(blankDescription); blank.addView(Ui.gap(this,height=18))
        retry = BrowserStyle.nav(this,"refresh","Thử lại") { refreshBrowser() }
        blank.addView(retry,LinearLayout.LayoutParams(Ui.dp(this,180),Ui.dp(this,57)))
        root.place(blank,237f,197f,683f,238f)
        selectedName = BrowserStyle.text(this,"",16f).apply { maxLines = 2; Ui.ellipsize(this,2) }
        selectedMeta = BrowserStyle.text(this,"",12.5f).apply { setTextColor(BrowserStyle.muted); Ui.ellipsize(this) }
        val footer=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        footer.addView(selectedName,LinearLayout.LayoutParams(-1,-2))
        footer.addView(Ui.gap(this,height=8))
        footer.addView(selectedMeta,LinearLayout.LayoutParams(-1,-2))
        root.place(footer,237f,458f,683f,66f)
        // Explicit rail/content links, while Android handles spatial movement inside the attached list.
        driveNav.nextFocusDownId = settingsNav.id; settingsNav.nextFocusUpId = driveNav.id
        back.nextFocusRightId = refresh.id; refresh.nextFocusLeftId = back.id
        setContentView(root); root.requestFocus()
    }

    private fun showSelection(row: BrowserRow) {
        selectedName.text = row.name
        selectedMeta.text = if(row.action != 0) "OK sang trang  ·  Back về thư mục cha"
            else "${row.detail}  ·  ${if(row.directory) "OK mở" else "OK phát"}  ·  Back quay lại"
    }
    private fun open(row: BrowserRow) {
        if(transitioning) return
        remember()
        when(row.action) {
            1,2 -> {
                current.offset = (current.offset + if(row.action==1) 200 else -200).coerceAtLeast(0)
                current.selected=0; current.first=0; current.top=0; current.focusKey=null; load()
            }
            else -> if(row.directory) { stack.add(Frame(row.id,row.name)); load() }
                else { beginTransition(); startActivityForResult(Intent(this,PlayerActivity::class.java).putExtra("file_id",row.id).putExtra("name",row.name).putExtra("parent_id",current.id).putExtra("offset",current.offset),42) }
        }
    }
    private fun remember() {
        if(!::list.isInitialized || transitioning) return
        val manager = list.layoutManager as LinearLayoutManager
        current.first = manager.findFirstVisibleItemPosition().coerceAtLeast(0)
        current.top = manager.findViewByPosition(current.first)?.top ?: 0
    }
    private fun cancelRestore() {
        restoreListener?.let { if(root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(it) }
        restoreListener = null
    }
    private fun beginTransition() {
        cancelRestore(); transitioning = true
        // Block default focus search BEFORE hiding, disabling or detaching the focused view.
        root.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        root.requestFocus(); list.stopScroll()
    }
    private fun placeholder(title: String, description: String, canRetry: Boolean) {
        blank.visibility=View.VISIBLE; list.visibility=View.INVISIBLE
        blankTitle.text=title; blankDescription.text=description; retry.visibility=if(canRetry) View.VISIBLE else View.GONE
        selectedName.text=""; selectedMeta.text=""
    }
    /** Release input only after a matching, attached holder has completed layout and accepted focus. */
    private fun restoreFocus(request: Int, selected: Int?, chrome: View? = null) {
        var scrolled = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if(!active || request != generation) { cancelRestore(); return true }
                if(list.isComputingLayout || list.hasPendingAdapterUpdates()) return true
                val target = chrome ?: if(selected == null) retry else list.findViewHolderForAdapterPosition(selected)?.itemView
                if(target == null || !target.isLaidOut || !target.isAttachedToWindow) {
                    if(selected != null && !scrolled) { list.scrollToPosition(selected); scrolled=true }
                    return true
                }
                root.descendantFocusability=ViewGroup.FOCUS_AFTER_DESCENDANTS
                if(target.requestFocus()) {
                    transitioning=false; loading=false; cancelRestore()
                    if(selected != null) showSelection(adapter.rows[selected])
                } else { root.descendantFocusability=ViewGroup.FOCUS_BLOCK_DESCENDANTS; root.requestFocus() }
                return true
            }
        }
        restoreListener=listener; root.viewTreeObserver.addOnPreDrawListener(listener); root.invalidate()
    }
    internal fun refreshBrowser() { if(!transitioning && !loading) { remember(); load() } }
    private fun load(quiet: Boolean = false, chrome: View? = null) {
        val frame=current; val request=++generation; val id=frame.id; val offset=frame.offset
        loading=true
        if(!quiet) {
            beginTransition()
            heading.text=frame.name; breadcrumb.text=if(stack.size==1) "Máy tính" else "Máy tính  /  "+stack.drop(1).joinToString("  /  ") { it.name }
            back.isEnabled=stack.size>1; back.alpha=if(back.isEnabled) 1f else .55f
            status.text="Đang đọc thư mục…"; placeholder("Đang mở…","Nội dung được đọc trực tiếp từ máy tính.",false)
        }
        worker.execute {
            try {
                val path=if(id==null) "/drives" else "/browse?id=${URLEncoder.encode(id,"UTF-8")}&offset=$offset"
                val json=BridgeApi.get(path); val next=ArrayList<BrowserRow>()
                if(id==null) {
                    val drives=json.getJSONArray("drives")
                    for(n in 0 until drives.length()) {
                        val d=drives.getJSONObject(n)
                        next.add(BrowserRow(d.getString("name"),d.getString("id"),directory=true,drive=true,
                            total=if(d.has("total_bytes")&&!d.isNull("total_bytes")) d.getLong("total_bytes") else null,
                            free=if(d.has("free_bytes")&&!d.isNull("free_bytes")) d.getLong("free_bytes") else null))
                    }
                } else {
                    if(offset>0) next.add(BrowserRow("Trang trước",action=2))
                    val entries=json.getJSONArray("entries")
                    for(n in 0 until entries.length()) {
                        val e=entries.getJSONObject(n)
                        next.add(BrowserRow(e.getString("name"),e.getString("id"),e.getBoolean("directory"),size=if(e.isNull("size")) null else e.optLong("size")))
                    }
                    if(!json.isNull("next_offset")) next.add(BrowserRow("Trang tiếp",action=1))
                }
                runOnUiThread {
                    if(!active || request!=generation) return@runOnUiThread
                    connection.text="Đã kết nối PC"
                    if(quiet && next==adapter.rows) { loading=false; return@runOnUiThread }
                    // A background poll preserves the focus the user has NOW, not where it was at request time.
                    val keepChrome=if(quiet && !list.hasFocus()) currentFocus?.takeUnless { it===root } else chrome
                    if(quiet) { remember(); beginTransition() }
                    heading.text=frame.name
                    status.text=if(id==null) "Chọn ổ đĩa để bắt đầu xem."
                        else "${if(offset>0 || !json.isNull("next_offset")) "Trang ${offset/200+1}  ·  " else ""}${next.count{it.directory}} thư mục  ·  ${next.count{!it.directory && it.action==0}} video"
                    if(showingDrives != (id==null)) {
                        list.layoutManager=if(id==null) GridLayoutManager(this,2) else LinearLayoutManager(this)
                        showingDrives=id==null
                    }
                    adapter.replace(next)
                    if(next.isEmpty()) {
                        placeholder(if(id==null) "Chưa có ổ dữ liệu" else "Chưa có video",if(id==null) "Cắm ổ USB vào máy tính. Danh sách tự cập nhật." else "Thư mục trống hoặc không có định dạng video được liệt kê.",true)
                        restoreFocus(request,null,keepChrome?.takeIf { it.isShown && it.isEnabled })
                    } else {
                        list.visibility=View.VISIBLE; blank.visibility=View.GONE
                        val selected=next.indexOfFirst{it.key==frame.focusKey}.takeIf{it>=0} ?: frame.selected.coerceIn(0,next.lastIndex)
                        frame.selected=selected; frame.focusKey=next[selected].key
                        (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(frame.first.coerceAtMost(next.lastIndex),frame.top)
                        showSelection(next[selected]); restoreFocus(request,selected,keepChrome?.takeIf{it.isShown && it.isEnabled})
                    }
                }
            } catch(e: Exception) {
                runOnUiThread {
                    if(!active || request!=generation) return@runOnUiThread
                    if(quiet) beginTransition()
                    connection.text="Mất kết nối PC"; status.text="Không thể cập nhật. Làm mới để thử lại."
                    placeholder("Không đọc được nguồn phát",e.message ?: "Kiểm tra máy tính và ổ USB, rồi thử lại.",true)
                    restoreFocus(request,null)
                }
            }
        }
    }
    private fun goBack() {
        if(transitioning) return
        if(stack.size>1) { stack.removeAt(stack.lastIndex); load() } else finish()
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code=event.keyCode; val down=event.action==KeyEvent.ACTION_DOWN; val up=event.action==KeyEvent.ACTION_UP
        if(code==KeyEvent.KEYCODE_DPAD_CENTER || code==KeyEvent.KEYCODE_ENTER || code==KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if(up) { pressedConfirm.remove(code); return true }
            if(down) {
                val fresh=pressedConfirm.add(code)
                if(!transitioning && fresh && event.repeatCount==0) currentFocus?.performClick()
                return true
            }
        }
        if(code in suppressedKeys) { if(up) suppressedKeys.remove(code); return true }
        if(code==KeyEvent.KEYCODE_BACK) {
            if(up) { backHeld=false; return true }
            if(down) { if(!transitioning && !backHeld && event.repeatCount==0) goBack(); backHeld=true; return true }
        }
        if(transitioning && code in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN)) {
            if(down) suppressedKeys.add(code); return true
        }
        // Re-enter content at its remembered identity, rather than whichever row happens to be nearest.
        if(down && code==KeyEvent.KEYCODE_DPAD_RIGHT && event.repeatCount==0 && (currentFocus===driveNav || currentFocus===settingsNav)) {
            if(adapter.rows.isNotEmpty() && list.visibility==View.VISIBLE) {
                beginTransition(); restoreFocus(generation,current.selected.coerceIn(0,adapter.rows.lastIndex))
            } else refresh.requestFocus()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    @Deprecated("Legacy Activity result for TV")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if(requestCode==64 && resultCode!=RESULT_OK && BridgeConnection.needsSetup){finish();return}
        if(requestCode==42 && resultCode==RESULT_OK && data!=null) {
            current.focusKey=data.getStringExtra("file_id") ?: current.focusKey
            val offset=data.getIntExtra("offset",current.offset)
            if(offset!=current.offset) { current.offset=offset; current.first=0; current.top=0; current.selected=0 }
        }
    }
    override fun onResume() {
        super.onResume()
        if(BridgeConnection.needsSetup){startActivityForResult(Intent(this,ServerActivity::class.java),64);return}
        active=true
        val key=BridgeConnection.snapshot().let{"${it.mode}:${it.serverId}"}
        if(connectionKey!=key){stack.clear();stack.add(Frame(null,"Ổ đĩa"));connectionKey=key;resumeChrome=null}
        val chrome=resumeChrome; resumeChrome=null
        load(chrome=chrome); handler.postDelayed(poll,5000)
    }
    override fun onPause() {
        remember(); active=false; generation++; loading=false; cancelRestore(); handler.removeCallbacks(poll)
        beginTransition(); pressedConfirm.clear(); suppressedKeys.clear(); backHeld=false
        super.onPause()
    }
    override fun onDestroy() { cancelRestore(); worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) {
        out.putString("connection_key",connectionKey)
        remember(); out.putString("stack",org.json.JSONArray().apply { stack.forEach { f ->
            put(JSONObject().put("id",f.id ?: JSONObject.NULL).put("name",f.name).put("offset",f.offset).put("selected",f.selected)
                .put("first",f.first).put("top",f.top).put("key",f.focusKey ?: JSONObject.NULL))
        } }.toString()); super.onSaveInstanceState(out)
    }
    @Deprecated("TV Back") override fun onBackPressed() { goBack() }
}
