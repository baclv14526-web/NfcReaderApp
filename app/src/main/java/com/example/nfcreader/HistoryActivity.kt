package com.example.nfcreader

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.nfcreader.data.AppDatabase
import com.example.nfcreader.data.ScanRecord
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Màn hình xem lại lịch sử các lần quét thẻ đã lưu trong SQLite (qua Room),
 * không cần quét lại thẻ để xem thông tin cũ.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var adapter: HistoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_history)

        db = AppDatabase.getInstance(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbarHistory)
        toolbar.setNavigationOnClickListener { finish() }

        tvEmpty = findViewById(R.id.tvEmpty)
        val rv = findViewById<RecyclerView>(R.id.rvHistory)
        rv.layoutManager = LinearLayoutManager(this)

        adapter = HistoryAdapter(
            items = emptyList(),
            onItemClick = { record -> showDetailDialog(record) },
            onItemLongClick = { record -> confirmDeleteOne(record) }
        )
        rv.adapter = adapter

        findViewById<MaterialButton>(R.id.btnClearHistory).setOnClickListener {
            confirmClearAll()
        }

        observeHistory()
    }

    private fun observeHistory() {
        lifecycleScope.launch {
            db.scanRecordDao().getAll().collect { list ->
                adapter.submitList(list)
                tvEmpty.visibility = if (list.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }

    /** Hiện toàn bộ nội dung chi tiết của 1 lần quét cũ, không cần quét lại thẻ. */
    private fun showDetailDialog(record: ScanRecord) {
        val dateFormat = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault())
        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            text = record.fullText
            textSize = 13f
            setPadding(32, 24, 32, 24)
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        scrollView.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("${record.cardIcon} ${record.cardTitle}  •  ${dateFormat.format(Date(record.timestampMillis))}")
            .setView(scrollView)
            .setPositiveButton(R.string.dialog_close, null)
            .show()
    }

    private fun confirmDeleteOne(record: ScanRecord) {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_delete_one_title)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                lifecycleScope.launch { db.scanRecordDao().delete(record) }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle(R.string.history_clear_confirm_title)
            .setMessage(R.string.history_clear_confirm_message)
            .setPositiveButton(R.string.dialog_yes) { _, _ ->
                lifecycleScope.launch { db.scanRecordDao().deleteAll() }
            }
            .setNegativeButton(R.string.dialog_no, null)
            .show()
    }
}
