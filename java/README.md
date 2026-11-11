# Java 后台

使用 Java 21、Spring Boot、MyBatis、Flyway 和 MySQL。成语试玩接口 `/api/local/**` 仅在 `local` profile 下开放。正式微信登录、权限和支付接口尚未实现。

## 代码结构

Java 后台以一个 Spring Boot 应用运行，按业务分包。`com.zhiya.preschool.game` 负责小程序的关卡、答题和进度，当前试玩接口使用 `/api/local/**`。管理端开发时使用独立的 `com.zhiya.preschool.admin` 和 `/api/admin/**`，负责词条审核、出题和关卡发布。管理端的身份校验放在管理端接口边界。

两个业务包各自维护下面的分层。它们可以读写同一个数据库，由同一套 Flyway 迁移维护表结构。管理端写入已发布的题目和关卡，游戏端读取发布结果。

| 包 | 职责 |
| --- | --- |
| `controller` | 接收 HTTP 请求并返回结果 |
| `service` | 处理所在业务的流程和规则 |
| `mapper` | MyBatis 数据库操作 |
| `exception` | 业务异常 |

## 本地运行

1. 在本机 MySQL 创建**空数据库**：`CREATE DATABASE preschool_education CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;`。如果已有同名开发库且数据不需要保留，可先执行 `DROP DATABASE preschool_education;`，再创建。
2. 设置环境变量 `DB_USER`、`DB_PASSWORD`；默认连接 `127.0.0.1:3306/preschool_education`，也可用 `DB_URL` 覆盖。不要把密码写入会提交到 Git 的配置文件。
3. 在 `java/` 目录执行 `mvn spring-boot:run -Dspring-boot.run.profiles=local`。

本机也可以用 Git 已忽略的 `java/config/application-local.yml` 配置数据库密码。使用该文件时，IDEA 启动配置的工作目录设为仓库中的 `java` 目录。

Flyway 启动时自动建表并插入 11 关样例题。接口：

> V1 建表脚本已重写，包含每张表和每个字段的中文注释。如果本机数据库已运行过旧版 V1，Flyway 会检测到校验值变化。当前仅是开发库且不需要保留数据时，请先备份需要的内容，再在 MySQL Workbench 中删除并重建 `preschool_education` 数据库，然后启动后台让 Flyway 从头建表。不要只执行 `flyway repair`：它会更新校验值，但不会给现有表补上注释。

- `GET /api/local/levels`：获取已发布的体验关卡列表。
- `POST /api/local/sessions/current`：按试玩者标识继续最早未提交的关卡，或返回购买解锁提示、已完成状态。
- `POST /api/local/levels/{levelNo}/sessions`：创建指定关卡的答题回合，返回题目、10 个字块和下一已发布关卡的序号。
- `GET /api/local/sessions/{id}`：恢复未完成的回合。
- `POST /api/local/sessions/{id}/submit`：提交全部 3 个空位，返回判题结果和讲解。请求示例：`{"answers":[{"questionId":1,"tileNo":2},{"questionId":2,"tileNo":5},{"questionId":3,"tileNo":7}]}`。

当前接口仅供本机开发和玩法验证，**不要以 `local` profile 对公网部署**。

启动 Java 时可通过环境变量 `IDIOM_FREE_LEVEL_COUNT` 配置成语模块的免费关卡数，默认 `300`。已发布且超出免费范围的关卡会提示购买解锁。此版本只展示提示，购买流程尚未接入。

## 导入本地成语词库

1. 启动 Java 一次，让 Flyway 执行 `V6__idiom_entry.sql`，创建 `idiom_entry` 表。
2. 在仓库根目录运行 `python scripts/prepare_idiom_import.py`。脚本读取 `docs/idiom.json`，输出 `java/target/idiom-import.sql`。Maven 清理 `target` 后，可以重新生成。
3. 在 MySQL 客户端选中本地 `preschool_education` 库，打开并执行生成的 SQL 文件。若启动时通过 `DB_URL` 指定了另一个库，选择对应的开发库。
4. 执行 `SELECT COUNT(*) FROM idiom_entry WHERE source_name = 'chinese-xinhua';` 核对数量。这份 JSON 首次完整导入后应为 30,895 条。

导入保留原词面和原拼音，标记四字词及拼音格式，词条均处于待审核状态。第 1 至第 11 关用到的 33 个成语优先采用人工分级。其余词条根据长度和汉字在词库中的出现次数生成 1～4 级初评。该估算反映字形常见程度，词义和出题难度仍需审核。重复执行会保留已设置的难度和人工审核结果。

已导入词库时，运行 `python scripts/prepare_idiom_import.py --difficulty-only`，再在原开发库执行 `java/target/idiom-difficulty.sql`。这个脚本只补空难度。

## 日志

后台默认向控制台和 `java/logs/preschool-api.log` 输出日志，单文件达到 10 MB 后滚动，保留 7 份历史日志。可通过 `APP_LOG_LEVEL` 调整本项目日志级别，通过 `LOG_FILE` 修改日志文件路径。日志只记录回合编号、关卡编号、题目数量、判题统计及异常原因，不记录数据库密码或孩子的具体答案。`logs/` 已加入 Git 忽略列表。
