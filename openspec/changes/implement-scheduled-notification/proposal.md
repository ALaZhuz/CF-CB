## Why

当前的即时通知系统仅在条目状态变更时发送一次性通知，但对于长期停留在需要处理状态的条目，缺乏持续的提醒机制。这导致：
- 条目长时间停留在待处理状态未被发现
- 无法按严重程度分级升级通知（成员 → 科长 → 部长）
- 管理层无法及时掌握滞留问题的整体情况

定时通知功能解决了这些问题，通过每天定时扫描并按规则升级通知，确保问题得到持续关注和处理。

## What Changes

### 新增功能

- **定时通知调度器**：每天指定时间（默认08:00）扫描所有停留在通知状态的条目
- **分类规则配置**：支持按条目字段值（如严重程度、优先级）配置不同的通知频率和升级规则
- **升级通知机制**：根据停留天数自动升级通知层级（成员 → 科长 → 部长）
- **聚合消息发送**：科长/部长收到聚合卡片消息，汇总所有待处理条目
- **组织架构缓存**：缓存员工与科长/部长的关系，减少钉钉API调用
- **YAML热更新检测**：调度执行时自动检测配置变更并重载
- **全量初始化功能**：服务首次启动时扫描存量条目并补录状态记录
- **按项目补录接口**：管理员可手动触发项目级存量数据补录

### 配置扩展

- **workflow-config.yml 新增配置块**：
  - `classify-config`：分类字段和分类规则配置
  - `scheduled-notify`：状态级定时通知开关
  - `notify-time`：tracker级通知时间配置

### 数据库新增

- **org_cache 表**：组织架构缓存（userid → manager/director）
- **config_meta 表**：配置元数据（初始化标记、YAML修改时间）

### API新增

- **GET /v3/items/{itemId}/history**：查询条目状态变更历史，用于初始化获取进入状态时间

## Capabilities

### New Capabilities

- `scheduled-notification`: 定时通知核心功能 - 调度器执行、停留天数计算、升级通知判断、聚合消息发送
- `classify-rules`: 分类规则配置 - classify-field配置、分类匹配、通知频率规则
- `org-cache`: 组织架构缓存 - 科长/部长关系缓存、定时同步
- `config-reload`: YAML热更新检测 - 文件修改时间检测、配置重载
- `initialization`: 初始化补录 - 全量初始化、按项目补录接口

### Modified Capabilities

- `workflow-config`: 扩展配置结构 - 新增 classify-config、scheduled-notify、notify-time 字段
- `instant-notification`: 共享数据模型 - notify_log 表增加定时通知类型记录

## Impact

### 新增组件

| 组件 | 文件路径 |
|------|----------|
| ScheduledNotifyService | `workflow/service/ScheduledNotifyService.java` |
| OrgCacheService | `workflow/cache/OrgCacheService.java` |
| ConfigMetaService | `workflow/config/ConfigMetaService.java` |
| InitService | `workflow/service/InitService.java` |
| ScheduledNotifyController | `workflow/controller/ScheduledNotifyController.java` |
| ClassifyConfig | `workflow/config/ClassifyConfig.java` |
| ClassifyRule | `workflow/config/ClassifyRule.java` |
| OrgCacheMapper | `db/mapper/OrgCacheMapper.java` |
| ConfigMetaMapper | `db/mapper/ConfigMetaMapper.java` |
| CBHistoryResponse | `model/dto/response/CBHistoryResponse.java` |

### 修改组件

| 组件 | 修改内容 |
|------|----------|
| WorkflowProperties | 新增 classify-config 配置绑定 |
| WorkflowTemplate.StateConfig | 新增 scheduled-notify、notify-time 字段 |
| SQLiteConfig | 新建 org_cache、config_meta 表 |
| CBSwaggerService | 新增 getItemHistory 方法 |
| DingService | 新增 sendAggregatedMessage 方法 |
| DingProperties | 新增 default-notify-time 配置 |

### 共享资源

- `item_state_record` 表：即时通知写入，定时通知读取
- `notify_log` 表：通知类型扩展（即时/定时成员/定时科长/定时部长）