# PhantomPad (formerly Phantom Mic)

*Note: Vietnamese translation is available below / Bản dịch tiếng Việt có ở bên dưới.*

An LSPosed (Xposed) module to simulate microphone input 🎤 from any pre-recorded audio file. This turns your device into a powerful Soundboard that perfectly intercepts microphone requests from any application.

<details>
  <summary>Demo Video</summary>

https://github.com/user-attachments/assets/12a9d229-fd8a-4370-b969-1a342360abdf

</details>

## Features

- **Material Design 3 App**: An intuitive user interface for managing your soundboard files.
- **Easy File Selection**: No need to manually edit `phantom.txt` anymore. Just open the app and pick your audio folder!
- **Per-App Routing (Multi-App Support)**: Select different MP3s for different apps. E.g., App A gets audio 1, App B gets audio 2 simultaneously.
- **Fully Permissionless Target Apps**: Target applications do not need storage permissions to read the audio files; it works silently and completely invisibly in the background.

## Tested Apps

| Application        | Status    |
| ------------------ | --------- |
| Facebook Messenger | ✔ Working |
| Discord            | ✔ Working |
| Telegram           | ✔ Working |
| Whatsapp           | ✔ Working |
| Google Chrome      | ✔ Working |
| Sound Recorder     | ✔ Working |
| .. You tell me!    |           |

Note: your app might work if it's not on the list, let us know if you tried it!

## Usage Guide

1. **Install Module:** Install PhantomPad and enable the module in LSPosed.
2. **Select Target Apps:** In LSPosed, check the applications you want to inject audio into (e.g., Whatsapp, Messenger, etc.).
3. **Reboot or Force Stop:** Reboot your device or Force Stop the target apps you just selected.
4. **Choose Folder:** Open the **PhantomPad** app from your launcher, tap the Floating Action Button, and select the folder containing your MP3, WAV, or AAC files.
5. **Assign Audio:** Tap on any audio file in the list. A menu will appear asking you to choose an application. Select "Global (All Apps)" or assign it to a specific app.
6. **Use:** Open your target application and record voice/audio. It will automatically transmit the selected audio file as if it were the real microphone input!

## Requirements

- Android 7+
- [Root] LSPosed / Edxposed

## Module not working?

Please open a github issue.
- If the app is labelled as "Working" in **Tested Apps**. Please attach `libaudioclient.so`, you can copy it from `/system/lib` or `/system/lib64`, alongside logs.
- Otherwise send play store version of the app and explain how I can test it.

## Developer Notes

The app relies on native hooking `AudioRecord.cpp`, feel free to take a look at the source code!

---

# Tiếng Việt (Vietnamese)

# PhantomPad (trước đây là Phantom Mic)

Một module LSPosed (Xposed) dùng để mô phỏng đầu vào micro 🎤 từ các file âm thanh có sẵn. Ứng dụng này biến thiết bị của bạn thành một Soundboard mạnh mẽ, chặn và thay thế hoàn hảo các yêu cầu thu âm từ micro của bất kỳ ứng dụng nào.

## Tính Năng

- **Giao diện Material Design 3**: Giao diện trực quan hiện đại để dễ dàng quản lý danh sách nhạc Soundboard.
- **Chọn file dễ dàng**: Không cần phải sửa file `phantom.txt` thủ công như trước. Chỉ cần mở App và chọn thư mục chứa file MP3!
- **Chỉ định theo từng App**: Cho phép chỉ định các file âm thanh khác nhau cho từng ứng dụng khác nhau. Ví dụ: WhatsApp dùng file nhạc A, Messenger dùng file nhạc B cùng một lúc.
- **Không yêu cầu cấp quyền rườm rà**: Ứng dụng bị hook không cần quyền bộ nhớ để đọc file; mọi thứ hoạt động vô hình và mượt mà ở chế độ nền thông qua cơ chế ContentProvider.

## Các Ứng Dụng Đã Test

| Ứng dụng           | Trạng thái |
| ------------------ | ---------- |
| Facebook Messenger | ✔ Hoạt động|
| Discord            | ✔ Hoạt động|
| Telegram           | ✔ Hoạt động|
| Whatsapp           | ✔ Hoạt động|
| Google Chrome      | ✔ Hoạt động|
| Sound Recorder     | ✔ Hoạt động|
| .. Và nhiều App khác! |         |

Lưu ý: App của bạn có thể vẫn hoạt động tốt dù không có trong danh sách, hãy cho chúng tôi biết trải nghiệm của bạn!

## Hướng Dẫn Sử Dụng

1. **Cài đặt Module:** Cài đặt PhantomPad và bật module trong ứng dụng LSPosed.
2. **Chọn App mục tiêu:** Trong LSPosed, đánh dấu tick vào các ứng dụng mà bạn muốn thay đổi micro (ví dụ: Whatsapp, Messenger, v.v.).
3. **Khởi động lại (Reboot) hoặc Buộc dừng (Force Stop):** Bạn cần khởi động lại máy hoặc Buộc dừng các ứng dụng mục tiêu để hook có hiệu lực.
4. **Chọn Thư Mục Nhạc:** Mở cấu hình **PhantomPad** từ màn hình chính, nhấn vào nút chọn thư mục (nút nổi kề dưới) và chọn nơi bạn lưu các file MP3, WAV, AAC.
5. **Gán Âm Thanh:** Chạm vào bất kỳ file âm thanh nào trong danh sách. Một bảng chọn hiện lên hỏi bạn muốn kích hoạt cho ứng dụng nào. Bạn có thể chọn "Global (Chung cho tính năng gọi của mọi app)" hoặc gán riêng cho môt app.
6. **Sử dụng:** Bây giờ hãy mở ứng dụng nhắn tin của bạn và bắt đầu thu âm. Ứng dụng sẽ tự động phát đoạn âm thanh thay vì thu âm từ micro thật!

## Yêu cầu hệ thống

- Hệ điều hành Android 7+ trở lên
- Máy đã Root và cài đặt LSPosed / Edxposed

## Lỗi không hoạt động?

Hãy báo lỗi (open issue) trên Github.
- Đối với những app được ghi "Hoạt động", vui lòng đính kèm file `libaudioclient.so` (copy từ `/system/lib` hoặc `/system/lib64`) cùng với file logs của bạn.
- Còn lại vui lòng cung cấp phiên bản Google Play của app và hướng dẫn tôi cách test.

## Dành cho nhà phát triển

Dự án này sử dụng native hooking vào `AudioRecord.cpp`, hãy đọc mã nguồn để hiểu rõ hơn cách hoạt động!
