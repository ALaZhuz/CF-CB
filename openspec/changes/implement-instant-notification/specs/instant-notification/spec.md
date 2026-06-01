## ADDED Requirements

### Requirement: beforeEvent校验接口
系统必须提供beforeEvent校验接口，接收Codebeamer Groovy脚本调用，执行三项校验后同步返回结果。

#### Scenario: 目标状态配置了notify_field，校验全部通过
- **WHEN** 条目ID为12345，目标状态为"处理中"，该状态配置了notify_field为"通知人"
- **AND** 条目的"通知人"字段填写了成员张三、李四
- **AND** 张三、李四的userid在钉钉缓存中均存在
- **THEN** 系统返回校验成功，Groovy放行保存

#### Scenario: 目标状态配置了notify_field，通知字段为空
- **WHEN** 条目ID为12345，目标状态为"处理中"，该状态配置了notify_field为"通知人"
- **AND** 条目的"通知人"字段为空（无成员填写）
- **THEN** 系统返回校验失败，错误信息："通知字段[通知人]未填写成员，请先填写后再保存"
- **AND** Groovy阻止保存并提示用户

#### Scenario: 目标状态配置了notify_field，成员userid不存在于钉钉
- **WHEN** 条目ID为12345，目标状态为"处理中"，该状态配置了notify_field为"通知人"
- **AND** 条目的"通知人"字段填写了成员张三
- **AND** 张三的userid在钉钉缓存中不存在，实时查询钉钉API也不存在
- **THEN** 系统返回校验失败，错误信息："成员[张三]的userid在钉钉中不存在，请检查用户配置"
- **AND** Groovy阻止保存并提示用户

#### Scenario: 目标状态配置了notify: false
- **WHEN** 条目ID为12345，目标状态为"已关闭"，该状态配置了notify: false
- **THEN** 系统跳过校验，直接返回成功，Groovy放行保存

#### Scenario: 目标状态未在配置中出现
- **WHEN** 条目ID为12345，目标状态为"待定"，该状态未在任何配置中出现
- **THEN** 系统返回校验失败，错误信息："目标状态[待定]未配置通知规则，请联系管理员补充配置"
- **AND** Groovy阻止保存并提示用户

### Requirement: afterEvent通知接口
系统必须提供afterEvent通知接口，在状态切换成功后处理通知逻辑。

#### Scenario: 条目进入目标状态（有notify_field配置）
- **WHEN** 条目ID为12345，从状态"新建"切换到"处理中"
- **AND** "处理中"状态配置了notify_field为"通知人"
- **THEN** 系统查询Codebeamer API获取条目详情
- **AND** 格式化消息内容
- **AND** 逐人发送钉钉个人通知
- **AND** 写入表1状态记录（enter_state_time = 当前时间）
- **AND** 写入表2发送日志

#### Scenario: 条目离开目标状态（转换前状态在表1有记录）
- **WHEN** 条目ID为12345，从状态"处理中"切换到"已关闭"
- **AND** 表1中存在该条目的记录（"处理中"状态）
- **THEN** 系统删除表1中该条目记录
- **AND** 后续定时通知不再处理该条目

#### Scenario: 条目在不需要通知的状态之间互转
- **WHEN** 条目ID为12345，从状态"草稿"切换到"已关闭"
- **AND** 两个状态都配置了notify: false或都不在配置中触发通知
- **THEN** 系统不做任何处理，直接返回成功

### Requirement: 消息格式化
系统必须按配置的消息模板格式化钉钉通知内容。

#### Scenario: 格式化标准消息
- **WHEN** 条目名称为"需求A"，状态为"处理中"，通知字段名为"通知人"，通知成员为"张三、李四"
- **AND** 条目链接为"http://cb/issue/12345"
- **THEN** 系统生成消息内容包含：条目名称、条目状态、通知属性字段名、通知成员、条目链接

### Requirement: 双模式钉钉通知
系统必须支持个人钉钉Webhook和企业钉钉API两种通知模式。

#### Scenario: 使用个人钉钉模式发送通知
- **WHEN** 配置dingtalk.mode为"personal"
- **AND** 需要发送通知给成员张三、李四
- **THEN** 系统调用个人钉钉Webhook发送文本消息

#### Scenario: 使用企业钉钉模式发送通知
- **WHEN** 配置dingtalk.mode为"enterprise"
- **AND** 需要发送通知给成员张三(userid=123)、李四(userid=456)
- **THEN** 系统获取企业钉钉access_token
- **AND** 调用企业钉钉API发送消息给userid列表"123,456"