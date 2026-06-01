## ADDED Requirements

### Requirement: 定时通知调度执行
系统 SHALL 在每天指定时间（可配置）自动执行定时通知扫描。

#### Scenario: 默认时间执行
- **WHEN** 系统时间到达配置的 notify-time（默认 08:00）
- **THEN** 定时通知调度器启动执行

#### Scenario: tracker级时间配置
- **WHEN** tracker 配置了自定义 notify-time（如 09:00）
- **THEN** 该 tracker 的条目在配置时间执行通知

### Requirement: 停留天数计算
系统 SHALL 计算条目在当前状态的停留天数。

#### Scenario: 正常计算停留天数
- **WHEN** 条目有 enter_state_time 记录
- **THEN** 停留天数 = 当前日期 - enter_state_time 日期（取天数部分）

#### Scenario: 无enter_state_time记录
- **WHEN** 条目无 enter_state_time 记录（数据异常）
- **THEN** 跳过该条目，记录警告日志

### Requirement: 重复通知过滤
系统 SHALL 防止同一天对同一条目重复发送通知。

#### Scenario: 今日已发送通知
- **WHEN** 条目的 last_notify_time = 当前日期
- **THEN** 跳过该条目，不发送通知

#### Scenario: 今日未发送通知
- **WHEN** 条目的 last_notify_time ≠ 当前日期（或为空）
- **THEN** 正常处理该条目

### Requirement: 成员通知发送
系统 SHALL 按分类规则配置的间隔天数通知成员。

#### Scenario: 达到成员通知间隔
- **WHEN** 停留天数 % member-interval-days == 0 且 停留天数 >= member-interval-days
- **THEN** 向通知字段的所有成员逐人发送个人消息

#### Scenario: 多成员独立通知
- **WHEN** 通知字段包含多个成员（如张三、李四）
- **THEN** 张三、李四各自收到独立的个人消息

#### Scenario: 成员通知消息内容
- **WHEN** 发送成员通知
- **THEN** 消息格式为：【条目名】，在【状态】下已【X】天，负责人【成员名】

### Requirement: 科长升级通知
系统 SHALL 在停留天数达到科长升级天数时通知科长。

#### Scenario: 达到科长升级天数
- **WHEN** 停留天数 >= manager-escalate-days 且分类规则配置了科长升级
- **THEN** 向每个成员对应的科长发送通知

#### Scenario: 多成员多科长场景
- **WHEN** 条目通知字段包含张三（科长A）、李四（科长B）
- **THEN** 科长A收到包含张三负责条目的聚合消息，科长B收到包含李四负责条目的聚合消息

#### Scenario: 科长聚合消息格式
- **WHEN** 科长需要收到多个条目的通知
- **THEN** 消息格式为：您好，以下问题未及时处理，请知悉！【条目名】，在【状态】下已【X】天，负责人【成员名】...

#### Scenario: 同一科长多成员条目聚合
- **WHEN** 科长A负责张三和李四，两人都有条目需要通知
- **THEN** 科长A收到一条聚合消息，包含张三和李四负责的所有条目

#### Scenario: 成员无科长
- **WHEN** 成员在 org_cache 中无 manager_userid 记录
- **THEN** 跳过该成员的科长通知

### Requirement: 部长升级通知
系统 SHALL 在停留天数达到部长升级天数时通知部长。

#### Scenario: 达到部长升级天数
- **WHEN** 停留天数 >= director-escalate-days 且分类规则配置了部长升级（不为null）
- **THEN** 向每个成员对应的部长发送通知

#### Scenario: 多成员多部长场景
- **WHEN** 条目通知字段包含张三（部长A）、李四（部长B）
- **THEN** 部长A收到包含张三负责条目的聚合消息，部长B收到包含李四负责条目的聚合消息

#### Scenario: 无部长升级配置
- **WHEN** 分类规则的 director-escalate-days = null
- **THEN** 不发送部长通知

#### Scenario: 成员无部长
- **WHEN** 成员在 org_cache 中无 director_userid 记录
- **THEN** 跳过该成员的部长通知

### Requirement: 通知记录更新
系统 SHALL 在发送通知后更新条目状态记录。

#### Scenario: 更新last_notify_time
- **WHEN** 定时通知发送完成
- **THEN** 更新 item_state_record.last_notify_time = 当前时间

#### Scenario: 写入notify_log
- **WHEN** 定时通知发送完成
- **THEN** 写入 notify_log 表，notify_type = "定时成员"/"定时科长"/"定时部长"

#### Scenario: 多成员多日志记录
- **WHEN** 条目有多个成员收到通知
- **THEN** 每个成员的发送记录独立写入 notify_log

### Requirement: 定时通知开关控制
系统 SHALL 根据状态配置判断是否启用定时通知。

#### Scenario: 默认开启定时通知
- **WHEN** 状态配置了 notify-field 但未配置 scheduled-notify
- **THEN** 默认开启定时通知（scheduled-notify = true）

#### Scenario: 显式关闭定时通知
- **WHEN** 状态配置了 scheduled-notify: false
- **THEN** 该状态不执行定时通知，即时通知正常执行

### Requirement: 数据模型共享约束
系统 SHALL 与即时通知共享 item_state_record 表，避免重复存储。

#### Scenario: 定时通知只读取不插入
- **WHEN** 定时通知调度执行
- **THEN** 只读取和更新 item_state_record，不插入新记录

#### Scenario: 记录由即时通知创建
- **WHEN** 条目进入目标状态
- **THEN** 由即时通知（WorkflowNotifyService）创建 item_state_record 记录