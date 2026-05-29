## ADDED Requirements

### Requirement: userid有效性缓存初始化
系统必须在启动时构建钉钉userid有效性缓存，遍历Codebeamer用户并验证钉钉存在性。

#### Scenario: 启动时全量验证userid
- **WHEN** 系统启动完成配置加载后
- **THEN** 系统查询Codebeamer所有用户列表
- **AND** 逐一验证每个用户userid在钉钉中是否存在
- **AND** 将存在的userid存入内存缓存Set

#### Scenario: userid在钉钉中存在
- **WHEN** Codebeamer用户张三的userid为"123"
- **AND** 钉钉API返回userid="123"存在
- **THEN** 系统将"123"加入validUserIds缓存Set

#### Scenario: userid在钉钉中不存在
- **WHEN** Codebeamer用户李四的userid为"456"
- **AND** 钉钉API返回userid="456"不存在
- **THEN** 系统不将"456"加入缓存，记录警告日志"userid[456]在钉钉中不存在"

### Requirement: userid缓存定期刷新
系统必须按配置周期定期刷新userid缓存。

#### Scenario: 每小时刷新缓存
- **WHEN** 配置cache.userid_refresh_interval=3600（1小时）
- **THEN** 系统每小时执行一次全量刷新
- **AND** 重新查询Codebeamer用户列表并验证钉钉存在性
- **AND** 更新内存缓存Set

#### Scenario: 刷新周期可配置
- **WHEN** 配置cache.userid_refresh_interval=7200（2小时）
- **THEN** 系统每2小时执行一次刷新

### Requirement: 缓存未命中实时查询
系统必须在缓存未命中时实时查询钉钉API确认userid存在性。

#### Scenario: 校验时缓存命中
- **WHEN** 校验成员张三(userid="123")是否存在于钉钉
- **AND** userid="123"在validUserIds缓存Set中
- **THEN** 系统直接返回存在，不调用钉钉API

#### Scenario: 校验时缓存未命中但钉钉存在
- **WHEN** 校验成员王五(userid="789")是否存在于钉钉
- **AND** userid="789"不在validUserIds缓存Set中（可能缓存刷新后新增）
- **AND** 实时查询钉钉API返回userid="789"存在
- **THEN** 系统将"789"加入缓存，返回存在

#### Scenario: 校验时缓存未命中且钉钉不存在
- **WHEN** 校验成员赵六(userid="000")是否存在于钉钉
- **AND** userid="000"不在validUserIds缓存Set中
- **AND** 实时查询钉钉API返回userid="000"不存在
- **THEN** 系统返回不存在，不加入缓存

### Requirement: 钉钉用户存在性查询API
系统必须提供查询钉钉用户是否存在的方法。

#### Scenario: 查询用户存在返回true
- **WHEN** 调用checkUserExists(userid="123")
- **AND** 钉钉API确认userid="123"存在
- **THEN** 返回true

#### Scenario: 查询用户不存在返回false
- **WHEN** 调用checkUserExists(userid="000")
- **AND** 钉钉API返回userid="000"不存在
- **THEN** 返回false

#### Scenario: 钉钉API调用异常返回false
- **WHEN** 调用checkUserExists(userid="123")
- **AND** 钉钉API调用超时或异常
- **THEN** 返回false，记录错误日志