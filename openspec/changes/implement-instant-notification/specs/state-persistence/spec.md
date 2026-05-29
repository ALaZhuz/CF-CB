## ADDED Requirements

### Requirement: 状态记录持久化（表1）
系统必须使用SQLite数据库持久化条目状态记录，支持定时通知调度器查询。

#### Scenario: 条目进入目标状态时写入记录
- **WHEN** 条目ID=12345进入状态"处理中"（有notify_field配置）
- **THEN** 系统写入item_state_record表，包含：
  - item_id="12345"
  - item_name="需求A"
  - tracker_id="111"
  - project_id="12345"
  - target_state="处理中"
  - enter_state_time=当前时间
  - last_notify_time=null

#### Scenario: 条目离开目标状态时删除记录
- **WHEN** 条目ID=12345从状态"处理中"切换到"已关闭"
- **AND** item_state_record表中存在item_id="12345"的记录
- **THEN** 系统删除该条记录

#### Scenario: 条目离开目标状态但记录不存在
- **WHEN** 条目ID=12345从状态"草稿"切换到"已关闭"
- **AND** item_state_record表中不存在item_id="12345"的记录
- **THEN** 系统不做任何操作，直接返回成功

### Requirement: 发送日志持久化（表2）
系统必须使用SQLite数据库记录每次钉钉通知发送结果。

#### Scenario: 即时通知发送成功时记录日志
- **WHEN** 条目ID=12345发送即时通知给成员张三(userid=123)
- **AND** 钉钉发送成功
- **THEN** 系统写入notify_log表，包含：
  - item_id="12345"
  - send_time=当前时间
  - receiver_userid="123"
  - notify_type="即时"
  - send_result="成功"

#### Scenario: 即时通知发送失败时记录日志
- **WHEN** 条目ID=12345发送即时通知给成员张三(userid=123)
- **AND** 钉钉发送失败，错误信息"userid不存在"
- **THEN** 系统写入notify_log表，包含：
  - item_id="12345"
  - send_time=当前时间
  - receiver_userid="123"
  - notify_type="即时"
  - send_result="失败: userid不存在"

### Requirement: SQLite数据库初始化
系统必须在启动时自动创建SQLite数据库和表结构。

#### Scenario: 数据库文件不存在时自动创建
- **WHEN** 启动时SQLite数据库文件data/notification.db不存在
- **THEN** 系统自动创建数据库文件
- **AND** 创建item_state_record表和notify_log表

#### Scenario: 数据库表不存在时自动创建
- **WHEN** 启动时SQLite数据库文件存在，但缺少notify_log表
- **THEN** 系统自动创建notify_log表

#### Scenario: 数据库和表都已存在
- **WHEN** 启动时SQLite数据库文件和表结构都已存在
- **THEN** 系统不做任何操作，继续启动

### Requirement: MyBatis Mapper接口
系统必须提供MyBatis Mapper接口访问SQLite数据库。

#### Scenario: ItemStateRecordMapper提供CRUD操作
- **WHEN** 系统需要操作item_state_record表
- **THEN** ItemStateRecordMapper提供以下方法：
  - insert(ItemStateRecord) - 插入记录
  - deleteByItemId(itemId) - 按item_id删除
  - selectByItemId(itemId) - 按item_id查询
  - selectAll() - 查询所有记录（定时通知用）

#### Scenario: NotifyLogMapper提供写入操作
- **WHEN** 系统需要记录发送日志
- **THEN** NotifyLogMapper提供以下方法：
  - insert(NotifyLog) - 插入日志记录
  - selectByItemId(itemId) - 按item_id查询历史日志