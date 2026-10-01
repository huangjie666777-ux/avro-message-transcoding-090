# Avro 数据契约注册中心

面向消息生产者/消费者在升级前核对 Avro Schema 兼容性的后端服务（无前端）。

- Java 17 + Javalin 6.5.0 + Apache Avro 1.12.0 + SQLite（sqlite-jdbc）
- Schema 按主题（subject）注册，版本自 1 起严格递增、内容不可变，SQLite 持久化、重启不丢
- 支持 Avro 全部基础/复杂类型、命名类型递归与 `aliases`；校验字段默认值；**不支持 `logicalType`**
- Schema 可引用其他主题的**固定版本**或**同批待发布主题**；解析完整引用闭包；拒绝缺失引用、全限定名冲突和跨主题循环（主题内部递归合法）
- 引用关系保存为精确的 `(主题, 版本)`，依赖后续升级不会改变历史版本的语义
- 每次发布选择 `BACKWARD` / `FORWARD` / `FULL`，与该主题的**全部历史版本**逐一按 Avro reader/writer 解析规则检查；失败信息包含主题、历史版本、方向、字段路径和原因
- 批量预检（dry-run）与批量发布；同一主题每批至多一次；每个条目携带 `expectedLatest`（首次为 0）
- 发布在单个 SQLite `BEGIN IMMEDIATE` 事务内完成：核对预期版本 → 解析引用闭包 → 全历史兼容检查 → 分配并写入版本；任一步失败整体回滚，不留半批、不产生版本号空洞
- 请求体上限 2 MiB；错误按 `400 INVALID_*` / `422 INCOMPATIBLE_SCHEMA` / `409 VERSION_CONFLICT` 明确区分

## 目录结构

```
src/main/java/dev/contracts/registry/
├── RegistryApplication.java        # 启动入口（Javalin + SQLite）
├── domain/                         # 领域模型：发布条目、引用、已注册版本、兼容模式
├── schema/
│   ├── SchemaValidator.java        # Avro 解析、默认值校验、logicalType 拒绝
│   └── ReferenceResolver.java      # 引用闭包、名称冲突、跨主题循环、拓扑解析
├── compat/
│   └── SchemaCompatibilityService.java  # Avro BACKWARD/FORWARD/FULL 全历史检查
├── storage/
│   └── SqliteSchemaRepository.java # 事务仓储（IMMEDIATE 事务 + 精确版本关系）
├── service/
│   └── RegistryService.java        # 预检/发布编排：版本核对→解析→兼容→写入
└── http/
    ├── RegistryController.java     # 路由、2MiB 限制、错误码映射
    ├── Requests.java / HttpJson.java
src/test/java/...                   # 10 个 JUnit 自测（业务 8 + HTTP 2）
```

## 构建与启动

需要 JDK 17、Maven 3.9+（依赖已在仓库 `.m2/` 内，Maven 通过 `.mvn/maven.config` 使用本地仓库）。

```bash
mvn test                 # 编译并运行自测
mvn package -DskipTests  # 构建 target/avro-contract-registry-0.1.0.jar

# 方式一：exec 插件
mvn exec:java -Ddb=data/registry.db -Dport=8080

# 方式二：jar + classpath
mvn dependency:build-classpath -Dmdep.outputFile=target/cp.txt
java -cp "target/avro-contract-registry-0.1.0.jar:$(cat target/cp.txt)" \
     -Ddb=data/registry.db -Dport=8080 \
     dev.contracts.registry.RegistryApplication
```

数据库路径默认 `data/registry.db`（可用 `-Ddb` 或环境变量 `REGISTRY_DB` 覆盖），端口默认 `8080`（`-Dport` 或 `PORT`）。首次启动自动建表。

## HTTP 协议

所有请求/响应均为 JSON。Schema 直接以 JSON 对象放在 `schema` 字段。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/health` | 健康检查 |
| GET | `/subjects` | 列出全部主题 |
| GET | `/subjects/{subject}/versions` | 列出该主题全部版本号及最新版本 |
| GET | `/subjects/{subject}/versions/{version}` | 读取不可变版本（含原始 schema、兼容模式、精确引用关系） |
| POST | `/compatibility/precheck` | 批量预检，不写入任何数据，返回将会分配的版本号 |
| POST | `/compatibility/publish` | 事务化批量发布，成功返回 `201` 与分配版本 |

### 发布/预检请求体

```json
{
  "entries": [
    {
      "subject": "people",
      "expectedLatest": 0,
      "compatibility": "BACKWARD",
      "schema": {
        "type": "record", "name": "people.Person",
        "fields": [
          {"name": "id", "type": "long"},
          {"name": "home", "type": "shared.Address"}
        ]
      },
      "references": [
        {"name": "shared.Address", "subject": "addresses"}
      ]
    }
  ]
}
```

- `expectedLatest`：调用方认为该主题当前最新版本；新主题填 `0`。与服务端不一致时返回 `409 VERSION_CONFLICT`。
- `compatibility`：`BACKWARD`（新 reader 读旧 writer）、`FORWARD`（旧 reader 读新 writer）、`FULL`（双向）。按**该主题全部历史版本**检查。
- `references[].version`：
  - 省略 → 引用**同批**正在发布的主题（该主题必须出现在本批，且每批只出现一次）；
  - 填整数 → 引用其他主题的**固定历史版本**；发布时把实际版本关系原样存下，未来读取历史版本时仍按该闭包解析。
- `references[].name` 必须等于被引用主题对应版本的根全限定名。

### 响应与错误

成功发布：`201 {"status":"CREATED","assigned":[{"subject":"people","version":1}]}`
预检通过：`200 {"status":"PASS","wouldAssign":[...]}`（不写库）

错误体统一为：`{"error": <code>, "status": <httpStatus>, "message": <详情>}`

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_REQUEST` | 请求结构/批次规则错误（重复主题、空主题、版本号非法、缺失引用、跨主题循环、名称冲突、请求体超 2 MiB 等） |
| 400 | `INVALID_SCHEMA` | Avro 语法错误、默认值非法、使用 `logicalType`、引用根名不匹配 |
| 422 | `INCOMPATIBLE_SCHEMA` | 兼容检查失败；message 列出 `subject / historicalVersion / direction / path / reason` |
| 409 | `VERSION_CONFLICT` | `expectedLatest` 与当前最新版本不符（含并发发布竞争失败方） |
| 404 | `SUBJECT_NOT_FOUND` / `VERSION_NOT_FOUND` | 读取不存在的主题/版本 |

兼容失败示例：

```
422 INCOMPATIBLE_SCHEMA
schema compatibility check failed (1):
 - subject='people' historicalVersion=1 direction=BACKWARD path=/fields/2
   reason=READER_FIELD_MISSING_DEFAULT_VALUE: age
```

## curl 流程示例

```bash
# 1) 预检（不写入）
curl -s -X POST localhost:8080/compatibility/precheck \
  -H 'Content-Type: application/json' -d @batch.json

# 2) 同批发布 addresses + people（people 以批引用方式使用 shared.Address）
curl -s -X POST localhost:8080/compatibility/publish \
  -H 'Content-Type: application/json' -d @batch.json

# 3) 列出/读取不可变版本
curl -s localhost:8080/subjects
curl -s localhost:8080/subjects/people/versions
curl -s localhost:8080/subjects/people/versions/1

# 4) 发布 addresses v2 后，people v2 仍可显式钉住 addresses v1
#    references: [{"name":"shared.Address","subject":"addresses","version":1}]

# 5) 典型拒绝
# - 非法默认值 / logicalType / 缺失引用 / 跨主题循环 -> 400
# - 新增强制字段（BACKWARD）                         -> 422，带主题/历史版本/方向/路径
# - expectedLatest 过期（或并发竞争败北）             -> 409
```

## 兼容语义说明

- 历史版本按其保存的**精确引用闭包**重新解析参与检查，依赖主题发布新版本不改变历史检查含义。
- 跨主题引用必须是无环的；同一 Schema 内部的命名递归（如树形结构）由 Avro 原生支持，合法。
- 兼容检查使用 Avro 1.12 的 `SchemaCompatibility.checkReaderWriterCompatibility`，`BACKWARD=check(新reader, 旧writer)`，`FORWARD=check(旧reader, 新writer)`。
