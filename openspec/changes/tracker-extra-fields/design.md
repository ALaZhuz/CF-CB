## Context

当前 extra-fields 配置结构：

```yaml
extra-fields:
  global:                          # 全局默认
    - field: "priority"
      label: "优先级"
  projects:                        # 项目级（完全覆盖全局）
    "5":
      - field: "customer"
        label: "客户"
```

查找逻辑：`projects[projectId] ?? global`

问题：无法针对特定 tracker 定制 extra-fields。

## Goals / Non-Goals

**Goals:**
- 支持 tracker 级 extra-fields 配置
- 保持与现有 trackers 配置结构一致
- tracker 级完全覆盖项目级（与项目级覆盖全局逻辑一致）

**Non-Goals:**
- 不支持追加模式（tracker 级 + 项目级），保持简单
- 不改变现有项目级和全局级配置结构

## Decisions

### 1. 在 trackers 配置中添加 extra-fields

**选择理由：**
- 已有 `trackers` 配置块用于 tracker 级差异定义
- extra-fields 与 states 同级，配置直观
- 复用现有的优先级查找模式

**配置示例：**
```yaml
projects:
  - project-id: 5
    # 项目级默认 extra-fields
    extra-fields:
      - field: "priority"
        label: "优先级"
    
    # tracker 级配置
    trackers:
      - tracker-id: 5292
        # tracker 级 extra-fields（完全覆盖项目级）
        extra-fields:
          - field: "customer"
            label: "客户"
          - field: "dueDate"
            label: "截止日期"
        # 可同时定义状态配置
        states:
          - name: "评审中"
            notify-field: "assignedTo"
      
      - tracker-id: 5300
        # 这个 tracker 不需要额外字段，可以显式设为空列表
        extra-fields: []
```

### 2. extra-fields 查找优先级

**优先级（从高到低）：**

```
1. trackers[trackerId].extra-fields     ← tracker 级（最高）
2. projects[projectId].extra-fields     ← 项目级
3. global                               ← 全局级（最低）
```

**查找逻辑：**
```java
public List<ExtraField> getExtraFields(Integer projectId, Integer trackerId) {
    // 1. 先查找 tracker 级配置
    ProjectConfig.TrackerConfig trackerConfig = findTrackerConfig(projectId, trackerId);
    if (trackerConfig != null && trackerConfig.getExtraFields() != null) {
        return trackerConfig.getExtraFields();
    }
    
    // 2. 再查找项目级配置
    if (projectId != null) {
        List<ExtraField> projectFields = workflowProperties.getExtraFields()
                .getProjects().get(String.valueOf(projectId));
        if (projectFields != null) {
            return projectFields;
        }
    }
    
    // 3. 最后使用全局配置
    return workflowProperties.getExtraFields().getGlobal();
}
```

### 3. ProjectConfig.TrackerConfig 扩展

**现有结构：**
```java
@Data
public static class TrackerConfig {
    private Integer trackerId;
    private String workflow;
    private List<WorkflowTemplate.StateConfig> states;
}
```

**扩展后：**
```java
@Data
public static class TrackerConfig {
    private Integer trackerId;
    private String workflow;
    private List<WorkflowTemplate.StateConfig> states;
    private List<ExtraField> extraFields;  // 新增
}
```

## Risks / Trade-offs

### Risk 1: 配置冗余

如果多个 tracker 需要相同的 extra-fields，需要在每个 tracker 中重复定义。

**Mitigation:** 可以在项目级定义公共的 extra-fields，只有需要差异的 tracker 定义自己的 extra-fields。如果 tracker 级配置为空列表 `[]`，则不显示任何额外字段。

### Trade-off 1: 不支持追加模式

tracker 级完全覆盖项目级，不能追加。

**Reason:** 保持逻辑简单，与项目级覆盖全局一致。如果需要追加，可以在 tracker 级重新列出所有字段（包括项目级的）。

## Migration Plan

### Phase 1: 扩展数据结构
1. 在 ProjectConfig.TrackerConfig 中添加 extraFields 字段
2. 无需修改 WorkflowProperties（结构已支持）

### Phase 2: 更新查找逻辑
1. 更新 WorkflowConfigService.getExtraFields() 方法，增加 trackerId 参数
2. 实现三级查找逻辑

### Phase 3: 更新调用方
1. 更新 WorkflowNotifyService.formatMessage()，传入 trackerId 参数

### Phase 4: 更新测试
1. 添加 tracker 级 extra-fields 测试场景
2. 测试覆盖、空列表等边界情况

### Phase 5: 更新文档
1. 更新 workflow-config.yml 示例
2. 更新 openspec/specs/workflow-config/spec.md