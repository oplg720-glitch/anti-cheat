# AegisAC Meteor Edition 1.7.2

Anti-cheat cho Paper, bổ sung phân tích packet qua PacketEvents và lớp kiểm kê/chống nhân bản vật phẩm bằng serial có chữ ký HMAC.

## 1. Phiên bản và build

- API compile target: Paper 1.21.11.
- `plugin.yml` đặt `api-version: '1.21.11'`; không cài được trên Paper 1.21.0–1.21.10.
- Source/bytecode target: Java 21. Có thể chạy bytecode Java 21 trên JVM mới hơn, nhưng bản này chưa được build/runtime-test trên Paper 26.x.
- Build: Gradle 8.7+ và JDK 21.
- Windows: `BUILD-WINDOWS.bat`
- Linux/macOS: `./BUILD-LINUX-MAC.sh`
- Hoặc chạy `gradle clean build`.
- Output dự kiến: `build/libs/AegisAC-Meteor-1.7.2.jar`.

### PacketEvents là dependency riêng

Phần packet-level cần PacketEvents **2.14.0 trở lên** được cài riêng trong thư mục `plugins/`. Gradle dùng dependency `compileOnly`, do đó file PacketEvents không được nhúng vào AegisAC. Nếu thiếu PacketEvents, các check Bukkit/Paper vẫn hoạt động; packet checks sẽ hiện disabled trong console và `/aegis dupe status`.

PacketEvents 2.14.0 hỗ trợ packet protocol mới tới Minecraft 26.3. Một thay đổi quan trọng là từ Minecraft 26.1, gói tấn công entity được tách thành packet `ATTACK`; các phiên bản cũ hơn đưa hành động này trong `INTERACT_ENTITY`. AegisAC đọc `InteractAction.ATTACK` bên trong `INTERACT_ENTITY` trên protocol cũ và theo dõi packet `ATTACK` riêng ở protocol 26.1+; packet rate vẫn chỉ là tín hiệu bất thường, không phải bằng chứng tự động về cheat.

## 2. Anti-cheat

### Bổ sung trong 1.7.1
- Packet attack counting trên 1.21.11 đọc action bên trong `INTERACT_ENTITY`; từ 26.1+ đếm packet `ATTACK` riêng.
- Item entity mới được serial-tag ở tick tiếp theo sau khi spawn.
- Tất cả rollback-eligible transfer events dùng chung identity registry theo tick; giao dịch chồng lấn trên cùng player/container/item sẽ không bị hoàn tác từ snapshot cũ.
- Serial cũ/không rõ nguồn cần hai lượt full-scan sạch liên tiếp trước khi ledger tin vị trí gốc.
- Chunk mới load được lên lịch quét ngay, ngoài vòng quét nền định kỳ.


AegisAC kết hợp ba lớp:

1. **Paper/Bukkit behavior:** movement, combat, block break/place, interaction, farming và automation patterns.
2. **PacketEvents:** tần suất movement, entity interaction/attack, inventory click, digging, item use và tổng packet theo cửa sổ 1 giây; yêu cầu nhiều cửa sổ liên tiếp trước khi staff alert.
3. **Server hardening:** dùng Paper Anti-Xray để giảm lợi thế của Xray thay vì cố suy đoán module render chỉ từ hành vi đào.

### Nhóm check có trong source

- Combat: Reach, KillAura pattern heuristic, AutoClicker/CPS, NoSwing.
- Movement: Speed, Fly/Hover, Timer heuristic, ImpossibleMove, NoFall, NoSlow, Jesus, Spider, Elytra anomaly.
- Building: Scaffold/Bridge pattern, FastPlace, AirPlace/extended interaction, Baritone Builder và Large Schematic pattern.
- Mining: FastBreak, Nuker/PacketMine burst, Baritone mining pattern, Xray mining statistics.
- Automation: Baritone farming/exploration behavior, AutoFish timing, BowSpam, explosive interaction burst, inventory automation.
- Packet rate anomaly: MovementPackets, EntityInteractionPackets, AttackPackets (khi protocol có packet riêng), InventoryPackets, DiggingPackets, UsePackets, TotalPackets.

**Giới hạn nhận diện:** Plugin không đọc được danh sách module bên trong Fabric client. ESP, Fullbright, Chams, NoRender, Zoom và một số kiểu visual Xray không tạo ra dấu vết chắc chắn ở server. KillAura, Baritone, Nuker, AutoFish và các hành vi tương tự được phân tích qua thống kê/hành vi nên có thể có false positive. Packet anomalies chỉ cảnh báo; không tự động ban.

## 3. Automatic DupeGuard — tự chống dupe, không cần lệnh quét

DupeGuard chạy nền ngay khi plugin bật. Lệnh `/aegis dupe scan` chỉ là công cụ chẩn đoán thủ công, **không phải bước cần chạy để bật chống dupe**.

### Lớp A — kiểm tra giao dịch theo thời gian thực

- `InventoryClickEvent` và `InventoryDragEvent`: chụp trạng thái trước giao dịch của túi người chơi, con trỏ và container đang mở. Với thao tác di chuyển thuần túy trong rương/thùng/hopper/shulker, tổng số lượng từng fingerprint item phải được bảo toàn. Nếu sau giao dịch số lượng một fingerprint tăng, AegisAC tự hoàn tác snapshot và ghi incident. Nếu cùng người chơi/container đang tham gia một giao dịch khác trong cùng tick, plugin bỏ rollback snapshot xung đột để tránh ghi đè giao dịch hợp lệ.
- `InventoryMoveItemEvent`: kiểm tra bảo toàn số lượng giữa inventory nguồn và đích của hopper/container; tự hoàn tác hai inventory nếu số lượng tăng bất thường và cặp inventory không có giao dịch chồng lấn trong cùng tick.
- `EntityPickupItemEvent` và `InventoryPickupItemEvent`: so sánh inventory với item entity trước/sau nhặt; nếu tổng số lượng tăng, tự khôi phục inventory và item entity.
- Stack có lượng vượt `ItemStack.getMaxStackSize()` bị chặn ở các sự kiện tương ứng và đưa vào quarantine nếu vẫn còn tồn tại sau khi giao dịch bị hủy.
- Fingerprint sử dụng NBT serialize của Paper, bỏ hai PDC serial riêng của AegisAC trước khi so sánh để việc đánh dấu serial không làm sai phép cân bằng.

### Lớp B — serial HMAC cho vật phẩm không stack được

- Gắn UUID + HMAC-SHA256 vào item có `getMaxStackSize() == 1`; không gắn serial riêng cho stackable để tránh phá cơ chế ghép stack.
- Item entity mới được kiểm tra ở tick kế tiếp; plugin quét chunk lân cận và inventory người chơi gần đó để phát hiện bản sao sớm.
- Kiểm tra inventory online, Ender Chest, container trong chunk load, entity inventory, item frame, mob equipment và item rơi.
- Serial trùng được cách ly tự động khi ledger đã xác định được đúng một vị trí gốc. Serial cũ/không rõ nguồn cần hai lượt quét nền sạch liên tiếp để thiết lập canonical source. Nếu provenance chưa rõ hoặc có nhiều ứng viên, plugin chỉ cảnh báo/ghi incident, tuyệt đối không đoán bản nào là bản gốc.
- Các bản sao đã xác nhận được chuyển vào quarantine và lưu bền; admin chỉ cần dùng lệnh restore nếu cần cứu đồ.

### Lớp C — kiểm tra liên tục và cấu hình Paper

- Vòng quét toàn bộ chunk đang load tự chạy theo `dupe.full-scan-interval-seconds`; không cần admin gọi scan.
- Theo dõi click/pickup burst, log incidents và tự động rollback các transfer mà AegisAC quan sát được.
- Khi khởi động, AegisAC đọc `config/paper-global.yml` để cảnh báo nếu các cờ phục hồi TNT/carpet/rail duplication, sand/end-portal duplication, tripwire duplication hoặc permanent-block breaking đang bật. Plugin không tự sửa file cấu hình Paper đang chạy.
- Nếu khóa HMAC bị mất/hỏng, xác minh serial tạm dừng để tránh cách ly nhầm toàn bộ vật phẩm.

### Lệnh quản trị — chỉ để xem và xử lý ngoại lệ

```text
/aegis dupe status
/aegis dupe incidents
/aegis dupe quarantine list
/aegis dupe quarantine restore <id> <online-player>
/aegis dupe quarantine delete <id>
/aegis dupe scan [online-player]   # tùy chọn, chỉ chẩn đoán
```

Không cần dùng lệnh scan thường xuyên. `restore` là bước admin xác nhận để cứu đồ; `delete` xóa vĩnh viễn bản đã cách ly.

### Bắt buộc sao lưu khóa

Sao lưu nguyên vẹn toàn bộ các file sau trong `plugins/AegisAC/`:

- `dupe-secret.key` — khóa ký HMAC, quan trọng nhất.
- `dupe-ledger.yml` — sổ serial/vị trí gốc.
- `dupe-quarantine.yml` — vật phẩm đang cách ly.
- `dupe-incidents.yml` — nhật ký sự cố.

Không xóa, sửa tay hoặc chia sẻ `dupe-secret.key`. Nếu khóa bị mất/hỏng, khôi phục bản backup gốc. AegisAC sẽ pause xác thực serial để tránh xóa nhầm hàng loạt vật phẩm. Nếu bạn xóa toàn bộ thư mục dữ liệu plugin cùng cả marker/ledger/secret, plugin không còn cách nào biết những vật phẩm đã từng được cấp serial từ trước.

### Phạm vi chống dupe — đọc kỹ

Không có plugin nào có thể đảm bảo chặn mọi exploit. Lớp conservation rollback chỉ bao phủ các giao dịch Paper/Bukkit mà plugin quan sát được (container click/drag, hopper transfer, player/hopper pickup) và các thao tác chuyển đồ thuần túy; nó không thể cân bằng an toàn các giao dịch tạo/đổi item như crafting, smithing, anvil, trade, plugin API tùy biến, rollback do crash hoặc exploit chưa biết. Serial HMAC mạnh nhất với item không stack được giữ nguyên PDC. Các stackable bị theo dõi qua lượng trước/sau trong các đường giao dịch được hỗ trợ, nhưng không có serial độc lập. Full scan chỉ thấy online inventories và chunk đã load; không mở force-load chunk/offline playerdata. Hãy cập nhật Paper, giữ các cờ dupe-hardening ở mức an toàn, sao lưu thế giới và test trên staging.

## 4. Cấu hình Paper chống exploit phía server

Để tăng lớp chống dupe, không chỉ dựa vào plugin:

- `extras/paper-global-dupe-hardening-example.yml`: giữ các cờ Paper `allow-piston-duplication`, `allow-unsafe-end-portal-teleportation`, `skip-tripwire-hook-placement-validation`, `allow-permanent-block-break-exploits` và `allow-headless-pistons` ở `false`.
- `extras/paper-world-dupe-hardening-example.yml`: bật `fixes.disable-unloaded-chunk-enderpearl-exploit` và `fixes.split-overstacked-loot`.
- `extras/paper-world-defaults-antixray-example.yml`: merge phần `anticheat.anti-xray` để giảm lợi thế Xray.

Các file trong `extras/` chỉ là **mẫu để merge**, không chép đè toàn bộ config hiện tại. Paper mặc định vá một số exploit như TNT/carpet/rail duplication, gravity-block duplication qua End portal và tripwire-related dupe; các cờ `unsupported-settings` nếu bật lại có thể phục hồi exploit. Xem tài liệu Paper trước khi sửa config và restart server sau khi merge.

AegisAC không thể xác minh trực tiếp Xray render-only. Hãy xem file `extras/paper-world-defaults-antixray-example.yml` và **merge** phần `anticheat.anti-xray` vào `config/paper-world-defaults.yml` của Paper, giữ nguyên các cấu hình hiện có của bạn rồi restart server.

`engine-mode: 2` obfuscates các block được khai báo bằng block thay thế và có thể ẩn thêm block tiếp giáp không khí. Cách này giảm hiệu quả Xray phía client, nhưng có trade-off hiệu năng và một số trường hợp lộ thông tin từ seed/world generation không thể giải quyết chỉ bằng obfuscation.

## 5. Ban và vùng base

Các chức năng hiện có từ các bản trước vẫn được giữ:

```text
/aegis ban <player> <reason>
/aegis ban <player> <duration> <reason>
/aegis ban <player> perm <reason>
/aegis tempban <player> <duration> <reason>
/aegis pardon <player>
/aegis banlist
/aegis kick <player> <reason>

/aegis base create <name> <world> <x> <y> <z> [radius]
/aegis base create <name> <world> <x1> <y1> <z1> <x2> <y2> <z2>
/aegis base list
/aegis base info <name>
/aegis base delete <name>
```

## 6. Auto-ban và quyền

- `general.auto-ban: false` mặc định.
- Các heuristic Baritone/Meteor và packet rate không tự ban.
- Cần xem alert, log và test trên staging trước khi bật bất kỳ cơ chế xử phạt tự động nào.

Permissions:
- `aegis.admin` — quản trị AegisAC.
- `aegis.alerts` — nhận staff alerts.
- `aegis.bypass` — bypass một số movement/behavior and rate checks; không nên cấp tùy tiện.
- `aegis.base.bypass` — đi xuyên qua bảo vệ base.

## 7. Kiểm thử

Xem `DUPLICATION-TEST-PLAN.md` để chạy checklist trên server staging, gồm kiểm tra quarantine, rollback, key persistence và packet protocol.


Đây là **source ZIP, không phải JAR đã biên dịch**. Trong môi trường tạo gói hiện tại có JDK 21 nhưng không có Gradle, Paper API/PacketEvents JAR cache, và không tải được dependency từ Maven. YAML và tính toàn vẹn ZIP có thể kiểm tra cục bộ; full Gradle type-check, Paper server boot, packet behavior, dupe simulation và cross-version testing vẫn phải thực hiện trên server staging.

## Admin-only Survival flight

Use `/aegis fly` to toggle flight for the administrator who runs the command. It only works while that player is in Survival; the plugin does not switch the player to Creative. The command requires `aegis.admin` (default: operators). The grant is session-scoped and is revoked when the player disconnects, the plugin shuts down, or the player leaves Survival. If another plugin already grants flight, AegisAC leaves that permission untouched.



## Version 1.7.2
- Added `/aegis fly` for administrators to toggle flight in Survival without changing game mode.
- Flight is excluded from movement/fly heuristics through Bukkit flight state.
- The grant is revoked on quit, plugin disable, or leaving Survival; it is restored after respawn while still active.
