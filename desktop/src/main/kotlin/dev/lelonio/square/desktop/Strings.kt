package dev.lelonio.square.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Language(val code: String, val label: String) { English("en", "English"), Vietnamese("vi", "Tiếng Việt") }

/**
 * The desktop's own strings, in English as written in the code and in
 * Vietnamese from this table. The phone's strings.xml are Android resources the
 * desktop cannot load, and most of its screens' wording is the desktop's own.
 */
object Strings {
    var current by mutableStateOf(Language.English)

    val VI = mapOf(
        "Search" to "Tìm kiếm", "Home" to "Trang chủ", "Library" to "Thư viện", "Playlists" to "Danh sách phát",
        "Your month" to "Tháng của bạn", "Settings" to "Cài đặt", "Pinned" to "Đã ghim",
        "Quick play" to "Phát nhanh", "Moods & genres" to "Tâm trạng & thể loại", "Playlists on NAS" to "Danh sách phát trên NAS",
        "Recently played" to "Nghe gần đây", "Recently added" to "Mới thêm", "Most played" to "Nghe nhiều nhất",
        "Discover" to "Khám phá", "Favorites" to "Yêu thích", "Downloaded" to "Đã tải xuống", "See all" to "Xem tất cả",
        "Couldn't reach the server — showing what is on this computer." to "Không kết nối được server — đang hiện nhạc có trên máy này.",
        "Couldn't reach the server" to "Không kết nối được server",
        "Albums" to "Album", "Artists" to "Nghệ sĩ", "Songs" to "Bài hát",
        "Artists, albums, songs" to "Nghệ sĩ, album, bài hát", "Recent searches" to "Tìm kiếm gần đây", "Clear" to "Xoá",
        "Play" to "Phát", "Shuffle" to "Trộn bài", "Download" to "Tải xuống", "Pin" to "Ghim", "Unpin" to "Bỏ ghim",
        "Order" to "Thứ tự", "Title" to "Tên", "Artist" to "Nghệ sĩ", "Album" to "Album", "Added" to "Ngày thêm", "Length" to "Độ dài",
        "Descending" to "Giảm dần", "Ascending" to "Tăng dần", "Show as grid" to "Dạng lưới", "Show as list" to "Dạng danh sách",
        "Play next" to "Phát tiếp theo", "Add to queue" to "Thêm vào hàng chờ", "Go to album" to "Đi tới album",
        "Go to artist" to "Đi tới nghệ sĩ", "Remove download" to "Xoá bản tải", "Inspect" to "Kiểm tra file",
        "Copy link" to "Sao chép liên kết", "Link copied" to "Đã sao chép liên kết", "Remove from playlist" to "Xoá khỏi danh sách phát",
        "Removed from playlist" to "Đã xoá khỏi danh sách phát", "Delete from NAS" to "Xoá khỏi NAS", "Delete" to "Xoá", "Cancel" to "Huỷ",
        "The file is deleted from the server and taken out of every playlist. This can't be undone." to
            "File sẽ bị xoá khỏi server và khỏi mọi danh sách phát. Không thể hoàn tác.",
        "Close" to "Đóng", "Reading the file and its spectrum…" to "Đang đọc file và phổ âm thanh…",
        "Nothing downloaded yet. Right-click a song, or use Download on an album or playlist." to
            "Chưa tải bài nào. Nhấp chuột phải vào bài hát, hoặc bấm Tải xuống trên album hay danh sách phát.",
        "Your month in music" to "Tháng âm nhạc của bạn", "Nothing played this month yet." to "Tháng này chưa nghe bài nào.",
        "Song of the month" to "Bài hát của tháng", "Most played songs" to "Bài nghe nhiều nhất", "Most played artists" to "Nghệ sĩ nghe nhiều nhất",
        "Previous month" to "Tháng trước", "Next month" to "Tháng sau", "minutes" to "phút", "plays" to "lượt phát",
        "songs" to "bài hát", "artists" to "nghệ sĩ",
        "Up next" to "Tiếp theo", "Clear the queue" to "Xoá hàng chờ", "Move up" to "Lên", "Move down" to "Xuống",
        "Remove from queue" to "Xoá khỏi hàng chờ", "Sleep timer" to "Hẹn giờ tắt", "End of song" to "Hết bài",
        "Turn off" to "Tắt", "Lyrics" to "Lời bài hát", "Lyrics (L)" to "Lời bài hát (L)", "Queue" to "Hàng chờ",
        "Close (Esc)" to "Đóng (Esc)", "Translate lyrics" to "Dịch lời", "Hide translation" to "Ẩn bản dịch",
        "No lyrics for this song" to "Bài này chưa có lời", "Automatic" to "Tự động", "Change" to "Đổi",
        "Lyrics from" to "Lời từ", "Try another source" to "Thử nguồn khác",
        "Previous" to "Bài trước", "Next" to "Bài sau", "Repeat" to "Lặp lại", "Back" to "Quay lại", "Not playing" to "Chưa phát",
        "Navidrome / Subsonic server" to "Server Navidrome / Subsonic", "Server URL" to "Địa chỉ server",
        "Username" to "Tên đăng nhập", "Password" to "Mật khẩu", "Connect" to "Kết nối", "Rescan server library" to "Quét lại thư viện trên server",
        "Downloads" to "Tải xuống", "Open folder" to "Mở thư mục", "Remove all downloads" to "Xoá mọi bản tải",
        "Removed all downloads" to "Đã xoá mọi bản tải", "Appearance" to "Giao diện", "Dark" to "Tối", "Light" to "Sáng",
        "Font" to "Phông chữ", "System" to "Hệ thống", "Language" to "Ngôn ngữ", "Glass" to "Kính",
        "Renderer (applies after a restart)" to "Bộ vẽ (có hiệu lực sau khi mở lại app)", "Software" to "Phần mềm",
        "Crossfade" to "Chuyển bài mượt", "Off" to "Tắt", "Equalizer" to "Bộ chỉnh âm", "Flat" to "Phẳng", "Vocal" to "Giọng hát",
        "Lyrics style" to "Kiểu lời bài hát", "Animated" to "Chuyển động", "Classic" to "Cổ điển",
        "Lyrics timing" to "Lệch lời bài hát", "Done" to "Xong", "Volume" to "Âm lượng", "Mood" to "Tâm trạng",
        "Updates" to "Cập nhật", "Check for updates" to "Kiểm tra cập nhật", "Diagnostics" to "Chẩn đoán",
        "Save diagnostics report" to "Lưu báo cáo chẩn đoán", "Playback" to "Phát nhạc", "Speed" to "Tốc độ", "Pitch" to "Cao độ",
        "Reverb" to "Vang", "Karaoke (remove vocals)" to "Karaoke (tách giọng)", "Match loudness" to "Cân bằng độ to",
        "Trim silence" to "Cắt khoảng lặng", "Fade on skip" to "Giảm dần khi chuyển bài", "Effects" to "Hiệu ứng", "Reset" to "Đặt lại",
        "On" to "Bật", "Installer opened: " to "Đã mở trình cài đặt: ", "Update failed: " to "Cập nhật lỗi: ",
        " is available — Settings → Updates" to " đã có — Cài đặt → Cập nhật", "new" to "mới", "Version" to "Phiên bản", "Install" to "Cài",
        "You have the newest version" to "Bạn đang dùng bản mới nhất", "Downloading" to "Đang tải",
        "Report saved to " to "Đã lưu báo cáo vào ", " and copied" to " và sao chép", "Library rescan started" to "Đã bắt đầu quét thư viện",
        "Rescan failed" to "Quét lỗi", "Find in list" to "Tìm trong danh sách", "Find in album" to "Tìm trong album",
    )
}

/** [en] in the listener's language. */
fun tr(en: String): String = if (Strings.current == Language.Vietnamese) Strings.VI[en] ?: en else en
