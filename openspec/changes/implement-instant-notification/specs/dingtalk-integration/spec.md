## ADDED Requirements

### Requirement: 发送纯文本消息
DingServiceImpl必须新增sendTextMessage方法，支持发送纯文本格式的钉钉消息。

#### Scenario: 企业钉钉发送纯文本消息
- **WHEN** 调用sendTextMessage(userIds="123,456", content="通知内容")
- **AND** 配置使用企业钉钉模式
- **THEN** 系统获取access_token
- **AND** 调用企业钉钉API发送text类型消息给userid列表

#### Scenario: 个人钉钉发送纯文本消息
- **WHEN** 调用sendTextMessage(userIds="123,456", content="通知内容")
- **AND** 配置使用个人钉钉模式
- **THEN** 系统调用个人钉钉Webhook发送text类型消息

### Requirement: 查询钉钉用户存在性
DingServiceImpl必须新增checkUserExists方法，支持查询用户userid在钉钉中是否存在。

#### Scenario: 查询企业钉钉用户存在性
- **WHEN** 调用checkUserExists(userid="123")
- **AND** 配置使用企业钉钉模式
- **THEN** 系统获取access_token
- **AND** 调用钉钉用户查询API确认userid存在性
- **AND** 返回true或false

### Requirement: 钉钉通知模式配置
系统必须支持配置钉钉通知模式（个人/企业）。

#### Scenario: 配置企业钉钉模式
- **WHEN** workflow-config.yml配置dingtalk.mode="enterprise"
- **THEN** 系统使用企业钉钉API发送消息
- **AND** 需要配置app_key、app_secret、agent_id

#### Scenario: 配置个人钉钉模式
- **WHEN** workflow-config.yml配置dingtalk.mode="personal"
- **THEN** 系统使用个人钉钉Webhook发送消息
- **AND** 需要配置webhook_url