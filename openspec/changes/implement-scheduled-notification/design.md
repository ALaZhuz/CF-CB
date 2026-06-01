## Context

当前系统已实现即时通知功能（需求1），当条目状态变更时触发通知。本次扩展需要在此基础上增加定时通知功能（需求2），每天定时扫描停留状态的条目并按规则升级通知。

### 现有基础设施

| 组件 | 已有功能 |
|------|----------|
| item_state_record 表 | 记录进入状态的条目（enter_state_time, last_notify_time） |
| notify_log 表 | 记录通知发送日志 |
| DingUserCacheService | userid缓存 + 定时刷新机制（每小时） |
| WorkflowConfigService | 四层优先级配置查询 |
| CBSwaggerServiceImpl | Codebeamer API调用（getItemInfo, query） |
| DingServiceImpl | 钉钉消息发送（企业/个人模式） |

### 约束条件

- 条目数量可能很大（数千），需要批量处理优化
- Codebeamer userid 与钉钉 userid 一致
- 部署在内网单台服务器，SQLite WAL模式支持并发
- 分类字段值为单值（不存在多值情况）

## Goals / Non-Goals

**Goals:**
- 实现每天定时（可配置时间）扫描停留状态条目
- 支持按分类字段配置不同的通知频率和升级规则
- 实现升级通知机制（成员 → 科长 → 部长）
- 科长/部长聚合消息发送
- 服务首次启动时全量初始化存量数据
- 提供按项目补录HTTP接口

**Non-Goals:**
- 不改变即时通知的核心逻辑（独立服务）
- 不实现实时配置推送（采用调度时检测变更）
- 不支持跨项目聚合（按收件人聚合，不区分项目）
- 不实现条目自动状态流转（仅通知，不操作条目）

## Decisions

### 1. YAML热更新方案

**决策：采用 config_meta 表 + 调度时检测**

**理由：**
- 定时通知每天执行一次，无需实时响应配置变更
- 与 initialized 标记功能合并，复用同一表
- 实现简单，无需额外线程监听
- 兼容所有部署方式（jar/war/容器）

**替代方案：FileWatcher**
- 实时响应，但需要独立线程
- 部署为jar包时外部配置文件路径可能变化
- IDE保存可能触发多次事件，需要防抖
- Windows/Linux文件系统行为差异

### 2. 科长/部长获取方案

**决策：采用 org_cache 本地缓存表 + 定时同步**

**理由：**
- 条目数量众多，每次调用钉钉API性能差
- 本地缓存避免API调用频率限制
- 与 DingUserCacheService 刷新时机同步（每小时）

**替代方案：实时调用钉钉API**
- 现有 queryOrganizationManager 方法已可用
- 但条目多时调用次数大，性能不可接受

### 3. 获取进入状态时间方案

**决策：调用 Codebeamer History API**

**API**: `GET /v3/items/{itemId}/history`

**解析逻辑**:
```
遍历 versions（从最新到最旧）
  检查 changes 中 field.name == "Status"
  找到 newValue.values[0].name == targetState 的版本
  返回 modifiedAt 作为 enter_state_time
```

**理由**：Codebeamer 提供完整历史记录，可精确获取状态切换时间

### 4. 消息聚合格式

**决策：纯文本聚合消息**

**格式**:
```
您好，以下问题未及时处理，请知悉！
【条目名】，在【状态】下已【X】天，负责人【成员名】
【条目名】，在【状态】下已【X】天，负责人【成员名】
```

**理由**：
- 用户确认不需要跳转链接
- 纯文本简单可靠，避免卡片消息格式问题
- sendTextMessage 方法已支持

### 5. 定时通知开关默认值

**决策：默认开启**

**逻辑**:
- 配置 notify-field 的状态 → 默认 scheduled-notify: true
- 显式配置 scheduled-notify: false → 关闭定时通知

**理由**：大多数需要即时通知的状态也需要定时提醒

### 6. notify-time 配置层级

**决策：tracker 级配置**

**层级**:
```
tracker级 notify-time > 全局 default-notify-time
```

**理由**：同一 tracker 内条目统一时间通知，便于管理

### 7. 分类字段配置方案

**决策：类似 extra-fields，三级优先级**

**层级**:
```
tracker级 classify-field > 项目级 classify-field > 全局 classify-field
```

**字段值提取**：复用现有 getExtraFieldValue 逻辑

## Risks / Trade-offs

### [性能] 条目数量大时调度执行时间长

**风险**：数千条目逐条调用 getItemInfo 和 history API 耗时长

**缓解**：
- 批量查询优化：一次 query 获取多个条目基本信息
- 并行处理：使用线程池并行处理条目
- 分批写入：避免单次大量数据库写入

### [准确性] 组织架构缓存可能过期

**风险**：科长/部长关系变更后缓存未及时更新

**缓解**：
- 定时同步频率合理（每小时）
- 缓存未命中时实时查询补缓存
- 提供手动刷新接口

### [一致性] YAML变更检测时机延迟

**风险**：配置修改后第二天调度才生效

**缓解**：
- 接受此延迟（定时通知场景下不影响业务）
- 提供手动触发重载接口（运维需求）

### [初始化] History API调用次数多

**风险**：全量初始化时逐条调用 history API

**缓解**：
- 分批执行，避免一次性大量请求
- 可配置初始化并发度
- 已存在记录跳过（幂等）

## Migration Plan

### 1. 数据库迁移

```sql
-- 新建表（SQLiteConfig.initDatabase 扩展）
CREATE TABLE IF NOT EXISTS org_cache (
    userid TEXT PRIMARY KEY,
    manager_userid TEXT,
    director_userid TEXT,
    dept_id TEXT,
    last_sync_time DATETIME
);

CREATE TABLE IF NOT EXISTS config_meta (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    initialized BOOLEAN DEFAULT FALSE,
    yaml_modified_time DATETIME,
    last_loaded_time DATETIME
);

-- 初始插入 config_meta 记录
INSERT INTO config_meta (initialized, yaml_modified_time, last_loaded_time)
VALUES (FALSE, NULL, NULL);
```

### 2. 配置文件扩展

用户需在 workflow-config.yml 中添加：
- classify-config 配置块
- tracker级 notify-time 配置（可选）
- 状态级 scheduled-notify 配置（可选）

### 3. 部署流程

1. 部署新版本服务
2. 服务启动时检查 config_meta.initialized
3. 若未初始化 → 执行全量初始化
4. 初始化完成 → 标记 initialized = true
5. 定时通知调度器正常运行

### 4. 回滚策略

- 回滚服务到旧版本
- org_cache、config_meta 表不影响即时通知
- item_state_record 表数据保留（即时通知写入）
- 重新部署新版本后继续使用现有数据

## Open Questions

1. **notify-time 不同 tracker 时间不同时如何处理？**
   - 当前方案：每个 tracker 独立调度，或统一时间但按 tracker 分批处理
   - 建议：简化为全局统一时间（08:00），后续迭代支持 tracker 级配置

2. **科长/部长聚合消息是否需要区分项目？**
   - 当前方案：不区分，按收件人聚合所有项目条目
   - 用户反馈：暂不区分

3. **初始化并发度如何配置？**
   - 当前方案：固定线程池大小（如 10）
   - 建议：可配置，默认值根据服务器资源设定