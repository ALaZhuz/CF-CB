## MODIFIED Requirements

### Requirement: notify_log表notify_type扩展
notify_log 表的 notify_type 字段 SHALL 支持定时通知类型。

#### Scenario: 即时通知类型（原有）
- **WHEN** 即时通知发送记录
- **THEN** notify_type = "即时"

#### Scenario: 定时成员通知类型（新增）
- **WHEN** 定时通知向成员发送
- **THEN** notify_type = "定时成员"

#### Scenario: 定时科长通知类型（新增）
- **WHEN** 定时通知向科长发送
- **THEN** notify_type = "定时科长"

#### Scenario: 定时部长通知类型（新增）
- **WHEN** 定时通知向部长发送
- **THEN** notify_type = "定时部长"

## ADDED Requirements

### Requirement: item_state_record表共享约束
系统 SHALL 确保 item_state_record 表由即时通知和定时通知共享，避免重复存储。

#### Scenario: 即时通知创建记录
- **WHEN** 条目进入目标状态（afterEvent）
- **THEN** 即时通知服务插入 item_state_record 记录（enter_state_time = 当前时间）

#### Scenario: 定时通知更新记录
- **WHEN** 定时通知发送完成
- **THEN** 定时通知服务更新 last_notify_time，不插入新记录

#### Scenario: 初始化检查重复
- **WHEN** 初始化或补录发现 item_state_record 已存在该条目
- **THEN** 跳过写入，保留现有记录