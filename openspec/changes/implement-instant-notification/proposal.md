## Why

当前系统已有Python实现的即时通知功能，但未完全符合需求文档规范。Python代码缺少关键的beforeEvent校验逻辑、状态记录持久化、以及三层优先级配置系统。需要将Python实现迁移到Java Spring Boot服务中，并补齐需求文档中规定的完整功能，以支持Codebeamer工作流状态变化时的自动化钉钉通知。

## What Changes

- **新增** beforeEvent同步校验接口：三项校验（通知字段非空、userid钉钉存在性、目标状态配置显式声明）
- **新增** afterEvent通知处理接口：进入状态时发送通知并持久化记录，离开状态时删除记录
- **新增** SQLite数据库持久化：状态记录表(item_state_record)和发送日志表(notify_log)
- **新增** 三层优先级配置系统：项目级 > 全局工作流 > 全局默认规则
- **新增** userid钉钉缓存机制：启动时全量验证，定期刷新，缓存未命中时实时查询
- **迁移** Codebeamer API客户端逻辑（从Python CodeBeamerClient到Java CBSwaggerServiceImpl补充方法）
- **迁移** 钉钉通知发送逻辑（从Python DingTalkNotifier到Java DingServiceImpl补充方法）
- **迁移** Tracker匹配逻辑（从Python TrackerMatcher到Java新建类）
- **支持** 两种钉钉通知模式：个人钉钉Webhook和企业钉钉API

## Capabilities

### New Capabilities

- `instant-notification`: 即时通知核心能力 - 包括beforeEvent校验和afterEvent通知处理
- `workflow-config`: 工作流配置管理 - 三层优先级YAML配置加载和合并
- `state-persistence`: 状态持久化 - SQLite存储条目状态记录和发送日志
- `userid-cache`: 用户缓存管理 - 钉钉userid有效性缓存和刷新机制

### Modified Capabilities

- `dingtalk-integration`: 扩展钉钉集成 - 新增纯文本消息发送和用户存在性校验方法
- `codebeamer-api`: 扩展Codebeamer API客户端 - 新增获取单个条目详情和Tracker状态列表方法

## Impact

- **新增文件**：约15个Java类（Controller、Service、Mapper、Entity、Config等）
- **修改文件**：CBSwaggerServiceImpl、DingServiceImpl、application.yml
- **新增配置**：workflow-config.yml（工作流三层配置）
- **新增依赖**：SQLite JDBC、MyBatis-Spring-Boot-Starter
- **新增数据库**：SQLite数据库文件（data/notification.db）
- **新增API端点**：
  - `POST /workflow/validate` - beforeEvent校验接口
  - `POST /workflow/notify` - afterEvent通知接口
- **Groovy脚本调用**：Codebeamer工作流脚本将调用上述两个HTTP接口