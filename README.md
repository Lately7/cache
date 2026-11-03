# Lưu tin Messenger

App Android lưu tin Messenger đã nhận và đã gửi của chính bạn vào máy. Dữ liệu chỉ nằm trên
điện thoại: app không có quyền Internet và tắt sao lưu đám mây. Muốn lấy dữ liệu ra thì dùng
nút **Xuất JSON**. Giao diện luôn ở nền tối, theo màu của Messenger bản tối.

## Cách thu tin

| Nguồn | Lấy được gì | Giới hạn |
|---|---|---|
| Thông báo | Tin nhận (kể cả khi không mở Messenger) | Phải bật thông báo Messenger; thu hồi hoặc tắt thông báo thì mất |
| Trợ năng | Tin gửi và nhận đang hiện trên màn hình | Chỉ khi mở đoạn chat; tin chưa cuộn tới thì không có |

Tin link và tin chỉ có một emoji không có nhãn của Messenger, nên người gửi của chúng được lấy theo
bong bóng gần nhất; trong nhóm, tên này có thể chưa đúng.

Ảnh được cắt từ màn hình khi bạn mở đoạn chat nên chất lượng bằng ảnh đang hiển thị, không phải
ảnh gốc. Video chỉ lưu được ảnh xem trước, file chỉ lưu được tên. Tin thoại chưa được hỗ trợ.
Nhận diện ảnh dựa vào nhãn "Photo", "Video"; nếu Messenger dùng nhãn khác, vào ⚙ > Ghi cấu trúc
màn hình để lấy nhãn thật. Tin thu từ màn hình mang giờ lúc app đọc
được, không phải giờ gửi gốc; tin cũ tìm thấy khi cuộn lên được xếp đúng thứ tự và mượn giờ của
tin ngay sau nó. Cuộn từ từ để luôn còn tin đã lưu trên màn hình, vì cuộn quá nhanh sẽ làm app
không biết đoạn mới nằm ở đâu. Bong bóng trên cùng màn hình chỉ được lưu khi cuộn lên thấy tin
phía trước nó.

Messenger không cho biết mã đoạn chat trên màn hình, nên app chỉ lưu khi tiêu đề và tin đã đứng yên,
để tin của đoạn trước không lọt vào đoạn vừa mở. Hai người trùng tên được phân biệt lúc mở chat: nếu
tin đang hiện chưa có trong đoạn cùng tên nào, app so avatar, khác thì tạo đoạn "Tên (2)". Không có
avatar để so thì tin vào đoạn đã có, nên hai người trùng tên có thể vẫn bị gộp trong trường hợp này.
Nhật ký từng lần lưu nằm ở ⚙ > Xem nhật ký lưu tin, chỉ gồm tên đoạn chat, không có nội dung tin.
Đoạn đã lỡ gộp từ bản cũ không tách lại được: nhấn giữ đoạn đó trong danh sách để xoá, rồi mở lại
từng người trong Messenger.

Avatar lấy từ ảnh trong thông báo, hoặc cắt từ màn hình khi thấy avatar trong đoạn chat. Ảnh chụp
màn hình chỉ dùng để cắt avatar rồi bỏ, không lưu lại. Chưa có avatar thì hiện chữ cái đầu.

Khi cập nhật từ bản 0.1, dữ liệu cũ được giữ và tự làm sạch. Nếu avatar không hiện, tắt rồi bật
lại Trợ năng để app nhận quyền chụp màn hình.

## Khi không lưu được tin

Vào ⚙ và đọc dòng trạng thái:

| Dòng trạng thái | Ý nghĩa |
|---|---|
| Trợ năng: tắt, hoặc bật nhưng không chạy | Hệ thống đã tắt dịch vụ. Tắt rồi bật lại Trợ năng và cho app chạy nền |
| Lần đọc màn hình cuối: chưa có | Dịch vụ chưa từng đọc được Messenger |
| Không đọc được cửa sổ Messenger | Messenger đang hiện nhưng hệ thống không cho đọc cửa sổ |
| Không phải màn hình chat | Messenger đổi giao diện; dùng Ghi cấu trúc màn hình rồi gửi kết quả |
| Tên chat: thấy N tin, 0 tin mới | App đọc được, các tin đó đã có sẵn |

## Cài trên iQOO Z10 Turbo Pro

1. Chép `app-debug.apk` sang điện thoại và mở để cài (cho phép cài từ nguồn này).
2. Mở app, thử bấm **Bật đọc thông báo** và **Bật Trợ năng**. Nếu mục bị mờ hoặc báo
   "Cài đặt bị hạn chế": vào Cài đặt > Ứng dụng > Lưu tin Messenger > menu ⋮ >
   **Cho phép cài đặt bị hạn chế**, rồi bật lại.
3. Để app không bị tắt nền: trong mục pin của app, chọn cho phép chạy nền không giới hạn, và
   khoá app trong màn hình đa nhiệm. Tên mục có thể khác tuỳ bản OriginOS. Đây là bước bắt buộc:
   khi app bị buộc dừng (dọn nền, dọn RAM), Android tự tắt Trợ năng của app, và app không thể tự
   bật lại. Đừng vuốt tắt app và đừng tắt thông báo "Đang lưu tin Messenger".
4. Mở một đoạn chat, gửi thử một tin, rồi quay lại app xem có xuất hiện đúng chiều không.

## Build

Cần JDK 17 và Android SDK (platform 35).

```bash
./gradlew testDebugUnitTest assembleDebug
```

APK nằm ở `app/build/outputs/apk/debug/app-debug.apk`.

Kiểm tra phần lưu trữ trên SQLite thật (chỉ chạy trên máy ảo, vì lệnh này xoá dữ liệu đã lưu):

```bash
./gradlew installDebug installDebugAndroidTest
adb shell am instrument -w com.lately.msgarchive.test/com.lately.msgarchive.StoreCheck
```

Bản debug ký bằng khoá của máy build; build từ máy khác rồi cài đè sẽ phải gỡ app trước, và gỡ
app là mất dữ liệu đã lưu, nên hãy xuất JSON trước.
