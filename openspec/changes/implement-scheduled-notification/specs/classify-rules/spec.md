## ADDED Requirements

### Requirement: 分类字段配置
系统 SHALL 支持配置分类字段用于匹配分类规则。

#### Scenario: 全局分类字段
- **WHEN** 配置了 classify-config.global.classify-field（如 "severities"）
- **THEN** 所有项目默认使用该字段提取分类值

#### Scenario: 项目级分类字段覆盖
- **WHEN** 项目配置了 classify-config.projects.{projectId}.classify-field
- **THEN** 该项目使用项目级配置，覆盖全局配置

#### Scenario: tracker级分类字段覆盖
- **WHEN** tracker 配置了 classify-field
- **THEN** 该 tracker 使用 tracker 级配置，优先级最高

### Requirement: 分类字段值提取
系统 SHALL 从条目信息中提取分类字段值。

#### Scenario: 内置字段提取
- **WHEN** classify-field = "severities"、"priority"、"categories" 等内置字段
- **THEN** 从 ItemInfoResponse 对应字段提取第一个选项的 name

#### Scenario: 自定义字段提取
- **WHEN** classify-field 为自定义字段名
- **THEN** 从 customFields 中查找匹配字段，提取 value 或 values[0].name

#### Scenario: 字段值不存在
- **WHEN** 分类字段在条目中不存在或值为空
- **THEN** 使用 default-category 作为分类

### Requirement: 分类规则配置
系统 SHALL 支持配置每个分类的通知规则。

#### Scenario: 成员通知间隔配置
- **WHEN** 分类规则配置了 member-interval-days = N
- **THEN** 每 N 天向成员发送一次通知

#### Scenario: 科长升级天数配置
- **WHEN** 分类规则配置了 manager-escalate-days = N
- **THEN** 停留天数 >= N 时向科长发送通知

#### Scenario: 部长升级天数配置
- **WHEN** 分类规则配置了 director-escalate-days = N
- **THEN** 停留天数 >= N 时向部长发送通知

#### Scenario: 部长升级天数为null
- **WHEN** 分类规则配置了 director-escalate-days = null
- **THEN** 该分类不触发部长通知

### Requirement: 分类值匹配
系统 SHALL 根据分类字段值匹配对应的分类规则。

#### Scenario: 匹配成功
- **WHEN** 分类字段值等于某规则的 category
- **THEN** 使用该规则的通知配置

#### Scenario: 匹配失败使用默认
- **WHEN** 分类字段值不匹配任何规则的 category
- **THEN** 使用 default-category 对应的规则（如无 default-category 则跳过通知）

### Requirement: 分类配置层级优先级
系统 SHALL 按层级优先级查找分类配置。

#### Scenario: tracker级优先
- **WHEN** tracker 配置了 classify-field 和 classify-rules
- **THEN** 使用 tracker 级配置，忽略项目级和全局级

#### Scenario: 项目级优先
- **WHEN** tracker 未配置但项目配置了 classify-field 和 classify-rules
- **THEN** 使用项目级配置，忽略全局级

#### Scenario: 全局级兜底
- **WHEN** tracker 和项目均未配置
- **THEN** 使用全局 classify-config