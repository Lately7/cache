# Lưu tin Messenger

App Android lưu tin Messenger đã nhận và đã gửi của chính bạn vào máy. Dữ liệu chỉ nằm trên
điện thoại: app không có quyền Internet và tắt sao lưu đám mây. Muốn lấy dữ liệu ra thì dùng
nút **Xuất JSON**.

## Cách thu tin

| Nguồn | Lấy được gì | Giới hạn |
|---|---|---|
| Thông báo | Tin nhận (kể cả khi không mở Messenger) | Phải bật thông báo Messenger; thu hồi hoặc tắt thông báo thì mất |
| Trợ năng | Tin gửi và nhận đang hiện trên màn hình | Chỉ khi mở đoạn chat; tin chưa cuộn tới thì không có |

Ảnh, video và tin thoại không được lưu, chỉ lưu chữ. Tin thu từ màn hình mang giờ lúc app đọc
được, không phải giờ gửi gốc. Bong bóng trên cùng màn hình chỉ được lưu khi cuộn lên thấy tin
phía trước nó.

## Cài trên iQOO Z10 Turbo Pro

1. Chép `app-debug.apk` sang điện thoại và mở để cài (cho phép cài từ nguồn này).
2. Mở app, thử bấm **Bật đọc thông báo** và **Bật Trợ năng**. Nếu mục bị mờ hoặc báo
   "Cài đặt bị hạn chế": vào Cài đặt > Ứng dụng > Lưu tin Messenger > menu ⋮ >
   **Cho phép cài đặt bị hạn chế**, rồi bật lại.
3. Để app không bị tắt nền: trong mục pin của app, chọn cho phép chạy nền không giới hạn, và
   khoá app trong màn hình đa nhiệm. Tên mục có thể khác tuỳ bản OriginOS.
4. Mở một đoạn chat, gửi thử một tin, rồi quay lại app xem có xuất hiện đúng chiều không.

## Build

Cần JDK 17 và Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

Bản debug ký bằng khoá của máy build; build từ máy khác rồi cài đè sẽ phải gỡ app trước, và gỡ
app là mất dữ liệu đã lưu, nên hãy xuất JSON trước.
