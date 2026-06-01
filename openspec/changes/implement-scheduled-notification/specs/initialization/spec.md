## ADDED Requirements

### Requirement: 全量初始化执行
系统 SHALL 在首次启动时初始化所有存量条目的状态记录。

#### Scenario: 检查初始化标记
- **WHEN** 服务启动时 config_meta.initialized = false
- **THEN** 执行全量初始化流程

#### Scenario: 遍历配置的tracker
- **WHEN** 执行初始化
- **THEN** 遍历 workflow-config.yml 中所有配置了定时通知的 tracker 和目标状态

#### Scenario: 查询存量条目
- **WHEN** 找到需要初始化的 tracker 和状态
- **THEN** 使用 cbQL 查询该项目 tracker 下状态 = 目标状态的所有条目

#### Scenario: 获取进入状态时间
- **WHEN** 条目 ID 已获取
- **THEN** 调用 GET /v3/items/{itemId}/history，找最后一次状态切换到目标状态的 modifiedAt

#### Scenario: 写入状态记录（检查重复）
- **WHEN** 获取到 enter_state_time 且条目不在 item_state_record 表中
- **THEN** 写入 item_state_record 表

#### Scenario: 跳过已存在记录
- **WHEN** 条目在 item_state_record 表中已存在（即时通知已创建）
- **THEN** 跳过该条目，不重复写入

#### Scenario: 标记初始化完成
- **WHEN** 所有条目处理完成
- **THEN** 更新 config_meta.initialized = true

### Requirement: 数据模型共享约束
系统 SHALL 与即时通知共享 item_state_record 表，确保数据一致性。

#### Scenario: 表结构共享
- **WHEN** 定时通知和即时通知共用 item_state_record 表
- **THEN** 表结构不扩展，定时通知复用现有字段（enter_state_time、last_notify_time）

#### Scenario: 写入时机区分
- **WHEN** 条目进入目标状态
- **THEN** 由即时通知创建记录，定时通知只更新 last_notify_time

#### Scenario: 初始化补录幂等性
- **WHEN** 初始化或补录发现已存在记录
- **THEN** 跳过写入，保持现有记录不变

### Requirement: 按项目补录接口
系统 SHALL 提供按项目补录存量条目的 HTTP 接口。

#### Scenario: 补录接口请求
- **WHEN** POST /admin/补录/{projectId}
- **THEN** 执行该项目的存量条目补录

#### Scenario: 查询项目配置
- **WHEN** 补录执行
- **THEN** 查询该项目配置的所有 tracker 和目标状态

#### Scenario: 补录跳过已存在记录
- **WHEN** 条目在 item_state_record 中已存在
- **THEN** 跳过该条目，计入跳过数量

#### Scenario: 补录结果返回
- **WHEN** 补录完成
- **THEN** 返回成功补录数量、跳过数量、失败数量

### Requirement: 历史记录API调用
系统 SHALL 调用 Codebeamer History API 获取状态变更历史。

#### Scenario: History API请求
- **WHEN** 需要获取条目进入状态时间
- **THEN** 调用 GET /v3/items/{itemId}/history

#### Scenario: 解析状态变更记录
- **WHEN** 获取到 history 响应
- **THEN** 遍历 versions（从最新到最旧），找 changes 中 field.name = "Status" 且 newValue = 目标状态的版本

#### Scenario: 提取变更时间
- **WHEN** 找到目标状态变更记录
- **THEN** 返回 modifiedAt 作为 enter_state_time

#### Scenario: 无状态变更记录
- **WHEN** history 中无目标状态的变更记录
- **THEN** 使用当前时间作为 enter_state_time（兜底）

### Requirement: 手动触发初始化接口
系统 SHALL 提供手动触发初始化的 HTTP 接口。

#### Scenario: 手动触发请求
- **WHEN** POST /admin/init
- **THEN** 强制执行全量初始化（忽略 initialized 标记）

#### Scenario: 重置初始化标记
- **WHEN** 手动触发初始化
- **THEN** 先设置 initialized = false，再执行初始化

#### Scenario: 手动触发仍检查重复
- **WHEN** 手动触发初始化发现已存在记录
- **THEN** 跳过写入，不覆盖现有记录