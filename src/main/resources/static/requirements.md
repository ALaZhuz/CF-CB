# Codebeamer 钉钉通知服务 — 需求文档

## 一、背景与目标

在 Codebeamer 内，根据 Tracker 条目的工作流状态变化，自动给钉钉上对应成员发送通知。系统由一个独立的 Spring Boot 服务统一处理所有通知逻辑，部署在内网服务器上。

---

## 二、技术栈

| 项目 | 选型 |
|------|------|
| 框架 | Spring Boot 3.x |
| 语言 | Java 17 |
| 数据库 | SQLite + MyBatis |
| 定时任务 | @Scheduled |
| HTTP 客户端 | RestTemplate |
| 配置绑定 | @ConfigurationProperties |
| JSON | Jackson |

---

## 三、系统约束

- Codebeamer 版本 3.1，工作流状态转换时可触发 Groovy 脚本（全局监听所有状态转换）
- Groovy 脚本只负责校验和调用外部服务，不直接操作钉钉
- Codebeamer userid 与钉钉 userid 完全一致，无需额外映射
- 部署在内网单台服务器上

---

## 四、配置文件结构（YAML）

### 4.1 三层优先级

```
项目特殊工作流配置
  > 全局工作流定义
  > 全局默认分类规则
```

越具体的配置优先级越高，未配置项自动继承上层默认值。

### 4.2 状态配置规则

配置文件中对每个 Tracker 的每个状态必须显式声明意图：

| 配置情况 | 系统行为 |
|---------|---------|
| 状态配置了 `notify_field`（有通知字段） | 需要通知，执行校验 |
| 状态配置了 `notify: false` | 明确不需要通知，直接放行 |
| 状态未在配置中出现 | 阻止保存，提示管理员补充配置 |

> 设计原则：需要通知的状态只需配置字段，不需要通知的状态必须显式写 `notify: false`，漏写的一律阻断，防止配置遗漏导致静默跳过通知。

### 4.3 配置文件完整结构

```yaml
# ========================
# 层级1：全局基础配置
# ========================
dingtalk:
  app_key: "xxx"
  app_secret: "xxx"
  env: "production"           # production / test

codebeamer:
  base_url: "http://cb.internal"
  username: "admin"
  password: "xxx"
  env: "production"           # production / test

cache:
  userid_refresh_interval: 3600    # userid缓存刷新周期（秒），默认1小时

log:
  level: "INFO"
  retry_count: 3

# ========================
# 层级2：全局工作流定义（可复用）
# ========================
global_workflows:
  - name: "标准Bug工作流"
    classify_field: "严重程度"       # 条目分类字段名
    states:
      - name: "处理中"
        notify_field: "通知人"       # 有此字段 = 需要即时通知
        scheduled_notify: true       # 是否开启定时通知
      - name: "已关闭"
        notify: false                # 明确不需要通知
      - name: "待审核"
        notify_field: "审核人"
        scheduled_notify: false      # 只要即时通知，不要定时通知
    classify_rules:                  # 分类通知规则（默认值）
      - category: "A类"
        member_interval_days: 1
        manager_escalate_days: 2
        director_escalate_days: 3
        notify_time: "08:00"
      - category: "B类"
        member_interval_days: 2
        manager_escalate_days: 3
        director_escalate_days: 5
        notify_time: "08:00"
      - category: "C类"
        member_interval_days: 3
        manager_escalate_days: 5
        director_escalate_days: null  # C类不通知部长
        notify_time: "08:00"
    message_template:
      fields:
        - "条目名称"
        - "条目状态"
        - "通知属性字段名:通知成员"
        - "严重程度"
        - "条目链接"

# ========================
# 层级3：项目级配置
# ========================
projects:
  - project_id: "12345"
    name: "项目A"
    codebeamer_env: "production"
    trackers:
      - tracker_id: "111"
        workflow: "标准Bug工作流"     # 引用全局工作流
      - tracker_id: "222"
        workflow: "标准Bug工作流"
        classify_rules:              # 覆盖全局分类规则
          - category: "A类"
            member_interval_days: 1
            manager_escalate_days: 1
            director_escalate_days: 2
            notify_time: "09:00"
        states:                      # 覆盖部分状态配置
          - name: "处理中"
            notify_field: "特殊通知人"
            scheduled_notify: true

  - project_id: "67890"
    name: "项目B"
    codebeamer_env: "test"
    trackers:
      - tracker_id: "333"
        workflow:                    # 定义特殊工作流（不复用全局）
          classify_field: "优先级"
          states:
            - name: "进行中"
              notify_field: "负责人"
              scheduled_notify: true
            - name: "完成"
              notify: false
          classify_rules:
            - category: "高"
              member_interval_days: 1
              manager_escalate_days: 2
              director_escalate_days: 3
              notify_time: "08:00"
```

---

## 五、数据库结构（SQLite）

### 表1：条目状态记录表 `item_state_record`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 主键 |
| item_id | TEXT | 条目ID |
| item_name | TEXT | 条目名称 |
| tracker_id | TEXT | Tracker ID |
| project_id | TEXT | 项目ID |
| target_state | TEXT | 目标状态名 |
| enter_state_time | DATETIME | 进入目标状态时间 |
| last_notify_time | DATETIME | 最后通知时间（防重复发送） |

> 条目进入目标状态时写入，离开目标状态时删除。定时通知调度器以此表为数据源，避免每天全量扫描 Codebeamer。

### 表2：发送日志表 `notify_log`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 主键 |
| item_id | TEXT | 条目ID |
| send_time | DATETIME | 实际发送时间 |
| receiver_userid | TEXT | 实际收件人userid |
| notify_type | TEXT | 即时 / 定时成员 / 定时科长 / 定时部长 |
| send_result | TEXT | 成功 / 失败原因 |

### 表3：组织架构缓存表 `org_cache`

| 字段 | 类型 | 说明 |
|------|------|------|
| userid | TEXT PK | 用户ID |
| manager_userid | TEXT | 科长userid |
| director_userid | TEXT | 部长userid |
| dept_id | TEXT | 部门ID |
| last_sync_time | DATETIME | 最后同步时间 |

### 表4：配置元数据表 `config_meta`

| 字段 | 类型 | 说明 |
|------|------|------|
| id | INTEGER PK | 主键 |
| yaml_modified_time | DATETIME | YAML文件修改时间 |
| last_loaded_time | DATETIME | 最后加载时间 |
| initialized | BOOLEAN | 全量初始化完成标记 |

---

## 六、内存缓存

### userid 有效性缓存

- 服务启动时：遍历 Codebeamer 所有用户，逐一验证钉钉 userid 是否存在，构建内存 Set
- 按配置周期定期全量刷新（默认1小时）
- 校验时缓存未命中：实时查一次钉钉确认，防止缓存过期误拦截

---

## 七、模块划分

| 模块 | 职责 |
|------|------|
| Webhook 接收模块 | 对外暴露 HTTP 接口，接收 Groovy 调用，同步返回校验结果 |
| 校验模块 | 依次执行三项校验，任意失败返回具体原因 |
| 即时通知处理器 | 查条目详情、构建消息、逐人发送、写日志 |
| 定时通知处理器 | 查表1、计算停留天数、判断层级、聚合发送、写日志 |
| 配置加载模块 | 启动时解析 YAML，三层优先级合并，支持热更新检测 |
| 钉钉客户端模块 | 封装所有钉钉 API：发消息、查userid、查科长/部长 |
| 状态记录模块 | 封装表1/表2读写 |
| Codebeamer API 客户端 | 查条目详情、查用户列表、查历史记录 |
| 初始化模块 | 全量初始化扫描 + 按项目粒度补录 HTTP 接口 |

---

## 八、需求1：即时通知

### 8.1 触发时机

用户在 Codebeamer 做状态切换并点击保存时，Groovy 脚本触发（全局监听所有状态转换）。

### 8.2 Groovy beforeEvent（同步阻断）

**步骤：**

1. 调用外部服务校验接口，传入条目 ID + 目标状态
2. 外部服务查配置，判断目标状态的意图：
   - 目标状态配置了 `notify_field` → 执行三项校验
   - 目标状态配置了 `notify: false` → 直接返回成功，放行
   - 目标状态未在配置中出现 → 返回错误，阻止保存，提示管理员补充配置
3. 三项校验（均通过才放行）：
   - 校验①：通知属性字段内有成员填写（非空）
   - 校验②：所有成员的 userid 在钉钉缓存中存在（缓存未命中则实时查钉钉）
4. 任意校验失败 → 返回具体错误原因 → Groovy 阻止保存，提示用户
5. 全部通过 → 返回成功 → Groovy 放行保存

### 8.3 Groovy afterEvent（状态切换成功后）

**情况1：条目进入目标状态（目标状态有 `notify_field` 配置）**

1. 调用外部服务通知接口，传入条目 ID
2. 查询 Codebeamer API 获取条目详情：
   - 条目名称、当前状态、分类字段值、通知字段成员列表、条目链接
3. 格式化消息内容：
   ```
   条目名称：xxx
   条目状态：xxx
   [通知属性字段名]：成员1、成员2
   严重程度：xxx
   条目链接：xxx
   ```
4. 逐人发送钉钉个人通知
5. 写入表1（enter_state_time = 当前时间）
6. 写入表2发送日志

**情况2：条目离开目标状态（转换前的状态在表1中有记录）**

1. 查表1，若该条目存在记录 → 删除该条目记录
2. 停止后续定时通知

**情况3：其他状态之间互转（配置为 `notify: false` 或两端都不在配置中触发通知）**

- 不做任何处理，直接跳过

---

## 九、需求2：定时通知

### 9.1 触发时机

每天默认 08:00 触发，发送时间可在配置文件中按 Tracker 粒度覆盖。

### 9.2 默认分类通知规则

| 分类 | 成员通知间隔 | 科长升级天数 | 部长升级天数 |
|------|------------|------------|------------|
| A类 | 每1天 | 第2天起每天 | 第3天起每天 |
| B类 | 每2天 | 第3天起每天 | 第5天起每天 |
| C类 | 每3天 | 第5天起每天 | 不通知部长 |

> 以上为默认值，均可在配置文件中覆盖。

### 9.3 调度器执行流程

1. 对比 YAML 文件修改时间与表4记录，若有变更则重载配置
2. 查表1，获取所有当前需跟踪的条目
3. 按条目 ID 逐条查询 Codebeamer API 获取最新详情
4. 对每个条目：
   - 计算停留天数 = 当前日期 - `enter_state_time`
   - 判断 `last_notify_time` 是否为今天 → 是则跳过，防止重复发送
   - 实时读取当前通知字段成员列表
   - 按条目分类字段值匹配对应分类规则
   - 根据停留天数判断本次通知层级（成员 / 科长 / 部长，可同时触发多层）
5. 成员通知：逐人发送个人消息
6. 科长/部长通知：
   - 查表3获取每个成员对应的科长/部长
   - 同一收件人的多个条目合并为一条卡片消息
   - 不按项目拆分，统一发送
7. 更新 `last_notify_time`，写入表2发送日志（记录实际发送的成员列表）

### 9.4 停止条件

条目离开目标状态时，由 afterEvent 触发删除表1记录，调度器下次运行时自动不再处理该条目。

---

## 十、初始化与补录

### 10.1 全量初始化（服务首次启动）

1. 启动时检查表4 `initialized` 字段
2. 若未初始化：
   - 逐项目 → 逐 Tracker → 按目标状态过滤，查询 Codebeamer API 获取存量条目列表
   - 对每个条目调用历史记录 API，取最近一次切换到目标状态的时间作为 `enter_state_time`
   - 批量写入表1
   - 写表4，标记 `initialized = true`
3. 初始化完成后服务才对外提供接口

### 10.2 按项目补录（HTTP 接口）

**接口：** `POST /admin/补录/{projectId}`

**流程：**
1. 查该项目配置的所有 Tracker 和目标状态
2. 查询 Codebeamer API 获取该项目存量条目
3. 对每个条目查历史记录 API，取进入目标状态时间
4. 表1中已存在的条目跳过，只补录缺失的
5. 返回补录结果（成功几条、跳过几条）

**适用场景：** 管理员新增项目配置后，对该项目存量条目进行补录，无需重启服务或重跑全量初始化。

---

## 十一、钉钉 userid 缓存机制

| 阶段 | 行为 |
|------|------|
| 服务启动时 | 遍历 Codebeamer 所有用户，逐一验证钉钉 userid 是否存在，构建内存 Set |
| 定期刷新 | 按配置周期全量同步（默认1小时） |
| 校验时缓存未命中 | 实时查一次钉钉确认，防止缓存过期导致误拦截 |
| 组织架构同步 | 定时从钉钉同步科长/部长关系，持久化到表3 |

---

## 十二、流程图

### 12.1 即时通知流程

```
用户点击保存（状态 A→B）
  │
  ▼
Groovy beforeEvent 触发
  │ 调用外部服务校验接口（同步）
  ▼
查配置：目标状态意图判断
  ├─ notify: false ──────────────────→ 直接放行
  ├─ 未在配置中出现 ─────────────────→ 阻止保存，提示补充配置
  └─ 有 notify_field 配置
        │
        ▼
      校验①：通知字段是否有成员填写
        ├─ 为空 ──→ 阻止保存，提示填写成员
        └─ 非空
             │
             ▼
           校验②：成员 userid 在钉钉中是否存在
           （缓存未命中则实时查钉钉）
             ├─ 不存在 ──→ 阻止保存，提示 userid 有误
             └─ 全部存在
                  │
                  ▼
                返回成功，Groovy 放行保存
                  │
                  ▼
                状态切换成功
                  │
                  ▼
                Groovy afterEvent 触发
                  │ 调用外部服务通知接口（传入条目ID）
                  ▼
                查询 Codebeamer API 获取条目详情
                （名称 / 状态 / 分类字段值 / 通知字段成员 / 链接）
                  │
                  ▼
                格式化消息内容
                  │
                  ▼
                逐人发送钉钉个人通知
                  │
                  ▼
                写表1（进入状态时间）+ 写表2（发送日志）
```

### 12.2 定时通知流程

```
每天 08:00 调度器触发
  │
  ▼
检测 YAML 是否变更，若变更则重载配置
  │
  ▼
查表1，获取所有当前需跟踪的条目
  │
  ▼
按条目ID逐条查询 Codebeamer API 获取最新详情
  │
  ▼
遍历每个条目：
  │
  ├─ 计算停留天数 = 今天 - enter_state_time
  │
  ├─ last_notify_time == 今天？
  │     └─ 是 ──→ 跳过
  │
  ├─ 实时读取当前通知字段成员列表
  │
  ├─ 按分类字段值匹配分类规则
  │
  └─ 停留天数判断通知层级：
        ├─ 达到成员通知间隔 ──→ 逐人发送个人消息
        ├─ 达到科长升级天数 ──→ 查表3取科长，按科长聚合发送卡片
        └─ 达到部长升级天数 ──→ 查表3取部长，按部长聚合发送卡片
  │
  ▼
更新 last_notify_time，写表2发送日志

─────────────────────────────
条目离开目标状态时（afterEvent）
  │
  ▼
查表1，若存在该条目记录 ──→ 删除 ──→ 停止定时通知
```

### 12.3 初始化与补录流程

```
服务启动
  │
  ▼
检查表4 initialized 标记
  ├─ 已初始化 ──→ 启动 userid 缓存 ──→ 对外提供服务
  └─ 未初始化
        │
        ▼
      逐项目 → 逐Tracker → 按目标状态过滤
      查询 Codebeamer API 获取存量条目
        │
        ▼
      逐条查历史记录 API，取进入目标状态时间
        │
        ▼
      批量写入表1
        │
        ▼
      写表4，标记 initialized = true
        │
        ▼
      启动 userid 缓存 ──→ 对外提供服务

─────────────────────────────
按项目补录接口 POST /admin/补录/{projectId}
  │
  ▼
查该项目配置的 Tracker 和目标状态
  │
  ▼
查 Codebeamer API 获取存量条目
  │
  ▼
逐条查历史记录 API，取进入目标状态时间
  │
  ▼
表1已存在的条目跳过，补录缺失条目
  │
  ▼
返回结果（成功N条，跳过N条）
```

