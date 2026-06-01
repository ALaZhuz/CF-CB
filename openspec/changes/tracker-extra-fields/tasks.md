## 1. 数据结构扩展

- [x] 1.1 在 ProjectConfig.TrackerConfig 中添加 extraFields 字段

## 2. 查找逻辑更新

- [x] 2.1 更新 WorkflowConfigService.getExtraFields() 方法签名，增加 trackerId 参数
- [x] 2.2 实现三级查找逻辑：trackers > projects > global
- [x] 2.3 添加 findTrackerExtraFields() 辅助方法

## 3. 调用方更新

- [x] 3.1 更新 WorkflowNotifyService.formatMessage()，传入 trackerId 参数
- [x] 3.2 更新 getExtraFields() 调用，传入 trackerId

## 4. 测试更新

- [x] 4.1 添加 tracker 级 extra-fields 查找测试
- [x] 4.2 测试 tracker 级覆盖项目级场景
- [x] 4.3 测试 tracker 级为空列表场景（不显示任何额外字段）
- [x] 4.4 测试 tracker 级未配置时使用项目级

## 5. 文档更新

- [x] 5.1 更新 workflow-config.yml 添加 tracker 级配置示例
- [x] 5.2 更新 openspec/specs/workflow-config/spec.md
- [x] 5.3 更新 src/main/resources/static/项目说明文档.md