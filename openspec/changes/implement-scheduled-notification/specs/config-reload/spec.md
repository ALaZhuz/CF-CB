## ADDED Requirements

### Requirement: YAML变更检测
系统 SHALL 检测 workflow-config.yml 文件的变更。

#### Scenario: 获取文件修改时间
- **WHEN** 调度执行前检测变更
- **THEN** 获取 workflow-config.yml 文件的 lastModifiedTime

#### Scenario: 对比修改时间
- **WHEN** 文件修改时间 > config_meta.yaml_modified_time
- **THEN** 标记需要重载配置

#### Scenario: 无变更跳过重载
- **WHEN** 文件修改时间 <= config_meta.yaml_modified_time
- **THEN** 使用现有配置，不执行重载

### Requirement: 配置重载执行
系统 SHALL 在检测到变更后重载配置。

#### Scenario: 重载Spring配置
- **WHEN** 检测到 YAML 变更
- **THEN** 重新加载 workflow-config.yml 到 WorkflowProperties

#### Scenario: 更新加载时间记录
- **WHEN** 配置重载完成
- **THEN** 更新 config_meta.yaml_modified_time = 当前文件修改时间，last_loaded_time = 当前时间

#### Scenario: 重载失败处理
- **WHEN** 配置重载失败（如 YAML 格式错误）
- **THEN** 记录错误日志，继续使用旧配置

### Requirement: 初始化标记管理
系统 SHALL 通过 config_meta 表管理初始化状态。

#### Scenario: 检查初始化状态
- **WHEN** 服务启动时
- **THEN** 查询 config_meta.initialized 字段

#### Scenario: 标记初始化完成
- **WHEN** 全量初始化执行完成
- **THEN** 更新 config_meta.initialized = true

#### Scenario: 已初始化跳过
- **WHEN** config_meta.initialized = true
- **THEN** 跳过全量初始化流程