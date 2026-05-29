---
name: workflow-config
description: 工作流配置YAML文件结构规范
---

# Workflow Config Spec

## 概述

workflow-config.yml 定义工作流通知的三层优先级配置，支持项目级覆盖全局配置。

## 配置结构

### 顶层结构

```yaml
type-mappings:          # Tracker类型映射
extra-fields:           # 额外字段配置
global-workflows:       # 全局工作流模板
projects:               # 项目级配置
```

### type-mappings

Tracker类型到显示名称的映射，用于消息模板中的 `{trackertype}` 占位符。

```yaml
type-mappings:
  global:               # 全局默认映射
    Requirement: "需求"
    Bug: "缺陷"
    Task: "任务"
  projects:             # 项目级覆盖（完全覆盖，不追加）
    "5":
      Requirement: "智驾需求"
      Bug: "智驾缺陷"
```

**查找顺序：** 项目级优先，无配置则使用全局。

### extra-fields

消息中额外显示的字段列表，动态插入到"链接"行之前。

**三级优先级：tracker级 > 项目级 > 全局级**

```yaml
extra-fields:
  global:               # 全局默认
    - field: "priority"
      label: "优先级"
    - field: "severity"
      label: "严重程度"
  projects:             # 项目级覆盖（完全覆盖，不追加）
    "5":
      - field: "customer"
        label: "客户"

# tracker级配置（在projects.trackers中定义）
projects:
  - project-id: 5
    trackers:
      - tracker-id: 5292
        # tracker级 extra-fields（完全覆盖项目级，不追加）
        extra-fields:
          - field: "dueDate"
            label: "截止日期"
      - tracker-id: 5300
        # 空列表表示不显示任何额外字段
        extra-fields: []
```

**处理逻辑：**
- 查找顺序：trackers[trackerId].extra-fields → projects[projectId].extra-fields → global
- 配置了 extra-fields → 调用 API 获取字段值
- 未配置（null）→ 使用上一级配置
- 空列表（[]）→ 不显示任何额外字段
- 格式：`{label}: {value}`

### global-workflows

全局工作流模板，被项目级引用或覆盖。

```yaml
global-workflows:
  - name: "标准Bug工作流"
    states:
      - name: "新建"
        notify: false
      - name: "处理中"
        notify-field: "assignedTo"
      - name: "已解决"
        notify-field: "submitter"
      - name: "已关闭"
        notify: false

  - name: "标准需求工作流"
    states:
      - name: "草稿"
        notify: false
      - name: "评审中"
        notify-field: "评审人"
```

**注意：**
- 无 `description` 字段（已删除）
- 项目级可直接引用全局 workflow（在 tracker-matching 中使用同名）
- 项目级可定义同名 workflow 覆盖全局

### projects

项目级配置，覆盖或扩展全局配置。

```yaml
projects:
  - project-id: 5
    project-name: "智能驾驶项目A"

    # 项目级 type-mappings（覆盖全局）
    type-mappings:
      Requirement: "智驾需求"

    # 项目级 extra-fields（完全覆盖全局）
    extra-fields:
      - field: "customer"
        label: "客户"

    # 项目独有 workflow（复用全局直接在 tracker-matching 引用）
    workflows:
      - name: "智驾需求流程"
        states:
          - name: "草稿"
            notify: false
          - name: "评审中"
            notify-field: "assignedTo"
          - name: "已批准"
            notify-field: "submitter"

    # tracker-matching：tracker-id + tracker-type
    tracker-matching:
      - tracker-id: 5292
        workflow: "智驾需求流程"
      - tracker-id: 222
        workflow: "标准Bug工作流"       # 直接引用全局
      - tracker-type: "Bug"
        workflow: "标准Bug工作流"       # 直接引用全局
      - tracker-type: "Requirement"
        workflow: "智驾需求流程"

    # trackers：差异部分配置（tracker-matching 未覆盖的特殊情况）
    trackers:
      - tracker-id: 333
        states:
          - name: "特殊状态A"
            notify-field: "特殊通知人"
          - name: "特殊状态B"
            notify: false
```

## Tracker 匹配优先级

```
优先级从高到低：

1. trackers 差异配置
   - tracker-id 精确匹配
   - 定义与 workflow 不同的状态配置

2. tracker-matching tracker-id
   - tracker-id 精确匹配
   - 指定使用的 workflow

3. tracker-matching tracker-type
   - tracker-type 类型匹配
   - 指定使用的 workflow

4. 无匹配
   - 不发送通知
```

**注意：** tracker-name 匹配已删除，使用 tracker-id 防止名称重复问题。

## 状态配置合并逻辑

当 tracker 同时匹配 tracker-matching 和 trackers 差异配置时：

```java
// 合并 workflow.states 和 trackers.states
// trackers.states 中同名状态覆盖 workflow.states
// trackers.states 中新状态追加到 workflow.states
```

## 消息模板格式（固定）

```
【{trackertype}】
{trackertype}名称: {item_name}
{trackertype}状态: {status_name}，请您处理
{notify_field_name}: {notify_display_names}
{extra_fields}                      ← 动态插入（如果配置了）
{trackertype}链接: {item_url}
```

**占位符说明：**
- `{trackertype}` - 来自 type-mappings，根据 tracker.type 确定
- `{item_name}` - 条目名称
- `{status_name}` - 目标状态名称
- `{notify_field_name}` - 通知字段名（如 assignedTo）
- `{notify_display_names}` - 通知人显示名列表
- `{extra_fields}` - extra-fields 配置的字段，格式 `{label}: {value}`
- `{item_url}` - 条目链接

## 示例输出

**项目5 + Requirement tracker + extra-fields:**

```
【智驾需求】
智驾需求名称: 用户登录功能需求
智驾需求状态: 评审中，请您处理
assignedTo: 张三,李四
客户: XX汽车
截止日期: 2025-06-30
智驾需求链接: https://cb-pmt.hirain.com/item/12345
```

**项目无配置 + Bug tracker（使用全局）:**

```
【缺陷】
缺陷名称: 登录失败Bug
缺陷状态: 处理中，请您处理
assignedTo: 王五
优先级: 高
严重程度: 紧急
缺陷链接: https://cb-pmt.hirain.com/item/67890
```