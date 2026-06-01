## Why

当前 extra-fields 只支持全局和项目级配置，不支持 tracker 级别。但在实际业务场景中，同一个项目内的不同 tracker 可能需要显示不同的额外字段：

- tracker 5292（需求）需要显示"客户"、"截止日期"字段
- tracker 5300（Bug）只需要显示"优先级"、"严重程度"字段

当前设计下，项目级 extra-fields 会对所有 tracker 生效，无法针对特定 tracker 定制。

## What Changes

- **新增** trackers 配置中的 extra-fields 支持
- **更新** extra-fields 查找优先级：trackers > projects > global
- **保持** 完全覆盖逻辑（tracker级完全覆盖项目级，不追加）

## Capabilities

### Modified Capabilities

- `workflow-config`: 扩展 extra-fields 配置层级，支持 tracker 级别

## Impact

- **修改文件**：
  - WorkflowProperties.java - 无需修改（ProjectConfig.TrackerConfig 已存在）
  - WorkflowConfigService.java - 新增 tracker 级 extra-fields 查找方法
  - WorkflowNotifyService.java - 更新 extra-fields 获取逻辑
  - workflow-config.yml - 可添加 tracker 级配置示例
  - openspec/specs/workflow-config/spec.md - 更新规范文档

- **新增测试**：tracker 级 extra-fields 查找和覆盖场景