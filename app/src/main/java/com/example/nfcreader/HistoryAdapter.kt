package com.example.nfcreader

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcreader.data.ScanRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<ScanRecord>,
    private val onItemClick: (ScanRecord) -> Unit,
    private val onItemLongClick: (ScanRecord) -> Unit,
    private val onEditLabelClick: (ScanRecord) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    private val dateFormat = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvItemIcon)
        val tvTitle: TextView = view.findViewById(R.id.tvItemTitle)
        val tvCardType: TextView = view.findViewById(R.id.tvItemCardType)
        val tvSubtitle: TextView = view.findViewById(R.id.tvItemSubtitle)
        val tvStatus: TextView = view.findViewById(R.id.tvItemStatus)
        val btnEdit: View = view.findViewById(R.id.btnItemEdit)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val record = items[position]
        holder.tvIcon.text = record.cardIcon
        holder.tvSubtitle.text = "UID: ${record.uid}  •  ${dateFormat.format(Date(record.timestampMillis))}"
        holder.tvStatus.text = if (record.success) "✅" else "⚠️"

        // Nếu người dùng đã đặt tên riêng: hiển thị tên đó làm tiêu đề chính,
        // và tên loại thẻ nhận diện tự động làm dòng phụ bên dưới.
        // Nếu chưa đặt tên: hiển thị tên loại thẻ nhận diện tự động làm tiêu đề chính như cũ.
        val label = record.label
        if (!label.isNullOrBlank()) {
            holder.tvTitle.text = label
            holder.tvCardType.text = "${record.cardIcon} ${record.cardTitle}"
            holder.tvCardType.visibility = View.VISIBLE
        } else {
            holder.tvTitle.text = record.cardTitle
            holder.tvCardType.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onItemClick(record) }
        holder.itemView.setOnLongClickListener {
            onItemLongClick(record)
            true
        }
        holder.btnEdit.setOnClickListener { onEditLabelClick(record) }
    }

    override fun getItemCount(): Int = items.size

    /** Cập nhật toàn bộ danh sách (gọi mỗi khi Flow từ Room phát dữ liệu mới). */
    fun submitList(newItems: List<ScanRecord>) {
        items = newItems
        notifyDataSetChanged()
    }
}
