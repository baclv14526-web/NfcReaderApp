package com.example.nfcreader

import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.MifareClassic
import android.nfc.tech.MifareUltralight
import android.nfc.tech.Ndef
import android.nfc.tech.NfcA
import android.nfc.tech.NfcB

/**
 * Kết quả nhận diện loại thẻ: icon (emoji, không cần asset ảnh riêng),
 * tên gợi ý ngắn gọn, và mô tả/ứng dụng thường gặp.
 */
data class CardGuess(
    val icon: String,
    val title: String,
    val detail: String
)

/**
 * Nhận diện loại thẻ NFC dựa trên các đặc điểm phần cứng có thể đọc được
 * MÀ KHÔNG cần giải mã dữ liệu riêng tư trên thẻ: ATQA, SAK, dung lượng
 * Mifare, và tập công nghệ (tech list). Đây chỉ là PHỎNG ĐOÁN dựa trên
 * các giá trị SAK phổ biến trên thị trường — không đảm bảo chính xác 100%
 * vì nhiều nhà sản xuất có thể dùng SAK tùy biến hoặc thẻ giả lập.
 */
object CardTypeDetector {

    fun detect(tag: Tag): CardGuess {
        val nfcA = NfcA.get(tag)
        val nfcB = NfcB.get(tag)
        val mifareClassic = MifareClassic.get(tag)
        val mifareUltralight = MifareUltralight.get(tag)
        val isoDep = IsoDep.get(tag)
        val ndef = Ndef.get(tag)

        val sak = nfcA?.sak?.toInt()?.and(0xFF) ?: -1
        val uidLen = tag.id?.size ?: 0

        // 1) Mifare Classic - nhận diện qua dung lượng bộ nhớ
        if (mifareClassic != null) {
            return when (mifareClassic.size) {
                320 -> CardGuess(
                    "🅼", "Mifare Mini (~320 byte)",
                    "Dung lượng nhỏ, thường dùng cho thẻ ra/vào bãi đỗ xe hoặc khóa cửa đơn giản."
                )
                1024 -> CardGuess(
                    "🚌", "Mifare Classic 1K",
                    "Loại rất phổ biến tại Việt Nam cho vé xe buýt, thẻ gửi xe, thẻ thành viên siêu thị, thẻ ra vào chung cư/công ty."
                )
                4096 -> CardGuess(
                    "🏢", "Mifare Classic 4K",
                    "Dung lượng lớn hơn bản 1K, thường dùng cho thẻ tích hợp nhiều ứng dụng: chấm công, gửi xe, thanh toán căng tin trong cùng 1 thẻ."
                )
                else -> CardGuess(
                    "💳", "Mifare Classic (dung lượng khác)",
                    "Thuộc họ Mifare Classic nhưng dung lượng không thuộc 2 loại phổ biến 1K/4K."
                )
            }
        }

        // 2) Mifare Ultralight - vé giấy/nhựa mỏng dùng 1 hoặc vài lần
        if (mifareUltralight != null) {
            return if (mifareUltralight.type == MifareUltralight.TYPE_ULTRALIGHT_C) {
                CardGuess(
                    "🎫", "Mifare Ultralight C",
                    "Thường dùng làm vé xe buýt/metro loại dùng nhiều lần (có nạp thêm lượt), hỗ trợ mã hoá 3DES cơ bản."
                )
            } else {
                CardGuess(
                    "🎟️", "Mifare Ultralight / NTAG21x",
                    "Thường dùng làm vé xe buýt/tàu điện dùng 1 lần, thẻ sự kiện, hoặc tem NFC dán trên sản phẩm/áp phích."
                )
            }
        }

        // 3) Thẻ chip thông minh tương thích ISO-DEP (ISO 14443-4)
        //    Bao gồm nhiều khả năng: CCCD gắn chip, thẻ ngân hàng contactless, DESFire, JCOP...
        if (isoDep != null) {
            if (nfcA != null) {
                return when (sak) {
                    0x20 -> CardGuess(
                        "🪪", "Thẻ chip ISO 14443-4 (SAK 0x20)",
                        "Nhóm này gồm CCCD gắn chip, thẻ DESFire, JCOP, và một số thẻ ngân hàng contactless. " +
                            "Cần xem thêm phản hồi APDU thô bên dưới để đoán chính xác hơn ứng dụng trên chip."
                    )
                    0x28 -> CardGuess(
                        "🪪", "JCOP / SmartMX (SAK 0x28)",
                        "Chip nền tảng Java Card — thường thấy ở CCCD, thẻ ngân hàng, hoặc thẻ định danh doanh nghiệp."
                    )
                    0x38 -> CardGuess(
                        "💳", "Mifare Plus – SL1 (SAK 0x38)",
                        "Phiên bản bảo mật cao hơn Mifare Classic, dùng trong một số hệ thống vé giao thông thông minh (thẻ MRT/BRT thế hệ mới)."
                    )
                    else -> CardGuess(
                        "💳", "Thẻ chip ISO 14443-4 (SAK 0x${Integer.toHexString(sak)})",
                        "Thẻ có bộ xử lý (smart card), có thể là thẻ định danh điện tử, thẻ ngân hàng, hoặc thẻ giao thông cao cấp."
                    )
                }
            }
            if (nfcB != null) {
                return CardGuess(
                    "💳", "Thẻ chuẩn NFC-B (Type B)",
                    "Nhiều thẻ ngân hàng contactless (EMV) và một số hộ chiếu điện tử dùng chuẩn Type B thay vì Type A."
                )
            }
            return CardGuess(
                "💳", "Thẻ chip ISO 14443-4",
                "Thẻ có bộ xử lý (smart card) nhưng không xác định được công nghệ tầng dưới cụ thể."
            )
        }

        // 4) Chỉ có NDEF, không thuộc các nhóm trên
        if (ndef != null) {
            return CardGuess(
                "🏷️", "Thẻ NFC Forum (NDEF)",
                "Thường dùng làm thẻ thành viên, tag thông tin, hoặc nhãn NFC dán trên sản phẩm/áp phích quảng cáo."
            )
        }

        // 5) Không khớp nhóm nào đã biết
        val uidNote = when (uidLen) {
            4 -> " UID 4 byte (thường gặp ở thẻ đời cũ/thẻ giá rẻ)."
            7 -> " UID 7 byte (chuẩn phổ biến hiện nay theo ISO 14443-3)."
            10 -> " UID 10 byte (loại hiếm, dùng cho một số thẻ chuyên dụng)."
            else -> ""
        }
        return CardGuess(
            "❓", "Không xác định được loại thẻ cụ thể",
            "Không khớp với các loại thẻ phổ biến trong danh sách nhận diện của app.$uidNote"
        )
    }
}
