package com.example.nfcreader

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
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
 *
 * Vì dữ liệu thẻ có thể nhạy cảm (UID, dump dữ liệu thẻ ngân hàng/CCCD...),
 * màn hình này YÊU CẦU xác thực bằng vân tay/khuôn mặt hoặc mã khóa màn hình
 * (PIN/mẫu hình/mật khẩu) của điện thoại trước khi tải và hiển thị dữ liệu.
 * Dữ liệu chỉ được truy vấn từ database SAU KHI xác thực thành công.
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

        // QUAN TRỌNG: chỉ gọi observeHistory() (tải dữ liệu) sau khi xác thực thành công.
        // Không tải trước, để tránh dữ liệu nhạy cảm bị lộ dù chỉ trong khoảnh khắc.
        requireAuthenticationThenLoad()
    }

    /**
     * Kiểm tra thiết bị có hỗ trợ xác thực (vân tay/khuôn mặt hoặc khóa màn hình) không,
     * rồi hiện hộp thoại xác thực chuẩn của hệ thống Android.
     *
     * LƯU Ý QUAN TRỌNG: Android không cho phép gộp chung BIOMETRIC_WEAK (đa số cảm biến vân
     * tay đời thường, "Class 2") với DEVICE_CREDENTIAL trong 1 lời gọi — chỉ BIOMETRIC_STRONG
     * mới gộp được với DEVICE_CREDENTIAL. Nếu chỉ xin quyền STRONG+CREDENTIAL, những máy có
     * vân tay Class 2 (rất phổ biến) sẽ bị hệ thống ÂM THẦM BỎ QUA bước vân tay và nhảy thẳng
     * sang xin mã khóa màn hình (PIN) — đây chính là điều bạn gặp phải. Để vân tay luôn được
     * ưu tiên hỏi trước, ta thử BIOMETRIC_WEAK riêng trước, có nút phụ để chuyển sang PIN.
     */
    private fun requireAuthenticationThenLoad() {
        val biometricManager = BiometricManager.from(this)
        val fingerprintOrFaceAvailable =
            biometricManager.canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
        val deviceCredentialAvailable =
            biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

        when {
            fingerprintOrFaceAvailable -> showBiometricPrompt(
                authenticators = BIOMETRIC_WEAK,
                allowCredentialFallback = deviceCredentialAvailable
            )
            deviceCredentialAvailable -> showBiometricPrompt(
                authenticators = DEVICE_CREDENTIAL,
                allowCredentialFallback = false
            )
            else -> showNoLockAvailableDialog()
        }
    }

    private fun showBiometricPrompt(authenticators: Int, allowCredentialFallback: Boolean) {
        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    // Xác thực thành công -> mới bắt đầu tải dữ liệu lịch sử
                    observeHistory()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // Người dùng bấm nút phụ ("Dùng PIN/mẫu hình thay thế") khi đang ở màn
                    // hình vân tay -> chuyển sang hỏi mã khóa màn hình thay vì đóng luôn.
                    if (errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON && allowCredentialFallback) {
                        showBiometricPrompt(authenticators = DEVICE_CREDENTIAL, allowCredentialFallback = false)
                        return
                    }
                    // Người dùng hủy hẳn hoặc xác thực lỗi -> đóng màn hình, không hiển thị gì cả
                    Toast.makeText(
                        this@HistoryActivity,
                        getString(R.string.lock_auth_error, errString),
                        Toast.LENGTH_SHORT
                    ).show()
                    finish()
                }

                override fun onAuthenticationFailed() {
                    // Vân tay/khuôn mặt không khớp - để hệ thống tự cho người dùng thử lại,
                    // không đóng màn hình ngay ở bước này.
                }
            }
        )

        val promptInfoBuilder = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(authenticators)

        // Android bắt buộc phải có nút phụ khi dùng riêng BIOMETRIC_WEAK (không gộp
        // DEVICE_CREDENTIAL). Ngược lại, khi authenticators đã gồm DEVICE_CREDENTIAL thì
        // KHÔNG được set nút phụ - hệ thống tự thêm nút "Hủy" (set thêm sẽ bị crash).
        if (authenticators == BIOMETRIC_WEAK) {
            val negativeText = if (allowCredentialFallback) {
                getString(R.string.lock_use_device_credential)
            } else {
                getString(R.string.dialog_no)
            }
            promptInfoBuilder.setNegativeButtonText(negativeText)
        }

        biometricPrompt.authenticate(promptInfoBuilder.build())
    }

    /**
     * Thiết bị chưa cài bất kỳ vân tay/khuôn mặt/PIN/mẫu hình/mật khẩu nào,
     * nên không có gì để xác thực. Cho người dùng lựa chọn: đi cài đặt khóa màn hình,
     * hoặc chấp nhận xem lịch sử mà không có lớp bảo vệ (rõ ràng, không âm thầm bỏ qua).
     */
    private fun showNoLockAvailableDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.lock_not_available_title)
            .setMessage(R.string.lock_not_available_message)
            .setCancelable(false)
            .setPositiveButton(R.string.lock_open_settings) { _, _ ->
                startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                finish()
            }
            .setNegativeButton(R.string.lock_continue_anyway) { _, _ ->
                observeHistory()
            }
            .show()
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
