package com.example.nfcreader.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {

    @Insert
    suspend fun insert(record: ScanRecord): Long

    /** Flow tự động phát dữ liệu mới mỗi khi bảng thay đổi - danh sách lịch sử tự cập nhật. */
    @Query("SELECT * FROM scan_records ORDER BY timestampMillis DESC")
    fun getAll(): Flow<List<ScanRecord>>

    @Delete
    suspend fun delete(record: ScanRecord)

    @Query("DELETE FROM scan_records")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM scan_records")
    suspend fun count(): Int

    /** Đặt/sửa/xóa nhãn tên riêng cho 1 lần quét (truyền null để xóa nhãn). */
    @Query("UPDATE scan_records SET label = :label WHERE id = :id")
    suspend fun updateLabel(id: Long, label: String?)
}
