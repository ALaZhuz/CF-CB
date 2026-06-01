## ADDED Requirements

### Requirement: 四层优先级配置合并
系统必须支持四层配置优先级：Tracker级配置 > 项目内工作流模板 > 全局工作流模板 > 全局默认规则。

#### Scenario: Tracker级配置最高优先级
- **WHEN** tracker_id="111"在项目级配置中有专属状态配置
- **AND** 该tracker同时引用了项目内工作流模板
- **THEN** 系统使用tracker级专属配置，覆盖项目内工作流模板配置

#### Scenario: Tracker引用项目内工作流模板
- **WHEN** 项目配置中定义了工作流模板"项目A-缺陷流程"
- **AND** tracker_id="111"配置为引用workflow: "项目A-缺陷流程"
- **THEN** 系统使用项目内工作流模板配置

#### Scenario: Tracker引用全局工作流模板
- **WHEN** tracker_id="111"未在项目级配置专属配置
- **AND** tracker配置为引用workflow: "标准Bug工作流"（全局工作流）
- **THEN** 系统使用全局工作流模板配置

#### Scenario: 全局默认规则兜底
- **WHEN** tracker未配置专属配置，也未引用任何工作流模板
- **AND** 全局默认规则存在
- **THEN** 系统使用全局默认规则

### Requirement: 项目内工作流模板定义
系统必须支持在项目配置中定义工作流模板，供项目内tracker引用。

#### Scenario: 项目定义多个工作流模板
- **WHEN** 项目A配置中定义了工作流模板"项目A-缺陷流程"和"项目A-需求流程"
- **AND** tracker_id="111"（缺陷tracker）引用"项目A-缺陷流程"
- **AND** tracker_id="222"（需求tracker）引用"项目A-需求流程"
- **THEN** 不同tracker使用各自匹配的工作流模板

### Requirement: Tracker批量匹配规则
系统必须支持按规则批量匹配tracker到工作流模板，避免逐个手动配置。

#### Scenario: 按tracker类型批量匹配
- **WHEN** 全局匹配规则定义：tracker_type="Bug" → workflow="标准Bug工作流"
- **AND** tracker_id="111"的类型为Bug
- **AND** tracker_id="111"未精确配置
- **THEN** 系统自动匹配到"标准Bug工作流"

#### Scenario: 按tracker名称模式批量匹配
- **WHEN** 项目匹配规则定义：name_pattern="*需求规格*" → workflow="需求流程"
- **AND** tracker_id="222"的名称为"产品需求规格"
- **AND** tracker_id="222"未精确配置
- **THEN** 系统自动匹配到"需求流程"

#### Scenario: 精确配置优先于批量匹配
- **WHEN** tracker_id="111"有精确配置引用workflow="特殊流程"
- **AND** 批量匹配规则定义：tracker_type="Bug" → workflow="标准Bug工作流"
- **AND** tracker_id="111"的类型为Bug
- **THEN** 系统使用精确配置的"特殊流程"，不使用批量匹配结果

#### Scenario: 匹配规则按顺序执行
- **WHEN** 配置多条匹配规则，按顺序定义
- **AND** tracker同时匹配多条规则
- **THEN** 系统使用第一条匹配成功的规则结果

### Requirement: 状态配置显式声明校验
系统必须在运行时校验目标状态是否在工作流模板中显式声明，未声明的状态阻止保存。

#### Scenario: 工作流模板覆盖所有状态
- **WHEN** 工作流模板"标准Bug工作流"定义了所有可能状态（新建、处理中、已关闭、待审核等）
- **AND** 每个状态都显式声明了notify_field或notify: false
- **THEN** 系统正常运行，所有状态转换都通过校验

#### Scenario: 工作流模板缺少某状态声明
- **WHEN** 工作流模板"标准Bug工作流"只定义了"新建"、"处理中"状态
- **AND** 条目切换到未定义的状态"待定"
- **THEN** 系统返回错误："目标状态[待定]未在工作流模板[标准Bug工作流]中配置，请联系管理员补充"

#### Scenario: 批量匹配覆盖大量tracker
- **WHEN** 系统有100个Bug类型tracker
- **AND** 全局匹配规则定义：tracker_type="Bug" → workflow="标准Bug工作流"
- **AND** "标准Bug工作流"定义了所有Bug相关状态
- **THEN** 所有Bug类型tracker自动继承工作流配置，无需逐个手动配置

### Requirement: 配置文件结构
系统必须支持YAML配置文件定义四层配置结构。

#### Scenario: 配置文件包含全局工作流模板
- **WHEN** workflow-config.yml中定义global_workflows列表
- **THEN** 系统加载全局工作流模板供所有项目引用

#### Scenario: 配置文件包含项目级配置
- **WHEN** workflow-config.yml中定义projects列表
- **AND** 每个项目配置包含workflows（项目内模板）和trackers（tracker配置）
- **THEN** 系统加载项目级配置，支持项目内工作流模板定义和tracker精确配置

#### Scenario: 配置文件包含匹配规则
- **WHEN** workflow-config.yml中定义tracker_matching规则列表
- **THEN** 系统按规则批量匹配tracker到工作流模板