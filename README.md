# WordChain

Plugin mini-game nối từ tiếng Việt cho Paper. Hỗ trợ ván công khai, đấu tay đôi có hoặc không cược, menu trong game, bảng xếp hạng và tích hợp cho plugin bên ngoài.

![Java 25](https://img.shields.io/badge/Java-25-orange) ![Paper](https://img.shields.io/badge/Paper-26.1%2B-blue) ![License](https://img.shields.io/badge/License-MIT-green)

## Tương thích

- **Java:** 25
- **Paper:** 26.1 trở lên; build dùng Paper API 26.3
- **Tùy chọn:** PlaceholderAPI; Vault cùng một economy provider nếu bật cược duel
- **Lưu trữ:** SQLite cục bộ trong thư mục plugin

## Tính năng chính

- Chuẩn hóa tiếng Việt Unicode NFC, bỏ khác biệt hoa thường và lập chỉ mục từ theo âm tiết để tra cứu nhanh.
- Chat nối từ chỉ được xử lý cho người đã tham gia ván công khai; người xem vẫn chat bình thường.
- Chặn lặp từ theo giới hạn cấu hình, chống solo, kiểm tra thời gian phản ứng và loại chu kỳ đảo ngược tức thời như `kia đó → đó kia → kia đó`.
- Duel riêng có thời gian lượt, giới hạn trận, chọn đấu vui hoặc cược vật phẩm/Vault.
- Cược chỉ được trừ khi người nhận chấp thuận; người thắng nhận pot. Trận hòa hoặc dừng bình thường sẽ hoàn cược.
- BossBar, ActionBar, GUI, reward theo chuỗi thắng, báo từ thiếu và công cụ quản trị.
- Top 10 PlaceholderAPI và API bất đồng bộ để plugin menu, scoreboard, hologram dùng dữ liệu.
- Nạp từ điển nền từ nguồn văn bản, TSV, JSON array, Kaikki JSONL hoặc archive được cấu hình.

## Cài đặt nhanh

1. Cài Java 25 và Paper 26.1+.
2. Tải `word-chain-1.6.0.jar` từ [GitHub Releases](https://github.com/Zerep231/vietnamese-wordchain-paper/releases).
3. Chép JAR vào `plugins/`, rồi khởi động máy chủ.
4. Chỉnh `plugins/WordChain/config.yml`; dùng `/noitu reload` để nạp lại cấu hình.

Paper tải SQLite JDBC theo khai báo trong `plugin.yml`. PlaceholderAPI và Vault là tùy chọn, không cần có để plugin chạy.

## Build và kiểm thử

Cần JDK 25 và Maven 3.9 trở lên.

```bash
mvn clean verify
```

JAR được tạo tại `target/word-chain-1.6.0.jar`. GitHub Actions chạy cùng lệnh build khi có tag `v*` và đính kèm JAR phù hợp với tag vào GitHub Release.

## Lệnh

Alias lệnh: `/wordchain`, `/wc`, `/noitu`.

| Lệnh | Mô tả | Quyền |
| --- | --- | --- |
| `/noitu` hoặc `/noitu menu` | Mở menu trò chơi | `wordchain.menu` |
| `/noitu start [cụm từ]` | Mở ván công khai; người chơi tham gia bằng accept | `wordchain.play` |
| `/noitu accept` · `/noitu join` · `/noitu leave` | Nhận duel, tham gia hoặc rời ván công khai | `wordchain.duel.respond` · `wordchain.play` · người chơi |
| `/noitu stop` | Người chơi đề xuất vote dừng; admin dừng ngay | `wordchain.play` · `wordchain.admin` |
| `/noitu reset` | Đề xuất mở ván công khai mới qua vote | `wordchain.play` |
| `/noitu vote <yes\|no>` | Bỏ phiếu cho đề xuất đang mở | `wordchain.vote` |
| `/noitu duel <player> <seconds\|off> <auto\|vault\|MATERIAL> <amount>` | Mời duel; `auto` chọn Vault hoặc vật phẩm mặc định; amount `0` để đấu vui | `wordchain.duel` |
| `/noitu deny` | Từ chối lời mời duel | `wordchain.duel.respond` |
| `/noitu top` · `/noitu wins <player>` | Xem top 10 hoặc thống kê người chơi | `wordchain.top` · `wordchain.stats` |
| `/noitu report <tiếng1 tiếng2>` | Báo cụm từ thiếu | `wordchain.report` |
| `/noitu add <tiếng1 tiếng2>` · `/noitu remove <tiếng1 tiếng2>` | Quản lý từ điển runtime | `wordchain.admin` |
| `/noitu import` · `/noitu reload` · `/noitu skip` | Nhập từ, tải lại config, bỏ qua từ hiện tại | `wordchain.admin` |
| `/noitu rewards` · `/noitu reward ...` | Xem hoặc chỉnh phần thưởng | `wordchain.rewards` |
| `/noitu difficulty <tiếng1> <tiếng2> <1-5>` | Đặt độ khó cụm từ | `wordchain.admin` |

Quyền cơ bản được gom trong `wordchain.player` và mặc định mở cho người chơi. Quyền quản trị mặc định dành cho operator. Đặt `game.members-can-control: false` để chỉ admin điều khiển start/reset và đề xuất dừng.

## Duel có cược

Duel mặc định là đấu vui, không cược. Khi đã cài Vault và economy provider, có thể mời cược với cú pháp:

```text
/noitu duel Steve 30 vault 50
/noitu duel Steve 30 DIAMOND 3
/noitu duel Steve off auto 0
```

Cú pháp đặt thời gian trước, sau đó chọn loại cược và số lượng. `vault` yêu cầu Vault cùng economy provider; loại vật phẩm dùng material Bukkit như `DIAMOND` hoặc `EMERALD` và cược số nguyên. `auto` chọn Vault khi khả dụng, nếu không dùng `duel.fallback-item`. Giá trị `0` tạo trận đấu vui. Hai bên phải đủ tài sản khi chấp nhận; vật cược được giữ trong lúc đấu, người thắng nhận pot, hòa hoặc hủy sẽ hoàn cược. GUI hỗ trợ cược Vault khi có economy và cược vật phẩm dự phòng khi không có Vault.

Giới hạn tiền Vault đặt tại `duel.max-wager`; giới hạn vật phẩm tại `duel.max-item-stake`. Phần thưởng trận công khai có thể tùy chỉnh qua `rewards.items`, `rewards.economy` và `rewards.commands`. Ván công khai tự đóng sau `game.idle-timeout-minutes` phút không có lượt nối: người nối cuối thắng; nếu chưa ai nối, ván bị hủy.

## Cấu hình

File mặc định: `src/main/resources/config.yml`. File runtime: `plugins/WordChain/config.yml`.

| Nhóm | Các tùy chọn đáng chú ý |
| --- | --- |
| `game` | Mục tiêu lượt, quyền điều khiển, độ dài cụm từ, timeout vote và thời gian giữa các ván |
| `dictionary` | URL nguồn, timeout tải, nạp lại khi khởi động |
| `anti-abuse` | Chống solo, độ trễ phản ứng, giới hạn lặp và chuỗi cụm khó |
| `display` · `menu` | Thời gian lượt, MiniMessage cho BossBar/ActionBar và tiêu đề GUI |
| `duel` | Số lượt thắng, thời gian lời mời/lượt/trận, chọn loại cược và giới hạn vật cược |
| `rewards` · `streak-rewards` | Vật phẩm, tiền Vault, lệnh console và hệ số streak |
| `difficulty` · `reports` | Mức khó cụm từ và cooldown báo từ |

Các loại nguồn từ điển: `plain|URL`, `tab|URL`, `json-array|URL`, `kaikki|URL`, `tu-dien-archive|URL`. Nguồn chỉ được thêm sau khi kiểm tra giấy phép và nguồn gốc dữ liệu. Plugin không đóng gói sẵn các bộ dữ liệu bên thứ ba.

## PlaceholderAPI

Expansion tích hợp có identifier `wordchain`. Các placeholder top được cache và cập nhật khi có chiến thắng hoặc theo chu kỳ, không truy vấn SQLite đồng bộ từ callback PlaceholderAPI.

| Placeholder | Giá trị |
| --- | --- |
| `%wordchain_active%` | Ván công khai đang chạy |
| `%wordchain_word%` · `%wordchain_keyword%` | Từ hiện tại |
| `%wordchain_moves%` | Số lượt hợp lệ |
| `%wordchain_dictionary_size%` | Số cụm từ đã nạp |
| `%wordchain_participating%` | Người chơi hiện tại đã vào ván công khai |
| `%wordchain_duel_active%` · `%wordchain_duel_wager%` | Trạng thái duel và cược mỗi bên |
| `%wordchain_top_<1-10>_name%` · `%wordchain_top_<1-10>_wins%` | Tên và số trận thắng theo hạng |
| `%wordchain_top_<1-10>_streak%` · `%wordchain_top_<1-10>_best_streak%` | Streak hiện tại và cao nhất |

## Tích hợp menu và API

ZMenu hoặc plugin giao diện khác có thể gọi `/noitu menu`, `/noitu duel ...`, `/noitu top` và dùng PlaceholderAPI ở trên để dựng menu/scoreboard riêng. Không cần thêm dependency bắt buộc vào WordChain.

Plugin đăng ký `com.example.wordchain.api.WordChainApi` trong Bukkit ServicesManager. API hỗ trợ kiểm tra trạng thái/từ, mở GUI, đọc wins và lấy leaderboard bất đồng bộ qua `CompletionStage`. Plugin cũng phát `WordChainMoveEvent` và `WordChainWinEvent`.

## Dữ liệu và giới hạn

Từ điển, cache nguồn và thống kê được lưu trong `plugins/WordChain/wordchain.db`. Mỗi server hiện dùng SQLite riêng; Redis, MySQL và đồng bộ trận/top xuyên Velocity hoặc BungeeCord chưa được triển khai.

Mã nguồn theo MIT (xem [LICENSE](LICENSE)). Dữ liệu nhập từ nguồn ngoài có thể có giấy phép khác; chủ server chịu trách nhiệm rà soát điều khoản trước khi phân phối lại dữ liệu.
