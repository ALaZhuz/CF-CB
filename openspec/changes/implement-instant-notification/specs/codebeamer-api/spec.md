## ADDED Requirements

### Requirement: 获取单个条目详情
CBSwaggerServiceImpl必须新增getItemInfo方法，支持获取单个tracker item的完整信息。

#### Scenario: 获取条目详情成功
- **WHEN** 调用getItemInfo(itemId=12345)
- **AND** Codebeamer API返回条目信息
- **THEN** 返回包含以下信息的对象：
  - 条目名称(name)
  - 当前状态(status.name)
  - tracker信息(tracker.id, tracker.name)
  - tracker类型(typeName)
  - assignedTo成员列表
  - submitter信息
  - customFields自定义字段列表
  - commonItemId(用于构建链接)

#### Scenario: 条目不存在返回null
- **WHEN** 调用getItemInfo(itemId=99999)
- **AND** Codebeamer API返回404
- **THEN** 返回null

#### Scenario: 从条目信息中提取通知字段成员
- **WHEN** 获取条目详情后，需要提取"通知人"字段成员
- **AND** "通知人"是自定义字段
- **THEN** 从customFields中找到name="通知人"的字段
- **AND** 返回该字段的values列表（用户对象数组）

#### Scenario: 从条目信息中提取内置字段成员
- **WHEN** 获取条目详情后，需要提取assignedTo字段成员
- **THEN** 直接从条目的assignedTo字段返回用户对象数组

### Requirement: 获取Codebeamer用户列表
CBSwaggerServiceImpl必须新增getAllUsers方法，支持获取Codebeamer所有用户列表，用于userid缓存初始化。

#### Scenario: 获取所有用户成功
- **WHEN** 调用getAllUsers()
- **AND** Codebeamer API返回用户列表
- **THEN** 返回包含所有用户的列表，每个用户包含：
  - name (userid，与钉钉userid一致)
  - displayName

#### Scenario: 分页获取用户列表
- **WHEN** Codebeamer用户数量超过pageSize
- **THEN** 系统分页获取所有用户，直到最后一页