## MODIFIED Requirements

### Requirement: 配置结构扩展
workflow-config.yml SHALL 支持定时通知相关的配置扩展。

#### Scenario: 新增classify-config顶层结构
- **WHEN** workflow-config.yml 需要配置定时通知分类规则
- **THEN** 新增 classify-config 顶层配置块，包含 classify-field、classify-rules、default-category

#### Scenario: 状态配置新增scheduled-notify字段
- **WHEN** 状态需要配置定时通知开关
- **THEN** 在状态配置中新增 scheduled-notify 字段（布尔值，默认 true）

#### Scenario: tracker级新增notify-time字段
- **WHEN** tracker 需要配置自定义通知时间
- **THEN** 在 tracker 级配置中新增 notify-time 字段（时间格式如 "08:00"）

#### Scenario: tracker级新增classify-field和classify-rules
- **WHEN** tracker 需要自定义分类配置
- **THEN** 在 tracker 级配置中新增 classify-field 和 classify-rules，覆盖项目级和全局级

## ADDED Requirements

### Requirement: classify-config全局配置
系统 SHALL 支持全局分类通知配置。

#### Scenario: 全局classify-field配置
- **WHEN** classify-config.global.classify-field 配置为字段名（如 "severities"）
- **THEN** 所有项目默认使用该字段作为分类依据

#### Scenario: 全局classify-rules配置
- **WHEN** classify-config.global.classify-rules 配置分类规则列表
- **THEN** 每条规则包含 category、member-interval-days、manager-escalate-days、director-escalate-days

#### Scenario: 全局default-category配置
- **WHEN** classify-config.global.default-category 配置默认分类名
- **THEN** 条目分类字段值不匹配任何规则时使用该分类

#### Scenario: 全局default-notify-time配置
- **WHEN** classify-config.global.default-notify-time 配置默认通知时间（如 "08:00"）
- **THEN** 所有 tracker 默认在该时间执行定时通知

### Requirement: classify-config项目级配置
系统 SHALL 支持项目级分类配置覆盖全局。

#### Scenario: 项目级classify-field覆盖
- **WHEN** classify-config.projects.{projectId}.classify-field 配置
- **THEN** 该项目使用项目级 classify-field，覆盖全局配置

#### Scenario: 项目级classify-rules覆盖
- **WHEN** classify-config.projects.{projectId}.classify-rules 配置
- **THEN** 该项目使用项目级 classify-rules，完全覆盖全局（不追加）

### Requirement: classify-config tracker级配置
系统 SHALL 支持 tracker 级分类配置作为最高优先级。

#### Scenario: tracker级classify-field配置
- **WHEN** trackers.{trackerId}.classify-field 配置
- **THEN** 该 tracker 使用 tracker 级 classify-field，覆盖项目级和全局级

#### Scenario: tracker级classify-rules配置
- **WHEN** trackers.{trackerId}.classify-rules 配置
- **THEN** 该 tracker 使用 tracker 级 classify-rules，完全覆盖上级配置

#### Scenario: tracker级notify-time配置
- **WHEN** trackers.{trackerId}.notify-time 配置（如 "09:00"）
- **THEN** 该 tracker 在配置时间执行定时通知，覆盖 default-notify-time

### Requirement: classify-rules规则结构
每条分类规则 SHALL 包含通知频率配置。

#### Scenario: member-interval-days配置
- **WHEN** 规则配置 member-interval-days = N
- **THEN** 成员每 N 天收到一次通知（N >= 1）

#### Scenario: manager-escalate-days配置
- **WHEN** 规则配置 manager-escalate-days = N
- **THEN** 停留天数 >= N 时科长开始收到通知（每天）

#### Scenario: director-escalate-days配置
- **WHEN** 规则配置 director-escalate-days = N（N != null）
- **THEN** 停留天数 >= N 时部长开始收到通知（每天）

#### Scenario: director-escalate-days为null
- **WHEN** 规则配置 director-escalate-days = null
- **THEN** 该分类不触发部长通知