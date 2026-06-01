## 1. 数据库层扩展

- [x] 1.1 新建 org_cache 表（userid, manager_userid, director_userid, dept_id, last_sync_time）
- [x] 1.2 新建 config_meta 表（id, initialized, yaml_modified_time, last_loaded_time）
- [x] 1.3 创建 OrgCacheMapper 接口（insert, selectByUserid, update, deleteAll）
- [x] 1.4 创建 ConfigMetaMapper 接口（select, updateInitialized, updateYamlTime）
- [x] 1.5 创建 OrgCache 实体类
- [x] 1.6 创建 ConfigMeta 实体类

## 2. 配置模型扩展

- [x] 2.1 创建 ClassifyConfig 配置类（classify-field, classify-rules, default-category, default-notify-time）
- [x] 2.2 创建 ClassifyRule 配置类（category, member-interval-days, manager-escalate-days, director-escalate-days）
- [x] 2.3 扩展 WorkflowProperties：新增 classify-config 配置绑定
- [x] 2.4 扩展 WorkflowTemplate.StateConfig：新增 scheduled-notify 字段（Boolean）
- [x] 2.5 扩展 ProjectConfig.TrackerConfig：新增 notify-time、classify-field、classify-rules 字段
- [x] 2.6 扩展 WorkflowConfigService：新增 getClassifyConfig(trackerId, projectId) 方法
- [x] 2.7 扩展 WorkflowConfigService：新增 getScheduledNotify(stateConfig) 方法（默认 true）
- [x] 2.8 扩展 WorkflowConfigService：新增 getNotifyTime(trackerId, projectId) 方法

## 3. Codebeamer API扩展

- [x] 3.1 创建 CBHistoryResponse 响应 DTO（versions 数组）
- [x] 3.2 创建 CBHistoryVersion DTO（itemRevision, changes, modifiedBy, modifiedAt）
- [x] 3.3 创建 CBHistoryChange DTO（field, oldValue, newValue）
- [x] 3.4 扩展 CBSwaggerService：新增 getItemHistory(itemId) 方法
- [x] 3.5 实现 getItemHistory：调用 GET /v3/items/{itemId}/history
- [x] 3.6 实现解析逻辑：遍历 versions 找最后一个状态切换到目标状态的 modifiedAt

## 4. 组织架构缓存服务

- [x] 4.1 创建 OrgCacheService 类
- [x] 4.2 实现 @PostConstruct initCache()：全量同步所有用户
- [x] 4.3 实现 @Scheduled(fixedRate = 3600000) 定时刷新
- [x] 4.4 实现 syncOrgCache()：批量调用 queryOrganizationManager 并写入缓存
- [x] 4.5 实现 getManager(userid)：查缓存/实时查询补缓存
- [x] 4.6 实现 getDirector(userid)：查缓存/实时查询补缓存
- [x] 4.7 实现 manualRefresh()：手动刷新接口

## 5. 配置热更新服务

- [x] 5.1 创建 ConfigMetaService 类
- [x] 5.2 实现 checkInitialized()：查询 config_meta.initialized
- [x] 5.3 实现 markInitialized()：设置 initialized = true
- [x] 5.4 实现 checkYamlModified()：对比文件修改时间与 yaml_modified_time
- [x] 5.5 实现 reloadConfig()：触发 Spring 配置重载（记录更新时间）
- [x] 5.6 实现 updateYamlLoadedTime()：更新加载时间记录

## 6. 初始化补录服务

- [x] 6.1 创建 InitService 类
- [x] 6.2 实现 runInitialization()：检查 initialized 标记，执行全量初始化
- [x] 6.3 实现 fetchItemsByTrackerAndState(trackerId, targetState)：cbQL 查询存量条目
- [x] 6.4 实现 getItemEnterStateTime(itemId)：调用 history API 解析进入状态时间
- [x] 6.5 实现 batchInsertStateRecords(items)：批量写入 item_state_record（检查重复）
- [x] 6.6 实现 补录Project(projectId)：按项目补录存量条目
- [x] 6.7 在 App.java 启动流程中调用 runInitialization()（InitService @PostConstruct 自动执行）

## 7. 定时通知核心服务

- [x] 7.1 创建 ScheduledNotifyService 类
- [x] 7.2 实现 @Scheduled(cron = "0 0 8 * * *") 主调度方法（或动态 cron）
- [x] 7.3 实现 checkAndReloadConfig()：检测 YAML 变更并重载
- [x] 7.4 实现 queryItemsToNotify()：查询 item_state_record，过滤今日已通知
- [x] 7.5 实现 processItem(item)：计算停留天数、获取分类规则、判断通知层级
- [x] 7.6 实现 calculateStayDays(enterStateTime)：计算停留天数
- [x] 7.7 实现 getClassifyValue(item, classifyField)：提取分类字段值
- [x] 7.8 实现 matchClassifyRule(classifyValue, classifyRules)：匹配分类规则
- [x] 7.9 实现 sendMemberNotifications(item, members)：逐人发送成员通知
- [x] 7.10 实现 sendManagerNotifications(items)：按科长聚合发送通知
- [x] 7.11 实现 sendDirectorNotifications(items)：按部长聚合发送通知
- [x] 7.12 实现 formatMemberMessage(item)：格式化成员消息内容
- [x] 7.13 实现 formatAggregatedMessage(items)：格式化科长/部长聚合消息
- [x] 7.14 实现 updateNotifyRecords(itemId, notifiedUserids)：更新 last_notify_time 和 notify_log

## 8. 管理接口控制器

- [x] 8.1 创建 ScheduledNotifyController 类
- [x] 8.2 实现 POST /admin/补录/{projectId} 接口
- [x] 8.3 实现 POST /admin/init 接口：手动触发全量初始化
- [x] 8.4 实现 POST /admin/org-cache/refresh 接口：手动刷新组织缓存
- [x] 8.5 实现 POST /admin/config/reload 接口：手动触发配置重载

## 9. 钉钉服务扩展

- [x] 9.1 扩展 DingServiceImpl：确保 queryOrganizationManager 方法正确返回科长/部长（已验证）
- [x] 9.2 扩展 DingProperties：新增 default-notify-time 配置字段（可选，已由 classify-config 替代）

## 10. workflow-config.yml 配置更新

- [x] 10.1 添加 classify-config 全局配置块示例
- [x] 10.2 添加 classify-rules 示例（严重、一般、轻微分类）
- [x] 10.3 添加 tracker 级 notify-time 配置示例
- [x] 10.4 添加状态级 scheduled-notify 配置示例

## 11. 测试与验证

- [x] 11.1 编写 OrgCacheServiceTest：缓存初始化、刷新、查询测试
- [x] 11.2 编写 ConfigMetaServiceTest：YAML 变更检测测试
- [x] 11.3 编写 InitServiceTest：初始化、补录、history API 解析测试
- [x] 11.4 编写 ScheduledNotifyServiceTest：停留天数计算、分类匹配、通知层级判断测试
- [x] 11.5 编写 WorkflowConfigService扩展测试：classify-config 查询、scheduled-notify 默认值测试
- [x] 11.6 验证多成员多科长场景：消息聚合正确性（单元测试覆盖）
- [x] 11.7 验证数据共享约束：初始化不重复写入已存在记录（单元测试覆盖）