package com.example.nfcreader

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.SoundPool
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB
import android.nfc.tech.NfcF
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.nfcreader.data.AppDatabase
import com.example.nfcreader.data.ScanRecord
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private lateinit var tvResult: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCardType: TextView
    private lateinit var rowCopyButtons: android.view.View
    private lateinit var db: AppDatabase

    /** UID (hex) của lần quét gần nhất, dùng cho nút "Copy UID". */
    private var lastUidHex: String? = null

    // --- Phản hồi rung + âm thanh khi đọc thẻ ---
    private lateinit var soundPool: SoundPool
    private var soundIdSuccess: Int = 0
    private var soundIdFail: Int = 0
    private var soundsLoaded = false
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        db = AppDatabase.getInstance(this)

        tvResult = findViewById(R.id.tvResult)
        tvStatus = findViewById(R.id.tvStatus)
        tvCardType = findViewById(R.id.tvCardType)
        rowCopyButtons = findViewById(R.id.rowCopyButtons)

        findViewById<android.view.View>(R.id.btnClear).setOnClickListener {
            tvResult.text = getString(R.string.hint_scan)
            tvStatus.text = getString(R.string.status_waiting)
            tvCardType.visibility = android.view.View.GONE
            rowCopyButtons.visibility = android.view.View.GONE
            lastUidHex = null
        }
        findViewById<android.view.View>(R.id.btnHistory).setOnClickListener {
            startActivity(Intent(this, HistoryActivity::class.java))
        }
        findViewById<android.view.View>(R.id.btnCopyUid).setOnClickListener {
            lastUidHex?.let { uid -> copyToClipboard("UID thẻ NFC", uid, R.string.copied_uid_toast) }
        }
        findViewById<android.view.View>(R.id.btnCopyAll).setOnClickListener {
            copyToClipboard("Kết quả đọc thẻ NFC", tvResult.text.toString(), R.string.copied_all_toast)
        }

        setupFeedback()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        if (nfcAdapter == null) {
            tvStatus.text = getString(R.string.nfc_not_supported)
            Toast.makeText(this, R.string.nfc_not_supported, Toast.LENGTH_LONG).show()
        }

        // Nếu app được mở trực tiếp từ 1 sự kiện NFC (ví dụ vừa cài xong rồi chạm thẻ)
        handleIntent(intent)
    }

    /** Khởi tạo SoundPool (phát 2 âm thanh ngắn) và Vibrator (rung phản hồi). */
    private fun setupFeedback() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(audioAttributes)
            .build()

        soundPool.setOnLoadCompleteListener { _, _, status ->
            if (status == 0) soundsLoaded = true
        }

        soundIdSuccess = soundPool.load(this, R.raw.beep_success, 1)
        soundIdFail = soundPool.load(this, R.raw.beep_fail, 1)

        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Phát rung + âm thanh phản hồi.
     * @param success true = đọc thẻ thành công (rung ngắn 1 lần + tiếng bíp cao),
     *                false = đọc thất bại (rung 2 lần liên tiếp + tiếng bíp trầm).
     */
    private fun playFeedback(success: Boolean) {
        // Âm thanh
        if (soundsLoaded) {
            val soundId = if (success) soundIdSuccess else soundIdFail
            soundPool.play(soundId, 1f, 1f, 1, 0, 1f)
        }

        // Rung
        val v = vibrator
        if (v != null && v.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (success) {
                    VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                } else {
                    // Rung 2 nhịp ngắn để phân biệt rõ với rung thành công
                    val pattern = longArrayOf(0, 80, 60, 80)
                    VibrationEffect.createWaveform(pattern, -1)
                }
                v.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                if (success) {
                    v.vibrate(60)
                } else {
                    v.vibrate(longArrayOf(0, 80, 60, 80), -1)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::soundPool.isInitialized) {
            soundPool.release()
        }
    }

    override fun onResume() {
        super.onResume()
        val adapter = nfcAdapter ?: return

        if (!adapter.isEnabled) {
            tvStatus.text = getString(R.string.nfc_disabled)
        } else {
            tvStatus.text = getString(R.string.status_waiting)
        }

        // Bật foreground dispatch để app luôn nhận sự kiện quét thẻ khi đang mở,
        // ưu tiên hơn các app khác cũng đăng ký NFC.
        val intent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, pendingIntentFlags)

        val ndefFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        val tagFilter = IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED)
        val techFilter = IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        val filters = arrayOf(ndefFilter, tagFilter, techFilter)

        val techLists = arrayOf(
            arrayOf(IsoDep::class.java.name, NfcA::class.java.name),
            arrayOf(MifareClassic::class.java.name),
            arrayOf(MifareUltralight::class.java.name),
            arrayOf(Ndef::class.java.name),
            arrayOf(NfcB::class.java.name),
            arrayOf(NfcF::class.java.name),
            arrayOf(NfcV::class.java.name)
        )

        adapter.enableForegroundDispatch(this, pendingIntent, filters, techLists)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED
        ) {
            return
        }

        val tag: Tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        } ?: run {
            playFeedback(success = false)
            return
        }

        tvStatus.text = getString(R.string.status_reading)
        val sb = StringBuilder()
        val time = SimpleDateFormat("HH:mm:ss dd/MM/yyyy", Locale.getDefault()).format(java.util.Date())
        sb.appendLine("=== KẾT QUẢ ĐỌC THẺ NFC ===")
        sb.appendLine("Thời gian: $time")
        sb.appendLine()

        // Nhận diện loại thẻ dựa trên ATQA/SAK/dung lượng - không cần kết nối tới thẻ
        // nên luôn thực hiện được, kể cả khi các bước đọc chi tiết bên dưới bị lỗi.
        val guess = CardTypeDetector.detect(tag)
        tvCardType.text = "${guess.icon} ${guess.title}\n${guess.detail}"
        tvCardType.visibility = android.view.View.VISIBLE
        sb.appendLine("--- Nhận diện loại thẻ (gợi ý, có thể không chính xác 100%) ---")
        sb.appendLine("${guess.icon} ${guess.title}")
        sb.appendLine(guess.detail)
        sb.appendLine()

        // Lưu UID để nút "Copy UID" dùng được ngay, và hiện hàng nút copy
        lastUidHex = bytesToHex(tag.id)
        rowCopyButtons.visibility = android.view.View.VISIBLE

        try {
            appendTagBasicInfo(tag, sb)
            appendNdefContent(tag, sb)
            appendMifareClassicContent(tag, sb)
            appendMifareUltralightContent(tag, sb)
            appendIsoDepInfo(tag, sb)

            tvResult.text = sb.toString()
            tvStatus.text = getString(R.string.status_waiting)
            playFeedback(success = true)
            saveScanToHistory(tag, guess, sb.toString(), success = true)
        } catch (e: Exception) {
            // Thường xảy ra khi thẻ bị nhấc ra quá sớm trong lúc đang đọc
            sb.appendLine()
            sb.appendLine("--- LỖI ---")
            sb.appendLine("Không đọc được thẻ: ${e.message}")
            sb.appendLine("(Hãy giữ thẻ áp sát mặt sau điện thoại và thử lại)")
            tvResult.text = sb.toString()
            tvStatus.text = getString(R.string.status_waiting)
            playFeedback(success = false)
            saveScanToHistory(tag, guess, sb.toString(), success = false)
        }
    }

    /** Lưu 1 bản ghi lịch sử vào SQLite (qua Room), chạy bất đồng bộ, không chặn UI. */
    private fun saveScanToHistory(tag: Tag, guess: CardGuess, fullText: String, success: Boolean) {
        val record = ScanRecord(
            timestampMillis = System.currentTimeMillis(),
            uid = bytesToHex(tag.id),
            cardIcon = guess.icon,
            cardTitle = guess.title,
            fullText = fullText,
            success = success
        )
        lifecycleScope.launch {
            try {
                db.scanRecordDao().insert(record)
            } catch (_: Exception) {
                // Lưu lịch sử thất bại không nên làm gián đoạn trải nghiệm đọc thẻ chính
            }
        }
    }

    // ----- Thông tin cơ bản: UID, ATQA/SAK, danh sách công nghệ -----
    private fun appendTagBasicInfo(tag: Tag, sb: StringBuilder) {
        sb.appendLine("--- Thông tin chung ---")
        sb.appendLine("UID (mã số thẻ): ${bytesToHex(tag.id)}")
        sb.appendLine("Công nghệ hỗ trợ:")
        for (tech in tag.techList) {
            sb.appendLine("  • ${tech.substringAfterLast('.')}")
        }

        val nfcA = NfcA.get(tag)
        if (nfcA != null) {
            sb.appendLine("ATQA: ${bytesToHex(nfcA.atqa)}")
            sb.appendLine("SAK: 0x${Integer.toHexString(nfcA.sak.toInt())}")
        }
        sb.appendLine()
    }

    // ----- Đọc dữ liệu NDEF: dùng cho nhiều thẻ thành viên, vé điện tử -----
    private fun appendNdefContent(tag: Tag, sb: StringBuilder) {
        val ndef = Ndef.get(tag) ?: return
        sb.appendLine("--- Dữ liệu NDEF ---")
        sb.appendLine("Loại thẻ NDEF: ${ndef.type}")
        sb.appendLine("Dung lượng: ${ndef.maxSize} bytes, chỉ đọc: ${!ndef.isWritable}")

        try {
            ndef.connect()
            val message: NdefMessage? = ndef.ndefMessage ?: ndef.cachedNdefMessage
            if (message == null) {
                sb.appendLine("(Không có dữ liệu NDEF trên thẻ)")
            } else {
                message.records.forEachIndexed { index, record ->
                    sb.appendLine("Bản ghi #${index + 1}:")
                    sb.appendLine("  TNF: ${record.tnf}")
                    sb.appendLine("  Type: ${String(record.type)}")
                    val payloadText = decodeNdefPayload(record.type, record.payload)
                    sb.appendLine("  Nội dung: $payloadText")
                }
            }
        } catch (e: Exception) {
            sb.appendLine("Không đọc được NDEF: ${e.message}")
        } finally {
            try { ndef.close() } catch (_: Exception) {}
        }
        sb.appendLine()
    }

    private fun decodeNdefPayload(type: ByteArray, payload: ByteArray): String {
        return try {
            val typeStr = String(type)
            when (typeStr) {
                "T" -> {
                    // Text record: byte đầu chứa cờ + độ dài mã ngôn ngữ
                    val statusByte = payload[0].toInt()
                    val langCodeLen = statusByte and 0x3F
                    String(payload, langCodeLen + 1, payload.size - langCodeLen - 1, Charsets.UTF_8)
                }
                "U" -> {
                    // URI record
                    String(payload, 1, payload.size - 1, Charsets.UTF_8)
                }
                else -> {
                    // Thử decode UTF-8, nếu không hiển thị dạng hex
                    val text = String(payload, Charsets.UTF_8)
                    if (text.any { it.code < 32 && it != '\n' && it != '\r' }) {
                        "(dữ liệu nhị phân) ${bytesToHex(payload)}"
                    } else {
                        text
                    }
                }
            }
        } catch (e: Exception) {
            "(không giải mã được) ${bytesToHex(payload)}"
        }
    }

    // ----- Mifare Classic: phổ biến trong vé xe buýt, thẻ gửi xe, thẻ thành viên -----
    private fun appendMifareClassicContent(tag: Tag, sb: StringBuilder) {
        val mifare = MifareClassic.get(tag) ?: return
        sb.appendLine("--- Mifare Classic ---")
        sb.appendLine("Loại: ${mifareTypeName(mifare.type)}")
        sb.appendLine("Số sector: ${mifare.sectorCount}, Kích thước: ${mifare.size} bytes")

        // Các khóa mặc định thường gặp - chỉ dùng để đọc thử, KHÔNG dùng cho mục đích xâm nhập trái phép
        val defaultKeys = listOf(
            MifareClassic.KEY_DEFAULT,
            MifareClassic.KEY_NFC_FORUM,
            MifareClassic.KEY_MIFARE_APPLICATION_DIRECTORY
        )

        try {
            mifare.connect()
            var readableSectors = 0
            val dump = StringBuilder()
            for (sectorIndex in 0 until minOf(mifare.sectorCount, 16)) { // giới hạn 16 sector đầu để tránh quá dài
                var authenticated = false
                for (key in defaultKeys) {
                    if (mifare.authenticateSectorWithKeyA(sectorIndex, key)) {
                        authenticated = true
                        break
                    }
                }
                if (!authenticated) continue

                readableSectors++
                val firstBlock = mifare.sectorToBlock(sectorIndex)
                val blockCount = mifare.getBlockCountInSector(sectorIndex)
                dump.appendLine("Sector $sectorIndex (đọc được):")
                for (b in 0 until blockCount) {
                    val blockData = mifare.readBlock(firstBlock + b)
                    dump.appendLine("  Block ${firstBlock + b}: ${bytesToHex(blockData)}")
                }
            }
            if (readableSectors == 0) {
                sb.appendLine("Không đọc được sector nào bằng khóa mặc định (thẻ dùng khóa riêng/đã khóa).")
            } else {
                sb.appendLine("Đọc được $readableSectors sector bằng khóa mặc định:")
                sb.append(dump)
            }
        } catch (e: Exception) {
            sb.appendLine("Lỗi đọc Mifare Classic: ${e.message}")
        } finally {
            try { mifare.close() } catch (_: Exception) {}
        }
        sb.appendLine()
    }

    private fun mifareTypeName(type: Int): String = when (type) {
        MifareClassic.TYPE_CLASSIC -> "Classic"
        MifareClassic.TYPE_PLUS -> "Plus"
        MifareClassic.TYPE_PRO -> "Pro"
        else -> "Không xác định"
    }

    // ----- Mifare Ultralight: thường dùng cho vé xe buýt/metro dùng 1 lần -----
    private fun appendMifareUltralightContent(tag: Tag, sb: StringBuilder) {
        val ultralight = MifareUltralight.get(tag) ?: return
        sb.appendLine("--- Mifare Ultralight ---")
        val typeName = if (ultralight.type == MifareUltralight.TYPE_ULTRALIGHT_C) "Ultralight C" else "Ultralight"
        sb.appendLine("Loại: $typeName")

        try {
            ultralight.connect()
            // Đọc các trang (page) dữ liệu, mỗi lần đọc trả về 4 trang (16 byte)
            val maxPage = 40 // đủ cho phần lớn thẻ vé xe Ultralight (~16-64 trang)
            var page = 0
            val dump = StringBuilder()
            while (page < maxPage) {
                try {
                    val data = ultralight.readPages(page)
                    for (i in 0 until 4) {
                        val start = i * 4
                        if (start + 4 <= data.size) {
                            val pageBytes = data.copyOfRange(start, start + 4)
                            dump.appendLine("  Page ${page + i}: ${bytesToHex(pageBytes)}")
                        }
                    }
                    page += 4
                } catch (e: Exception) {
                    break // đã đọc hết trang khả dụng
                }
            }
            sb.append(dump)
        } catch (e: Exception) {
            sb.appendLine("Lỗi đọc Mifare Ultralight: ${e.message}")
        } finally {
            try { ultralight.close() } catch (_: Exception) {}
        }
        sb.appendLine()
    }

    // ----- IsoDep: nền tảng của CCCD gắn chip (ICAO 9303) và nhiều thẻ giao thông ISO14443-4 -----
    private fun appendIsoDepInfo(tag: Tag, sb: StringBuilder) {
        val isoDep = IsoDep.get(tag) ?: return
        sb.appendLine("--- ISO-DEP (ISO 14443-4) ---")
        try {
            isoDep.connect()
            val hiLayerResponse = isoDep.hiLayerResponse
            val historicalBytes = isoDep.historicalBytes
            if (hiLayerResponse != null) {
                sb.appendLine("Hi-layer response: ${bytesToHex(hiLayerResponse)}")
            }
            if (historicalBytes != null) {
                sb.appendLine("Historical bytes: ${bytesToHex(historicalBytes)}")
            }

            // Gửi lệnh SELECT APDU tiêu chuẩn (chọn Master File) để kiểm tra phản hồi thô của chip.
            // LƯU Ý QUAN TRỌNG:
            // Để đọc đầy đủ dữ liệu cá nhân trên chip CCCD (họ tên, ngày sinh, ảnh...) theo chuẩn
            // ICAO 9303, cần thực hiện Basic Access Control (BAC) hoặc PACE bằng cách nhập số CCCD,
            // ngày sinh, ngày hết hạn (đọc từ vùng MRZ) để sinh khóa giải mã - phần này KHÔNG được
            // triển khai sẵn trong app demo (yêu cầu đúng quy định pháp luật và thư viện ICAO riêng).
            // Đoạn dưới chỉ minh họa việc giao tiếp thô ở tầng APDU với chip.
            val selectMasterFile = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04.toByte(), 0x0C.toByte(), 0x07.toByte(),
                0xA0.toByte(), 0x00, 0x00, 0x02, 0x47.toByte(), 0x10, 0x01
            )
            val response = isoDep.transceive(selectMasterFile)
            sb.appendLine("Phản hồi lệnh SELECT (thô, dạng hex): ${bytesToHex(response)}")
            sb.appendLine("(Ghi chú: để giải mã đầy đủ dữ liệu CCCD cần thêm bước xác thực BAC/PACE)")
        } catch (e: Exception) {
            sb.appendLine("Không giao tiếp được qua ISO-DEP: ${e.message}")
        } finally {
            try { isoDep.close() } catch (_: Exception) {}
        }
        sb.appendLine()
    }

    private fun bytesToHex(bytes: ByteArray?): String {
        if (bytes == null || bytes.isEmpty()) return "(rỗng)"
        val sb = StringBuilder()
        for (b in bytes) sb.append(String.format("%02X ", b))
        return sb.toString().trim()
    }

    /**
     * Copy văn bản vào clipboard hệ thống.
     * Từ Android 13 (API 33) trở lên, hệ thống tự hiện thông báo xác nhận copy,
     * nên chỉ tự hiện Toast trên các phiên bản cũ hơn để tránh hiện 2 lần.
     */
    private fun copyToClipboard(label: String, text: String, @androidx.annotation.StringRes toastRes: Int) {
        if (text.isBlank()) return
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboardManager.setPrimaryClip(ClipData.newPlainText(label, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, toastRes, Toast.LENGTH_SHORT).show()
        }
    }
}
