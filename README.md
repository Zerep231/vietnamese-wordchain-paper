# WordChain

Plugin nối từ tiếng Việt cho Paper, với chế độ chơi công khai và đấu tay đôi, menu GUI, bảng xếp hạng, phần thưởng và từ điển có thể nhập thêm.

> **Phiên bản:** 1.4.0 · **Yêu cầu:** Java 25 và Paper 26.1 trở lên. Maven biên dịch với Paper API 26.3.

## Tính năng

- Chơi nối cụm từ tiếng Việt gồm đúng hai âm tiết; dữ liệu được chuẩn hóa Unicode NFC và không phân biệt hoa thường.
- Menu trong game, ván công khai, duel riêng, lời mời/accept/deny, bỏ phiếu bắt đầu lại ván công khai.
- Giới hạn dùng lại từ, giới hạn từ khó liên tiếp, tránh lặp từ khóa mở đầu gần đây và tự khởi tạo ván mới khi không còn nước hợp lệ.
- Thống kê thắng/chuỗi thắng và top 10 được lưu trong SQLite.
- Phần thưởng vật phẩm, tích hợp Vault tùy chọn và lệnh thưởng tùy chỉnh.
- Nhập từ điển bất đồng bộ từ danh sách văn bản, JSON, Kaikki JSONL hoặc gói dữ liệu từ điển; dữ liệu đã nhập được lưu/cache trong SQLite.
- Tích hợp PlaceholderAPI tùy chọn và API Java cho plugin khác.

## Cài đặt

1. Cài Java 25 và Paper 26.1+.
2. Tải JAR từ mục **Releases**, hoặc tự build theo hướng dẫn bên dưới.
3. Đặt `word-chain-1.4.0.jar` vào thư mục `plugins/` rồi khởi động máy chủ.
4. Chỉnh `plugins/WordChain/config.yml`. Sau khi thay đổi, chạy `/wordchain reload`.

SQLite JDBC được khai báo trong `plugin.yml` dưới dạng thư viện Paper tải khi khởi động. Vault và PlaceholderAPI là tùy chọn; cài plugin tương ứng để bật tích hợp.

## Build và kiểm thử

```bash
mvn clean verify
```

Lệnh build cần JDK 25 cùng Maven 3.9+. JAR đầu ra: `target/word-chain-1.4.0.jar`.

## Lệnh và quyền

Các lệnh có thể dùng qua `/wordchain`, alias `/wc` hoặc `/noitu`.

| Lệnh | Tác dụng | Quyền |
| --- | --- | --- |
| `/wordchain` hoặc `/wordchain menu` | Mở menu trò chơi | `wordchain.menu` |
| `/wordchain start [cụm-từ]` | Bắt đầu ván công khai | `wordchain.play` |
| `/wordchain stop` | Mở vote dừng ván; admin dừng trực tiếp | `wordchain.play` hoặc `wordchain.admin` |
| `/wordchain reset` | Đề xuất mở ván công khai mới | `wordchain.play` |
| `/wordchain vote <yes\|no>` | Bỏ phiếu cho đề xuất dừng hoặc reset | `wordchain.vote` |
| `/wordchain duel <người-chơi> [giây\|off]` | Mời đấu tay đôi | `wordchain.duel` |
| `/wordchain accept` · `/wordchain deny` | Nhận hoặc từ chối lời mời | `wordchain.duel.respond` |
| `/wordchain top` | Xem bảng xếp hạng thắng | `wordchain.top` |
| `/wordchain wins <người-chơi>` | Xem thống kê người chơi | `wordchain.stats` |
| `/wordchain report <cụm-từ>` | Báo từ còn thiếu hoặc cần xem xét | `wordchain.report` |
| `/wordchain add <cụm-từ>` · `/wordchain remove <cụm-từ>` | Sửa từ điển runtime | `wordchain.admin` |
| `/wordchain import` | Nhập lại các nguồn từ điển cấu hình | `wordchain.admin` |
| `/wordchain reload` | Tải lại cấu hình | `wordchain.admin` |
| `/wordchain skip` | Bỏ qua từ hiện tại | `wordchain.admin` |
| `/wordchain rewards` · `/wordchain reward ...` | Xem/chỉnh phần thưởng | `wordchain.rewards` |
| `/wordchain difficulty <âm-đầu> <âm-cuối> <1-5>` | Đặt độ khó cụm từ | `wordchain.admin` |
| `/wordchain reports`, `/approve`, `/reject` | Quản lý báo cáo từ | `wordchain.admin` |

`wordchain.player` là nhóm quyền mặc định cho người chơi và bao gồm quyền chơi, menu, top, duel, báo từ và vote. `wordchain.admin` mặc định dành cho operator. Khi người chơi dùng `/wordchain stop`, plugin mở vote; ngưỡng đồng ý là ít nhất 1/3 số người chơi online lúc mở vote (làm tròn lên), người đề xuất không được tự bỏ phiếu và vote hết hạn theo `game.reset-vote-seconds`. Admin có thể dùng cùng lệnh để dừng ván ngay. Có thể đặt `game.members-can-control: false` để chỉ admin được start/stop/reset.

## Cấu hình

Cấu hình mặc định nằm ở `src/main/resources/config.yml`; bản đang chạy nằm tại `plugins/WordChain/config.yml`.

| Nhóm | Tùy chọn chính |
| --- | --- |
| `game` | Số lượt để thắng, quyền điều khiển, độ dài cụm từ, độ trễ ván tiếp theo và thời hạn vote reset |
| `dictionary` | Danh sách nguồn, tự làm mới lúc khởi động, timeout tải dữ liệu |
| `rewards` | Danh sách vật phẩm, bật/tắt Vault, tiền cơ bản và lệnh console |
| `streak-rewards` | Tỉ lệ thưởng theo chuỗi thắng và giới hạn hệ số/vật phẩm |
| `anti-abuse` | Chống solo, thời gian phản ứng tối thiểu, giới hạn lặp từ và từ khó liên tiếp |
| `display` | Thời lượng lượt, nội dung BossBar và ActionBar (MiniMessage) |
| `duel` | Số lượt, thời gian nhận lời mời, thời gian mỗi lượt và giới hạn trận |
| `menu`, `reports`, `difficulty` | Tiêu đề menu, cooldown báo từ và ngưỡng/độ khó |

Ví dụ nguồn từ điển được hỗ trợ:

```yaml
dictionary:
  refresh-on-start: true
  request-timeout-seconds: 20
  sources:
    - "plain|https://example.org/vietnamese-phrases.txt"
    - "tab|https://example.org/phrases.tsv"
    - "json-array|https://example.org/phrases.json"
    - "kaikki|https://example.org/vietnamese.jsonl.gz"
    - "tu-dien-archive|https://example.org/dictionary.tar.gz"
```

Mỗi nguồn cần được rà soát giấy phép và nguồn gốc dữ liệu trước khi dùng hoặc phân phối lại. Danh sách mặc định trong config lấy từ các dự án bên thứ ba; plugin không đóng gói sẵn những bộ dữ liệu đó. Chỉ các mục tiếng Việt gồm đúng hai âm tiết mới được đưa vào chỉ mục trò chơi.

## PlaceholderAPI

Khi PlaceholderAPI được cài, expansion nội bộ `wordchain` cung cấp:

| Placeholder | Giá trị |
| --- | --- |
| `%wordchain_active%` | Ván công khai có đang chạy không |
| `%wordchain_word%` hoặc `%wordchain_keyword%` | Từ khóa hiện tại |
| `%wordchain_moves%` | Số lượt hợp lệ hiện tại |
| `%wordchain_dictionary_size%` | Số cụm từ trong từ điển runtime |

## API cho plugin khác

Plugin đăng ký `WordChainApi` qua Bukkit ServicesManager. API phát `WordChainMoveEvent` và `WordChainWinEvent`; các thao tác đọc dữ liệu SQLite trả về `CompletionStage` để tránh chặn luồng server.

## Lưu trữ và hiệu năng

Từ điển runtime, cache nguồn và thống kê được lưu trong `plugins/WordChain/wordchain.db`. Nạp/nhập từ điển và truy vấn dữ liệu bất đồng bộ; xử lý chat chuyển cập nhật thế giới và người chơi về main thread. Các lượt chat chỉ được đưa vào xử lý khi có dạng hai âm tiết phù hợp.

Phiên bản hiện tại dùng SQLite cục bộ cho từng server. Redis Pub/Sub, MySQL và đồng bộ ván/bảng xếp hạng xuyên server chưa được triển khai; chạy sau Velocity/BungeeCord không tự tạo trạng thái game chung.

## Giấy phép và dữ liệu

Mã nguồn plugin được phát hành theo MIT; xem [LICENSE](LICENSE). Dữ liệu từ điển là nguồn độc lập và có thể có điều khoản riêng. Đọc điều khoản của nhà cung cấp trước khi nhập hoặc chia sẻ lại dữ liệu đã nhập.
