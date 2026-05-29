## Context

当前系统是一个Spring Boot服务(cf-cb)，用于Codebeamer与钉钉集成。已有Python实现的基础即时通知功能存放在`src/main/resources/origin-python/`目录，包含：
- `workflow_notifier.py` - 主通知逻辑
- `dingTalkPerson.py` - 个人钉钉Webhook客户端
- `tracker_matcher.py` - Tracker匹配规则
- `workflow_config.yaml` - 简单配置

现有Java代码已有：
- `CBSwaggerServiceImpl` - Codebeamer API客户端（部分功能）
- `DingServiceImpl` - 钉钉企业API客户端（发送消息、获取token）
- `CBProperties` - Codebeamer配置绑定

需求文档(`requirements.md`)定义了完整的即时通知流程，包括beforeEvent校验、afterEvent处理、状态持久化、三层配置优先级等，Python实现缺少这些关键功能。

## Goals / Non-Goals

**Goals:**
- 实现需求文档中需求1（即时通知）的全部功能
- 将Python可用代码迁移到Java，保持代码风格一致
- 引入SQLite持久化支持状态记录和发送日志
- 实现三层优先级配置系统（项目级 > 全局工作流 > 全局默认）
- 支持个人钉钉和企业钉钉两种通知模式
- 实现userid缓存机制，支持校验用户钉钉存在性

**Non-Goals:**
- 需求2（定时通知）暂不实现，后续迭代
- 初始化与补录功能（需求文档第十节）暂不实现
- 组织架构缓存（表3）暂不实现，后续迭代
- 不修改现有追溯功能(tracker downstream/upstream references)

## Decisions

### 1. 数据库选型：SQLite + MyBatis

**选择理由：**
- 需求文档明确指定SQLite
- 单机部署，无需复杂数据库服务器
- MyBatis与Spring Boot集成成熟，支持动态SQL
- 数据量小（状态记录和日志），SQLite性能足够

**替代方案：**
- H2嵌入式数据库 - 考虑过，但需求文档指定SQLite，遵从需求
- MySQL/PostgreSQL - 部署复杂度高，不适合单机内网场景

### 2. 配置系统：独立workflow-config.yml + 三层合并

**选择理由：**
- 工作流配置复杂度高，不适合放在application.yml
- 三层优先级支持灵活配置，项目可覆盖全局
- YAML格式支持复杂嵌套结构

**配置加载策略：**
```java
// 三层合并顺序：项目配置覆盖全局工作流覆盖默认规则
WorkflowConfig mergedConfig = defaultRules.merge(globalWorkflow).merge(projectConfig);
```

**配置结构设计（2026-05-27 更新）：**

```yaml
# Tracker类型映射（决定消息中 {trackertype} 显示内容）
type-mappings:
  global:
    Requirement: "需求"
    Bug: "缺陷"
  projects:
    "5":
      Requirement: "智驾需求"      # 项目级完全覆盖全局

# 额外字段配置（动态插入到链接行之前）
extra-fields:
  global:
    - field: "priority"
      label: "优先级"
  projects:
    "5":
      - field: "customer"        # 项目级完全覆盖全局
        label: "客户"

# 全局工作流模板（无description字段）
global-workflows:
  - name: "标准Bug工作流"
    states:
      - name: "处理中"
        notify-field: "assignedTo"

# 项目级配置
projects:
  - project-id: 5
    workflows:                   # 只定义项目独有workflow
      - name: "智驾需求流程"
        states: [...]
    tracker-matching:            # tracker-id + tracker-type（无tracker-name）
      - tracker-id: 5292
        workflow: "智驾需求流程"
      - tracker-type: "Bug"
        workflow: "标准Bug工作流"  # 直接引用全局
    trackers:                    # 差异部分配置
      - tracker-id: 333
        states: [...]            # 覆盖/新增workflow中的状态
```

**关键决策：**
1. 删除workflow的description字段 - 简化配置
2. 消息模板固定格式 - 不嵌入每个state，由type-mappings + extra-fields配置
3. tracker-matching使用tracker-id + tracker-type - 防止name重复问题
4. trackers只定义差异部分 - 与tracker-matching配合使用

**消息模板格式（固定）：**
```
【{trackertype}】
{trackertype}名称: {item_name}
{trackertype}状态: {status_name}，请您处理
{notify_field_name}: {notify_display_names}
{extra_fields}                  ← 动态插入（如果配置了）
{trackertype}链接: {item_url}
```

详见 `openspec/specs/workflow-config/spec.md`

### 3. beforeEvent同步阻塞设计

**选择理由：**
- 需求文档明确要求beforeEvent同步校验，失败时阻止Codebeamer保存
- Groovy脚本同步调用HTTP接口，响应时间在毫秒级
- 用户在Codebeamer保存条目时等待校验结果，体验可接受

**实现方式：**
- Groovy beforeEvent调用 `POST /workflow/validate`
- 校验失败返回错误信息，Groovy阻止保存并提示用户
- 校验成功返回成功，Groovy放行保存

### 4. 钉钉通知双模式支持

**选择理由：**
- Python实现已支持两种模式，保持兼容
- 测试环境可用个人钉钉Webhook快速验证
- 生产环境用企业钉钉API正式发送

**模式切换：**
- 配置中`dingtalk.mode: personal|enterprise`控制
- DingServiceImpl新增`sendTextMessage()`方法，根据模式调用不同API

### 5. userid缓存：内存Set + 定期刷新

**选择理由：**
- 需求文档要求启动时全量验证钉钉userid存在性
- 缓存命中时快速校验，避免每次调用钉钉API
- 缓存未命中时实时查询，防止过期误拦截

**实现方式：**
```java
@Component
public class DingUserCache {
    private Set<String> validUserIds;  // 内存缓存
    @Scheduled(fixedRate = 3600000)    // 1小时刷新
    public void refreshCache() { ... }
}
```

### 6. 架构分层

```
Controller (新增)
    │
    ├── WorkflowValidateController    - beforeEvent校验接口
    └── WorkflowNotifyController      - afterEvent通知接口
    │
Service (新增 + 扩展)
    │
    ├── WorkflowValidateService       - 三项校验逻辑
    ├── WorkflowNotifyService         - 通知处理逻辑
    ├── WorkflowConfigService         - 配置加载和合并
    ├── DingUserCacheService          - userid缓存管理
    ├── CBSwaggerServiceImpl (扩展)   - 新增getItemInfo, getStatuses
    └── DingServiceImpl (扩展)        - 新增sendTextMessage, checkUserExists
    │
Persistence (新增)
    │
    ├── ItemStateRecordMapper         - 表1 CRUD
    └── NotifyLogMapper               - 表2 写入
    │
Config (新增)
    │
    ├── WorkflowProperties            - YAML配置绑定
    └── SQLiteConfig                  - SQLite数据源配置
```

## Risks / Trade-offs

### Risk 1: beforeEvent校验延迟影响用户体验
**Mitigation:** 校验逻辑简单（查配置+查缓存），预期响应时间<100ms；若钉钉API调用慢，设置合理超时(5s)并记录日志

### Risk 2: SQLite并发写入限制
**Mitigation:** 单机部署，并发量低；使用WAL模式提升并发；定时任务单线程执行避免冲突

### Risk 3: 配置复杂度高，管理员配置出错
**Mitigation:** 启动时校验配置完整性（状态必须显式声明）；未配置状态阻断保存，强制管理员补充

### Risk 4: userid缓存过期导致误拦截
**Mitigation:** 缓存未命中时实时查询钉钉API确认；定期刷新周期可配置(默认1小时)

### Trade-off 1: 不实现定时通知
**Reason:** 需求复杂度高，需调度器、分类规则、层级升级逻辑；本次迭代聚焦即时通知，后续迭代实现

### Trade-off 2: 使用同步HTTP调用而非消息队列
**Reason:** 需求文档设计为同步阻塞；单机部署引入消息队列增加复杂度；通知量小，同步处理可接受

## Migration Plan

### Phase 1: 基础设施搭建
1. 添加SQLite JDBC和MyBatis依赖到pom.xml
2. 创建SQLite数据源配置
3. 创建数据库表(表1、表2)
4. 创建Entity类和Mapper接口

### Phase 2: 配置系统实现
1. 创建workflow-config.yml配置文件
2. 创建WorkflowProperties配置绑定类
3. 实现WorkflowConfigService三层合并逻辑

### Phase 3: 校验接口实现
1. 扩展CBSwaggerServiceImpl: getItemInfo(), getTrackerStatuses()
2. 实现DingUserCacheService userid缓存
3. 实现WorkflowValidateService三项校验逻辑
4. 创建WorkflowValidateController接口

### Phase 4: 通知接口实现
1. 扩展DingServiceImpl: sendTextMessage()
2. 实现WorkflowNotifyService通知处理逻辑
3. 创建WorkflowNotifyController接口
4. 实现状态记录持久化逻辑

### Phase 5: 集成测试
1. 编写单元测试
2. 与Codebeamer Groovy脚本联调测试
3. 验证beforeEvent阻断和afterEvent通知

## Open Questions

1. **Groovy脚本改造**：现有Python Groovy脚本调用Python进程，需要改造为调用Java HTTP接口。是否需要提供新Groovy脚本模板？

2. **配置热更新**：需求文档提到定时通知需检测YAML变更重载配置。即时通知是否也需要热更新支持？还是启动时加载即可？

3. **钉钉企业API权限**：企业钉钉发送消息需要appKey/appSecret，当前application.yml已配置。是否需要额外权限申请？

4. **测试环境验证**：是否需要在Codebeamer测试环境验证完整流程？还是先本地单元测试？