# Đọc Thẻ NFC (Kotlin + Android Studio)

App Android đọc thông tin cơ bản từ thẻ NFC: vé xe buýt/metro, thẻ thành viên siêu thị,
thẻ NDEF, và (ở mức cơ bản qua ISO-DEP) chip trên căn cước công dân. Nội dung đọc được
hiển thị rõ ràng trên màn hình, có cuộn dọc (ScrollView) khi dài.

## App đọc được gì?

| Loại thẻ | Đọc được |
|---|---|
| Thẻ NDEF (thẻ thành viên, vé điện tử...) | Toàn bộ bản ghi NDEF (text, URI...) |
| Mifare Classic (vé xe, thẻ gửi xe...) | UID, dump các sector đọc được bằng khoá mặc định |
| Mifare Ultralight (vé xe 1 lần) | UID, dump các trang (page) dữ liệu |
| CCCD gắn chip / thẻ ISO-DEP (ISO 14443-4) | UID, ATQA/SAK, phản hồi thô (hex) ở tầng APDU |

**Phản hồi rung + âm thanh:** mỗi lần quét thẻ, app tự phát 1 tiếng bíp + rung nhẹ để báo
**thành công** (rung 1 lần ngắn + bíp cao), hoặc **thất bại** (rung 2 nhịp + bíp trầm) khi
thẻ bị nhấc ra quá sớm hoặc không đọc được. 2 file âm thanh (`res/raw/beep_success.wav`,
`beep_fail.wav`) được tổng hợp bằng sóng sin thuần túy, không dùng file bản quyền nào.

**Nhận diện loại thẻ tự động:** dựa vào ATQA/SAK và dung lượng bộ nhớ (không cần đọc dữ
liệu riêng tư trên thẻ), app hiển thị 1 badge gợi ý loại thẻ + ứng dụng thường gặp ngay
sau khi quét, ví dụ: "🚌 Mifare Classic 1K — thường dùng cho vé xe buýt, thẻ gửi xe, thẻ
thành viên siêu thị". Logic nhận diện nằm trong `CardTypeDetector.kt`, dễ mở rộng thêm
SAK/ATQA mới. Đây là **suy đoán dựa trên các giá trị SAK phổ biến trên thị trường**, không
đảm bảo chính xác 100% vì một số nhà sản xuất dùng SAK tùy biến.

**Lịch sử quét thẻ (lưu SQLite qua Room):** mỗi lần quét (kể cả khi lỗi) đều được lưu tự
động vào cơ sở dữ liệu SQLite cục bộ trên máy — không cần mạng, không gửi đi đâu cả. Nhấn
nút **"🔒 Xem lịch sử"** ở màn hình chính để mở danh sách các lần quét trước đó, bấm vào 1
dòng để xem lại toàn bộ nội dung chi tiết mà **không cần quét lại thẻ**. Có thể xóa từng
dòng (nhấn giữ) hoặc xóa toàn bộ lịch sử. Code liên quan nằm trong package
`com/example/nfcreader/data/` (`ScanRecord`, `ScanRecordDao`, `AppDatabase`) và
`HistoryActivity.kt` / `HistoryAdapter.kt`.

**Khóa màn hình lịch sử bằng vân tay/PIN:** vì dữ liệu thẻ có thể nhạy cảm, mỗi lần mở
màn hình Lịch sử, app bắt buộc xác thực bằng vân tay, khuôn mặt, hoặc mã khóa màn hình
(PIN/mẫu hình/mật khẩu) đã cài trên điện thoại (dùng `androidx.biometric`). Dữ liệu **chỉ
được truy vấn từ database sau khi xác thực thành công** — không tải trước rồi mới che đi,
để tránh lộ dữ liệu dù chỉ trong khoảnh khắc. Nếu máy chưa cài đặt bất kỳ hình thức khóa
màn hình nào, app sẽ báo và cho lựa chọn đi tới Cài đặt hoặc tiếp tục xem mà không khóa
(vì không có gì để xác thực).

> **Lưu ý kỹ thuật:** Android không cho phép gộp `BIOMETRIC_WEAK` (loại cảm biến vân tay
> phổ biến nhất, "Class 2" — có ở rất nhiều điện thoại tầm trung) chung với
> `DEVICE_CREDENTIAL` trong 1 lần gọi; chỉ `BIOMETRIC_STRONG` (Class 3, thường chỉ có ở
> điện thoại cao cấp) mới gộp được. Nếu code chỉ xin `STRONG + DEVICE_CREDENTIAL`, những
> máy có vân tay Class 2 sẽ bị hệ thống **âm thầm bỏ qua bước vân tay** và nhảy thẳng sang
> hỏi PIN (hoặc không hỏi gì nếu không có PIN) — đây là lỗi rất dễ gặp và khó nhận ra. App
> đã được sửa để **thử vân tay/khuôn mặt (`BIOMETRIC_WEAK`) riêng trước**, có nút phụ
> "Dùng PIN/mẫu hình thay thế" để chuyển sang khóa màn hình nếu người dùng muốn.

**Copy nhanh vào clipboard:** ngay sau khi quét (đồng thời với badge nhận diện loại thẻ),
2 nút nhỏ **"📋 Copy UID"** và **"📋 Copy toàn bộ"** hiện ra dưới thanh trạng thái để copy
nhanh mà không cần chọn/bôi đen chữ. Màn hình Lịch sử cũng có nút "Copy toàn bộ" trong hộp
thoại xem chi tiết, để copy lại 1 lần quét cũ. Trên Android 13 trở lên, hệ thống tự hiện
thông báo xác nhận copy nên app không hiện thêm Toast trùng lặp.

**Lưu ý quan trọng về CCCD gắn chip:** để đọc đầy đủ dữ liệu cá nhân (họ tên, ngày sinh,
ảnh...) trên chip CCCD theo chuẩn ICAO 9303, cần thực hiện thêm bước xác thực **BAC/PACE**
bằng cách nhập số CCCD + ngày sinh + ngày hết hạn (lấy từ vùng MRZ) để sinh khoá giải mã.
App này đọc được UID và phản hồi thô ở tầng giao tiếp (APDU), nhưng **chưa** giải mã dữ liệu
cá nhân trên chip — phần đó cần thư viện ICAO riêng và phải tuân thủ đúng quy định pháp luật
về việc truy cập dữ liệu công dân.

## Cấu trúc project

```
NfcReaderApp/
├── app/
│   ├── build.gradle.kts          # cấu hình build + signing config đọc từ Secrets/keystore.properties
│   └── src/main/
│       ├── AndroidManifest.xml   # khai báo quyền NFC + intent-filter bắt sự kiện quét thẻ
│       ├── java/.../MainActivity.kt  # toàn bộ logic đọc NFC
│       └── res/...                # layout, string, icon
├── .github/workflows/build-apk.yml  # GitHub Actions: build + ký + tạo Release APK
└── keystore.properties.example
```

## Mở project bằng Android Studio

1. Cài **Android Studio** bản mới nhất (Giraffe trở lên).
2. `File > Open` → chọn thư mục `NfcReaderApp`.
3. Android Studio sẽ tự tạo file Gradle Wrapper còn thiếu (`gradlew`) và đồng bộ project —
   chờ vài phút cho lần đầu tải Gradle/SDK.
4. Cắm điện thoại Android thật qua USB (bật **Developer options > USB debugging**) —
   NFC không mô phỏng được trên máy ảo, phải test trên thiết bị thật có NFC.
5. Nhấn **Run ▶** để cài app lên điện thoại, sau đó chạm thẻ NFC vào mặt sau máy.

## Build APK tự động bằng GitHub Actions (không cần build tay)

Workflow `.github/workflows/build-apk.yml` sẽ tự động:

1. Cài JDK 17, Android SDK, Gradle (có cache để các lần build sau nhanh hơn nhiều).
2. Build bản `assembleRelease`.
3. **Ký APK** bằng keystore (xem 2 cách bên dưới).
4. Đăng APK lên **Artifacts** của lần chạy (tab *Actions* → chọn lần chạy → mục *Artifacts*).
5. Nếu push lên nhánh `main`/`master`: tự tạo **Release** kèm sẵn link tải file `.apk`
   để cài trực tiếp lên điện thoại, không cần vào Actions tìm Artifact.

### Cách 1 — Không cấu hình gì (chạy được ngay khi push public repo)

Nếu bạn **chưa** tạo Secrets, workflow tự sinh một keystore mới cho mỗi lần build, APK vẫn
có chữ ký và cài đặt bình thường. Nhược điểm: **chữ ký đổi giữa các lần build**, nên nếu bạn
đã cài bản cũ thì phải gỡ app cũ ra rồi mới cài bản mới đè lên được (Android chặn cài đè app
khác chữ ký).

Workflow cũng tự đính kèm file keystore vừa tạo vào Artifacts (tên
`generated-keystore-KHONG-CHIA-SE`) — nếu muốn giữ chữ ký cố định cho các lần build sau,
tải file đó về rồi làm theo Cách 2 bên dưới.

### Cách 2 — Cấu hình Secrets để chữ ký cố định (khuyên dùng)

Chữ ký cố định giúp cài đè bản cập nhật lên bản cũ mà không cần gỡ app, và là bắt buộc nếu
sau này muốn đăng app lên cửa hàng ứng dụng.

**Bước 1 — Tạo file keystore** (chạy 1 lần trên máy có cài JDK):

```bash
keytool -genkeypair -v -keystore release.jks -alias nfcreader \
  -keyalg RSA -keysize 2048 -validity 10000
```

Nhập mật khẩu và thông tin theo yêu cầu — **lưu lại cẩn thận**, mất là không ký lại được
bản cập nhật cho đúng chữ ký cũ.

**Bước 2 — Chuyển file thành base64:**

```bash
# macOS / Linux
base64 -i release.jks | tr -d '\n' > release_base64.txt

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("release.jks")) | Out-File release_base64.txt
```

**Bước 3 — Vào GitHub repo → Settings → Secrets and variables → Actions → New repository
secret**, tạo 4 secrets:

| Tên secret | Giá trị |
|---|---|
| `KEYSTORE_BASE64` | nội dung file `release_base64.txt` |
| `KEYSTORE_PASSWORD` | mật khẩu keystore đã đặt ở Bước 1 |
| `KEY_ALIAS` | `nfcreader` (hoặc alias bạn đã đặt) |
| `KEY_PASSWORD` | mật khẩu key đã đặt ở Bước 1 |

**Bước 4 — Push code lên `main`** (hoặc bấm *Run workflow* thủ công ở tab Actions). Từ lần
này trở đi mọi bản build đều dùng cùng 1 keystore.

### Vì sao build không bị chậm lại ở các lần sau?

`gradle/actions/setup-gradle@v4` tự động cache Gradle wrapper, dependencies đã tải, và
build cache giữa các lần chạy Actions — bạn không cần cấu hình thêm gì để có tốc độ build
nhanh hơn từ lần thứ 2 trở đi.

## Cài APK lên điện thoại

1. Vào tab **Releases** của repo (hoặc mục Artifacts trong 1 lần chạy Actions), tải file
   `.apk` về điện thoại.
2. Mở file `.apk` vừa tải → nếu máy hỏi, bật quyền **"Cài đặt ứng dụng không xác định"**
   cho ứng dụng bạn dùng để tải file (Chrome, Files...).
3. Nhấn **Cài đặt**.
4. Mở app, đưa thẻ NFC lại gần mặt sau điện thoại (thường ở giữa lưng máy) để đọc.

## Giấy phép & lưu ý sử dụng

App chỉ đọc dữ liệu công khai ở tầng giao tiếp thẻ (UID, NDEF, dump bằng khoá mặc định).
Việc đọc/giải mã dữ liệu cá nhân trên CCCD gắn chip cần tuân thủ quy định pháp luật hiện
hành về bảo vệ dữ liệu cá nhân — chỉ nên thực hiện khi có sự đồng ý của chủ thẻ và đúng
mục đích hợp pháp.
