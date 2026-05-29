## 1. 基础设施搭建

- [x] 1.1 添加SQLite JDBC依赖到pom.xml (org.xerial:sqlite-jdbc)
- [x] 1.2 添加MyBatis-Spring-Boot-Starter依赖到pom.xml
- [x] 1.3 创建SQLiteConfig配置类，配置SQLite数据源
- [x] 1.4 创建application.yml中的SQLite数据库路径配置
- [x] 1.5 创建数据库初始化脚本schema.sql（表1 item_state_record、表2 notify_log）

## 2. 久化层实现

- [x] 2.1 创建ItemStateRecord实体类（对应表1）
- [x] 2.2 创建NotifyLog实体类（对应表2）
- [x] 2.3 创建ItemStateRecordMapper接口（insert, deleteByItemId, selectByItemId, selectAll）
- [x] 2.4 创建NotifyLogMapper接口（insert, selectByItemId）
- [x] 2.5 创建Mapper XML文件或使用注解实现SQL（使用注解方式）

## 3. 配置系统实现

- [x] 3.1 创建workflow-config.yml配置文件模板
- [x] 3.2 创建WorkflowProperties配置绑定类（@ConfigurationProperties）
- [x] 3.3 创建WorkflowTemplate类（全局工作流模板）
- [x] 3.4 创建ProjectConfig类（项目级配置）
- [x] 3.5 创建TrackerMatchingRule类（批量匹配规则）
- [x] 3.6 创建WorkflowConfigService服务类，实现四层配置合并逻辑
- [x] 3.7 实现tracker批量匹配逻辑（按类型、按名称精确匹配）

## 4. Codebeamer API扩展

- [x] 4.1 扩展CBSwaggerServiceImpl，新增getItemInfo(itemId)方法
- [x] 4.2 扩展CBSwaggerServiceImpl，新增getAllUsers()方法（分页获取所有用户）
- [x] 4.3 创建ItemInfoResponse DTO类（封装条目详情返回）

## 5. 钉钉服务扩展

- [x] 5.1 扩展DingServiceImpl，新增sendTextMessage(userIds, content)方法
- [x] 5.2 实现企业钉钉发送纯文本消息逻辑
- [x] 5.3 实现个人钉钉Webhook发送纯文本消息逻辑
- [x] 5.4 扩展DingServiceImpl，新增checkUserExists(userid)方法
- [x] 5.5 创建DingProperties扩展类，新增mode配置项

## 6. userid缓存实现

- [x] 6.1 创建DingUserCacheService服务类
- [x] 6.2 实现启动时全量验证userid逻辑（调用CBSwaggerServiceImpl.getAllUsers）
- [x] 6.3 实现定期刷新缓存逻辑（@Scheduled）
- [x] 6.4 实现缓存未命中时实时查询钉钉API逻辑
- [x] 6.5 创建缓存查询接口isValidUserId(userid)

## 7. 校验接口实现（beforeEvent）

- [x] 7.1 创建WorkflowValidateService服务类
- [x] 7.2 实现目标状态意图判断逻辑（查配置判断notify_field/notify:false/未配置）
- [x] 7.3 实现校验①：通知字段是否有成员填写
- [x] 7.4 实现校验②：所有userid在钉钉缓存中存在（缓存未命中实时查询）
- [x] 7.5 创建ValidateRequest DTO类（itemId, targetState）
- [x] 7.6 创建ValidateResponse DTO类（success, errorMessage）
- [x] 7.7 创建WorkflowValidateController，暴露POST /workflow/validate接口

## 8. 通知接口实现（afterEvent）

- [x] 8.1 创建WorkflowNotifyService服务类
- [x] 8.2 实现进入目标状态处理逻辑（查询条目详情、格式化消息、发送通知）
- [x] 8.3 实现离开目标状态处理逻辑（删除表1记录）
- [x] 8.4 实现消息格式化逻辑（按模板填充变量）
- [x] 8.5 实现状态记录持久化（调用ItemStateRecordMapper.insert）
- [x] 8.6 实现发送日志持久化（调用NotifyLogMapper.insert）
- [x] 8.7 创建NotifyRequest DTO类（itemId, previousState, targetState）
- [x] 8.8 创建NotifyResponse DTO类（success, notifiedUsers）
- [x] 8.9 创建WorkflowNotifyController，暴露POST /workflow/notify接口

## 9. Tracker匹配器实现

- [x] 9.1 创建TrackerMatcher逻辑（已在WorkflowConfigService中实现）
- [x] 9.2 实现tracker_type类型匹配（忽略大小写）
- [x] 9.3 实现tracker_name名称精确匹配
- [x] 9.4 实现匹配规则按顺序执行逻辑

## 10. 集成测试

- [x] 10.1 编写WorkflowValidateService单元测试（三项校验场景）
- [x] 10.2 编写WorkflowNotifyService单元测试（进入/离开状态场景）
- [x] 10.3 编写WorkflowConfigService单元测试（四层配置合并场景）
- [x] 10.4 编写DingUserCacheService单元测试（缓存刷新、未命中查询场景）
- [x] 10.5 编写WorkflowIntegrationTest集成测试（Controller API测试）
- [x] 10.6 创建Codebeamer Groovy脚本模板（调用HTTP接口）
- [ ] 10.7 与Codebeamer测试环境联调验证

## 11. 配置文件完善

- [x] 11.1 完善workflow-config.yml配置示例（包含全局工作流、项目配置、匹配规则）
- [ ] 11.2 添加配置校验逻辑（启动时校验配置完整性）

## 12. workflow-config.yml 结构重构（2026-05-27）

- [x] 12.1 重构workflow-config.yml：删除workflow description字段
- [x] 12.2 新增type-mappings配置结构（全局 + 项目级覆盖）
- [x] 12.3 新增extra-fields配置结构（全局 + 项目级完全覆盖）
- [x] 12.4 更新WorkflowProperties类，适配新配置结构
- [x] 12.5 实现固定消息模板格式（trackertype + 名称 + 状态 + 通知人 + extra-fields + 链接）
- [x] 12.6 实现type-mappings查找逻辑（项目级优先，无配置用全局）
- [x] 12.7 实现extra-fields动态获取和插入逻辑（在链接行之前）
- [x] 12.8 更新tracker-matching：删除tracker-name匹配，使用tracker-id + tracker-type
- [x] 12.9 实现trackers差异配置合并逻辑（与workflow.states合并，同名覆盖，新增追加）
- [x] 12.10 更新消息格式化逻辑，使用新模板格式