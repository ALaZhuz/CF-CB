## ADDED Requirements

### Requirement: 组织架构缓存存储
系统 SHALL 缓存员工与科长/部长的关系。

#### Scenario: 缓存表结构
- **WHEN** org_cache 表存在
- **THEN** 表包含 userid、manager_userid、director_userid、dept_id、last_sync_time 字段

#### Scenario: 缓存数据写入
- **WHEN** 从钉钉获取到员工的科长/部长信息
- **THEN** 写入或更新 org_cache 表对应记录

### Requirement: 组织架构缓存初始化
系统 SHALL 在启动时同步所有用户的组织架构。

#### Scenario: 启动全量同步
- **WHEN** 服务启动时 org_cache 表为空
- **THEN** 遍历所有 Codebeamer 用户，调用钉钉 API 获取科长/部长，写入缓存

#### Scenario: 同步完成日志
- **WHEN** 组织架构同步完成
- **THEN** 记录同步用户数、成功数、失败数

### Requirement: 组织架构缓存定时刷新
系统 SHALL 定时刷新组织架构缓存。

#### Scenario: 每小时刷新
- **WHEN** 定时刷新触发（每小时）
- **THEN** 重新调用钉钉 API 更新所有用户缓存

#### Scenario: 刷新失败处理
- **WHEN** 单个用户刷新失败
- **THEN** 记录失败日志，保留旧缓存数据

### Requirement: 科长查询
系统 SHALL 提供查询员工科长的接口。

#### Scenario: 缓存命中
- **WHEN** org_cache 表中存在该 userid 的 manager_userid
- **THEN** 返回 manager_userid

#### Scenario: 缓存未命中实时查询
- **WHEN** org_cache 表中不存在该 userid
- **THEN** 实时调用 queryOrganizationManager(userid)，写入缓存后返回

#### Scenario: 无科长配置
- **WHEN** 用户无科长（manager_userid 为空）
- **THEN** 返回 null

### Requirement: 部长查询
系统 SHALL 提供查询员工部长的接口。

#### Scenario: 缓存命中
- **WHEN** org_cache 表中存在该 userid 的 director_userid
- **THEN** 返回 director_userid

#### Scenario: 缓存未命中实时查询
- **WHEN** org_cache 表中不存在该 userid
- **THEN** 实时调用 queryOrganizationManager(userid)，写入缓存后返回

#### Scenario: 无部长配置
- **WHEN** 用户无部长（director_userid 为空）
- **THEN** 返回 null