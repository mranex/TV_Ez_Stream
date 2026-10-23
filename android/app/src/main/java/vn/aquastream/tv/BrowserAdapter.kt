package vn.aquastream.tv

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView

data class BrowserRow(val name: String, val id: String? = null, val directory: Boolean = false,
    val action: Int = 0, val size: Long? = null, val drive: Boolean = false,
    val total: Long? = null, val free: Long? = null) {
    val key get() = id ?: "page:$action"
    val format get() = name.substringAfterLast('.', "VIDEO").uppercase(java.util.Locale.ROOT)
    val detail get() = when {
        action != 0 -> "Tiếp tục duyệt thư mục"
        drive -> listOfNotNull(total?.let { "${Ui.size(it)} tổng" }, free?.let { "${Ui.size(it)} trống" }).joinToString(" · ").ifEmpty { "Ổ đĩa trên máy tính" }
        directory -> "Thư mục"
        else -> listOf(format, Ui.size(size)).filter { it.isNotEmpty() }.joinToString(" · ")
    }
}

class BrowserAdapter(private val open: (BrowserRow) -> Unit, private val focused: (Int, BrowserRow) -> Unit) : RecyclerView.Adapter<BrowserAdapter.Holder>() {
    var rows: List<BrowserRow> = emptyList()
        private set
    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = rows[position].key.fold(1125899906842597L) { n, c -> n * 31 + c.code }
    override fun getItemCount() = rows.size
    override fun getItemViewType(position: Int) = if (rows[position].drive) 1 else 0
    fun replace(next: List<BrowserRow>) {
        val previous = rows
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = previous.size
            override fun getNewListSize() = next.size
            override fun areItemsTheSame(old: Int, new: Int) = previous[old].key == next[new].key
            override fun areContentsTheSame(old: Int, new: Int) = previous[old] == next[new]
        })
        rows = next; changes.dispatchUpdatesTo(this)
    }
    override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder = Holder(parent.context, type == 1)
    override fun onBindViewHolder(holder: Holder, position: Int) { holder.bind(rows[position]) }
    override fun onViewRecycled(holder: Holder) { holder.itemView.animate().cancel(); holder.itemView.scaleX = 1f; holder.itemView.scaleY = 1f }

    inner class Holder(c: Context, private val card: Boolean) : RecyclerView.ViewHolder(LinearLayout(c)) {
        private val body = itemView as LinearLayout
        private val icon = SymbolView(c, if (card) "drive" else "folder", 0xFFCFD6DD.toInt())
        private val title = BrowserStyle.text(c, "", if (card) 27f else 18f, card)
        private val meta = BrowserStyle.text(c, "", if (card) 15f else 13f).apply { setTextColor(BrowserStyle.muted); Ui.ellipsize(this) }
        private val arrow = SymbolView(c, "next", 0xFFCDD3DB.toInt())
        init {
            BrowserStyle.surface(body, if (card) 14f else 12f, if (card) 1.02f else 1.01f) { hasFocus ->
                val p = bindingAdapterPosition
                if (hasFocus && p != RecyclerView.NO_POSITION) focused(p, rows[p])
            }
            body.orientation = if (card) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            body.gravity = if (card) Gravity.TOP else Gravity.CENTER_VERTICAL
            body.setPadding(Ui.dp(c,if(card) 33 else 22),Ui.dp(c,if(card) 29 else 12),Ui.dp(c,if(card) 29 else 22),Ui.dp(c,if(card) 22 else 12))
            body.layoutParams = RecyclerView.LayoutParams(-1,Ui.dp(c,if(card) 185 else 72)).apply {
                bottomMargin = Ui.dp(c,if(card) 10 else 3); leftMargin = Ui.dp(c,3); rightMargin = Ui.dp(c,3)
            }
            Ui.ellipsize(title)
            if(card) {
                val top = LinearLayout(c).apply { gravity = Gravity.CENTER_VERTICAL }
                top.addView(icon,LinearLayout.LayoutParams(Ui.dp(c,40),Ui.dp(c,34)))
                top.addView(View(c),LinearLayout.LayoutParams(0,1,1f))
                top.addView(arrow,LinearLayout.LayoutParams(Ui.dp(c,18),Ui.dp(c,18)))
                body.addView(top); body.addView(Ui.gap(c,height=24)); body.addView(title)
                body.addView(Ui.gap(c,height=10)); body.addView(meta)
            } else {
                body.addView(icon,LinearLayout.LayoutParams(Ui.dp(c,29),Ui.dp(c,29))); body.addView(Ui.gap(c,width=17))
                val words = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
                words.addView(title); words.addView(Ui.gap(c,height=5)); words.addView(meta)
                body.addView(words,LinearLayout.LayoutParams(0,-2,1f)); body.addView(Ui.gap(c,width=16))
                body.addView(arrow,LinearLayout.LayoutParams(Ui.dp(c,16),Ui.dp(c,16)))
            }
            body.setOnClickListener { val p = bindingAdapterPosition; if(p != RecyclerView.NO_POSITION) open(rows[p]) }
        }
        fun bind(row: BrowserRow) {
            val driveLetter = row.drive && Regex("^[A-Za-z]:").containsMatchIn(row.name)
            title.text = if(driveLetter && row.name.length>2) android.text.SpannableString(row.name).apply {
                setSpan(android.text.style.RelativeSizeSpan(.62f),2,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else row.name
            meta.text = row.detail
            icon.symbol = when { row.drive -> "drive"; row.action == 2 -> "back"; row.action == 1 -> "next"; row.directory -> "folder"; else -> "video" }
            icon.invalidate(); body.tag = row.key; body.contentDescription = "${row.name}, ${row.detail}"
        }
    }
}
