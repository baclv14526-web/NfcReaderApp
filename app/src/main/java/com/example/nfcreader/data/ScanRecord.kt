package com.example.nfcreader.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Một bản ghi lịch sử quét thẻ, lưu vào SQLite qua Room.
 */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** Thời điểm quét, tính bằng epoch millis (dùng System.currentTimeMillis()). */
    val timestampMillis: Long,

    /** UID (mã số) của thẻ, dạng hex, dùng để hiển thị & tìm kiếm nhanh. */
    val uid: String,

    /** Icon emoji của loại thẻ đã nhận diện, vd "🚌". */
    val cardIcon: String,

    /** Tên loại thẻ đã nhận diện, vd "Mifare Classic 1K". */
    val cardTitle: String,

    /** Toàn bộ nội dung chi tiết đã đọc được (giống như hiển thị ở màn hình chính). */
    val fullText: String,

    /** true nếu lần quét đó đọc thành công, false nếu có lỗi xảy ra. */
    val success: Boolean
)
