package com.intland.codebeamer.workflow

import com.intland.codebeamer.persistence.dto.*
import com.intland.codebeamer.persistence.dto.base.*
import com.intland.codebeamer.manager.*

/**
 * Codebeamer工作流通知Groovy脚本
 *
 * 单一脚本，通过beforeEvent变量区分前后事件：
 * - beforeEvent阶段：调用校验接口，失败则阻止保存
 * - afterEvent阶段：调用通知接口，异步发送通知
 *
 * 思路A流程（新建条目）：
 * 1. 先调用 /config/notify-field 接口获取 notifyField
 * 2. 从 subject 提取该字段的成员信息
 * 3. 调用 /validate 接口进行完整校验
 *
 * 使用方法：
 * 1. 将此脚本配置为Tracker的beforeEvent和afterEvent脚本
 * 2. 修改baseUrl为Java服务地址
 * 3. 确保Tracker配置了工作流通知规则
 *
 * @author system
 * @since 1.0
 */

// Java服务地址
String baseUrl = "http://localhost:8081/workflow"

// ========== 辅助方法：从subject获取成员信息 ==========

/**
 * 从TrackerItem获取指定字段的成员userid列表
 *
 * @param trackerItem 条目对象
 * @param fieldName 字段名称（如 assignedTo、submitter、自定义字段名）
 * @return userid列表（Codebeamer用户名）
 */
List<String> getMemberUserIds(def trackerItem, String fieldName) {
    List<String> userIds = []

    try {
        logger.info("开始提取成员userId: fieldName=$fieldName")

        // 内置字段：assignedTo
        if ("assignedTo".equals(fieldName)) {
            def assignedTo = trackerItem.getAssignedTo()
            logger.info("assignedTo对象: $assignedTo, 类型: ${assignedTo?.getClass()?.getName()}")
            if (assignedTo != null) {
                for (member in assignedTo) {
                    logger.info("成员对象: $member, 类型: ${member?.getClass()?.getName()}")
                    // Codebeamer UserDto: 使用 getName() 获取用户名（如"yangyang.zhang"）
                    // 这与钉钉用户缓存中的userid格式一致
                    String userName = member.getName()
                    logger.info("成员userName: $userName")
                    if (userName != null && !userName.isEmpty()) {
                        userIds.add(userName)
                    }
                }
            }
        }
        // 内置字段：supervisors（TrackerItemDto有getSupervisors()方法）
        else if ("supervisors".equals(fieldName)) {
            def supervisors = trackerItem.getSupervisors()
            logger.info("通过getSupervisors()获取: $supervisors, 类型: ${supervisors?.getClass()?.getName()}")
            if (supervisors != null) {
                for (member in supervisors) {
                    logger.info("成员对象: $member, 类型: ${member?.getClass()?.getName()}")
                    // 成员可能是UserDto或Map类型
                    if (member instanceof UserDto) {
                        String userName = member.getName()
                        logger.info("UserDto userName: $userName")
                        if (userName != null && !userName.isEmpty()) {
                            userIds.add(userName)
                        }
                    } else if (member instanceof Map) {
                        String userName = member.get("name")
                        logger.info("Map userName: $userName")
                        if (userName != null && !userName.isEmpty()) {
                            userIds.add(userName)
                        }
                    } else {
                        // 其他类型，尝试调用getName()方法
                        try {
                            String userName = member.getName()
                            logger.info("其他类型 userName: $userName")
                            if (userName != null && !userName.isEmpty()) {
                                userIds.add(userName)
                            }
                        } catch (Exception e) {
                            logger.warn("无法从成员对象获取用户名: ${e.message}")
                        }
                    }
                }
            }
        }
        // 内置字段：submitter（对应Codebeamer的createdBy）
        else if ("submitter".equals(fieldName)) {
            def createdBy = trackerItem.getCreatedBy()
            if (createdBy != null) {
                String userName = createdBy.getName()
                if (userName != null && !userName.isEmpty()) {
                    userIds.add(userName)
                }
            }
        }
        // 自定义字段：调用 Java 服务端获取
        else {
            logger.info("自定义字段: fieldName=$fieldName, 调用Java服务端获取成员信息")

            // 对于自定义字段，不在这里校验，直接返回空列表
            // 实际校验由 Java 服务端通过 API 获取完整数据后处理
            // 这样可以避免 Groovy 脚本中 customFields 为空的问题
            logger.info("自定义字段校验跳过，由Java服务端处理")
        }
    } catch (Exception e) {
        logger.error("获取成员信息异常: fieldName=$fieldName, error=${e.message}")
    }

    return userIds
}

/**
 * 从TrackerItem获取指定字段的成员名称列表
 *
 * @param trackerItem 条目对象
 * @param fieldName 字段名称
 * @return 名称列表
 */
List<String> getMemberNames(def trackerItem, String fieldName) {
    List<String> names = []

    try {
        // 内置字段：assignedTo
        if ("assignedTo".equals(fieldName)) {
            def assignedTo = trackerItem.getAssignedTo()
            if (assignedTo != null) {
                for (member in assignedTo) {
                    // Codebeamer UserDto: 尝试多种方法获取名称
                    String name = member.getName()
                    if (name == null) {
                        // 尝试 firstName + lastName
                        String firstName = member.getFirstName()
                        String lastName = member.getLastName()
                        if (firstName != null || lastName != null) {
                            name = (firstName ?: "") + " " + (lastName ?: "")
                        }
                    }
                    if (name != null && !name.trim().isEmpty()) {
                        names.add(name.trim())
                    }
                }
            }
        }
        // 内置字段：supervisors（TrackerItemDto有getSupervisors()方法）
        else if ("supervisors".equals(fieldName)) {
            def supervisors = trackerItem.getSupervisors()
            if (supervisors != null) {
                for (member in supervisors) {
                    String name = null
                    if (member instanceof UserDto) {
                        name = member.getName()
                        if (name == null) {
                            String firstName = member.getFirstName()
                            String lastName = member.getLastName()
                            if (firstName != null || lastName != null) {
                                name = (firstName ?: "") + " " + (lastName ?: "")
                            }
                        }
                    } else if (member instanceof Map) {
                        name = member.get("name") ?: member.get("displayName")
                    } else {
                        try {
                            name = member.getName()
                        } catch (Exception e) {
                            logger.warn("无法从成员对象获取名称: ${e.message}")
                        }
                    }
                    if (name != null && !name.trim().isEmpty()) {
                        names.add(name.trim())
                    }
                }
            }
        }
        // 内置字段：submitter（对应Codebeamer的createdBy）
        else if ("submitter".equals(fieldName)) {
            def createdBy = trackerItem.getCreatedBy()
            if (createdBy != null) {
                String name = createdBy.getName()
                if (name == null) {
                    String firstName = createdBy.getFirstName()
                    String lastName = createdBy.getLastName()
                    if (firstName != null || lastName != null) {
                        name = (firstName ?: "") + " " + (lastName ?: "")
                    }
                }
                if (name != null && !name.trim().isEmpty()) {
                    names.add(name.trim())
                }
            }
        }
        // 自定义字段：不在这里处理，由Java服务端获取
        else {
            logger.info("自定义字段名称获取跳过: fieldName=$fieldName")
        }
    } catch (Exception e) {
        logger.error("获取成员名称异常: fieldName=$fieldName, error=${e.message}")
    }

    return names
}

// ========== HTTP请求辅助方法 ==========

/**
 * 发送HTTP GET请求
 *
 * @param url URL地址
 * @return 响应JSON对象
 */
def sendGetRequest(String url) {
    def connection = new URL(url).openConnection()
    connection.setRequestMethod("GET")
    connection.setRequestProperty("Accept", "application/json")

    int responseCode = connection.getResponseCode()

    // 读取响应（成功或失败）
    def responseText
    if (responseCode >= 200 && responseCode < 300) {
        responseText = connection.getInputStream().getText("UTF-8")
    } else {
        responseText = connection.getErrorStream()?.getText("UTF-8") ?: ""
        throw new Exception("HTTP请求失败: $responseCode")
    }

    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(responseText)
}

/**
 * 发送HTTP POST请求
 *
 * @param url URL地址
 * @param requestBody JSON请求体
 * @return 响应JSON对象
 */
def sendPostRequest(String url, String requestBody) {
    def connection = new URL(url).openConnection()
    connection.setRequestMethod("POST")
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setDoOutput(true)

    connection.getOutputStream().write(requestBody.getBytes("UTF-8"))
    connection.getOutputStream().flush()

    int responseCode = connection.getResponseCode()

    // 读取响应（成功或失败）
    def responseText
    if (responseCode >= 200 && responseCode < 300) {
        responseText = connection.getInputStream().getText("UTF-8")
    } else {
        responseText = connection.getErrorStream()?.getText("UTF-8") ?: ""
        logger.error("HTTP POST请求失败: code=$responseCode, body=$responseText")
        throw new Exception("HTTP请求失败: $responseCode")
    }

    def slurper = new groovy.json.JsonSlurper()
    return slurper.parseText(responseText)
}

// ========== beforeEvent阶段：校验逻辑 ==========

if (beforeEvent) {
    Integer itemId = subject.getId()
    Integer trackerId = subject.getTracker()?.getId()

    // 获取目标状态
    String targetState = subject.getStatus()?.getName()

    if (targetState == null) {
        logger.info("目标状态为空，跳过校验")
        return;
    }

    // 判断是新建还是修改
    boolean isNewItem = (itemId == null)

    logger.info("开始beforeEvent校验: itemId=$itemId, trackerId=$trackerId, targetState=$targetState, isNew=$isNewItem")

    try {
        def response

        // 所有条目（新建和修改）都使用思路A流程
        // 因为修改条目时，用户可能修改了成员字段，需要从subject获取新数据

        // Step 1: 查询notifyField（不校验，只返回配置）
        def configUrl = baseUrl + "/config/notify-field?trackerId=$trackerId&targetState=" + URLEncoder.encode(targetState, "UTF-8")
        def configResponse = sendGetRequest(configUrl)

        logger.info("查询notifyField结果: needsNotify=$configResponse.needsNotify, notifyField=$configResponse.notifyField")

        // 如果有错误，直接抛出
        if (configResponse.errorMessage != null) {
            throw new Exception(configResponse.errorMessage)
        }

        // 如果不需要通知，放行保存
        if (!configResponse.needsNotify) {
            logger.info("目标状态不需要通知，放行保存")
            return;
        }

        // Step 2: 解析 notifyFields（支持多字段）
        // 新增字段 notifyFields（列表），兼容旧字段 notifyField（逗号分隔字符串）
        List<String> notifyFields = []

        if (configResponse.notifyFields != null && !configResponse.notifyFields.isEmpty()) {
            // 使用新增的 notifyFields 列表
            notifyFields = configResponse.notifyFields
            logger.info("使用notifyFields列表: $notifyFields")
        } else if (configResponse.notifyField != null && !configResponse.notifyField.isEmpty()) {
            // 兼容旧版：解析逗号分隔字符串
            notifyFields = configResponse.notifyField.split(",").toList()
            logger.info("从notifyField解析: $notifyFields")
        }

        // 内置字段列表
        List<String> builtInFields = ["assignedTo", "supervisors", "submitter", "createdBy", "modifiedBy"]

        // 检查是否都是内置字段
        boolean allBuiltIn = notifyFields.every { field -> builtInFields.contains(field) }

        if (!allBuiltIn) {
            // 包含自定义字段：跳过 beforeEvent 校验
            // 校验由 Java 服务端在 afterEvent 时通过 API 获取完整数据后处理
            logger.info("包含自定义字段($notifyFields)，跳过beforeEvent校验，直接放行")
            return;
        }

        logger.info("所有字段都是内置字段: $notifyFields")

        // Step 3: 从subject提取所有字段的成员信息（合并去重）
        Set<String> allUserIds = new LinkedHashSet<String>()
        List<String> allMemberNames = new ArrayList<String>()

        for (String field : notifyFields) {
            List<String> fieldUserIds = getMemberUserIds(subject, field)
            List<String> fieldNames = getMemberNames(subject, field)
            allUserIds.addAll(fieldUserIds)
            allMemberNames.addAll(fieldNames)
        }

        List<String> userIds = allUserIds.toList()

        logger.info("提取成员信息: notifyFields=$notifyFields, userIds=$userIds, names=$allMemberNames")

        // Step 4: 调用完整校验接口
        // JSON标准要求使用双引号，不是单引号
        def userIdsJson = userIds.isEmpty() ? "[]" : "[" + userIds.collect { "\"$it\"" }.join(",") + "]"
        def namesJson = allMemberNames.isEmpty() ? "[]" : "[" + allMemberNames.collect { "\"$it\"" }.join(",") + "]"

        def validateRequest
        if (isNewItem) {
            validateRequest = """
            {
                "itemId": null,
                "trackerId": $trackerId,
                "targetState": "$targetState",
                "notifyUserIds": $userIdsJson,
                "notifyMemberNames": $namesJson
            }
            """
        } else {
            validateRequest = """
            {
                "itemId": $itemId,
                "trackerId": $trackerId,
                "targetState": "$targetState",
                "notifyUserIds": $userIdsJson,
                "notifyMemberNames": $namesJson
            }
            """
        }

        response = sendPostRequest(baseUrl + "/validate", validateRequest)

        // 处理最终响应
        if (response.success == false) {
            String errorMessage = response.errorMessage ?: "校验失败"
            logger.error("校验失败: $errorMessage")
            throw new Exception(errorMessage)
        }

        logger.info("校验通过，允许保存")

    } catch (Exception e) {
        logger.error("beforeEvent校验异常: ${e.message}")
        throw e
    }

    return;
}

// ========== afterEvent阶段：通知逻辑 ==========

Integer itemId = subject.getId()
if (itemId == null) {
    logger.error("条目ID为空，无法执行通知")
    return;
}

// 获取目标状态（保存后的新状态）
String targetState = subject.getStatus()?.getName()
if (targetState == null) {
    logger.info("目标状态为空，跳过通知")
    return;
}

logger.info("开始afterEvent通知: itemId=$itemId, targetState=$targetState")

// 异步执行通知（只传必要信息：itemId、targetState）
Thread.start {
    try {
        def requestBody = """
        {
            "itemId": $itemId,
            "targetState": "$targetState"
        }
        """

        def url = new URL(baseUrl + "/notify")
        def connection = url.openConnection()
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setDoOutput(true)

        connection.getOutputStream().write(requestBody.getBytes("UTF-8"))
        connection.getOutputStream().flush()

        int responseCode = connection.getResponseCode()
        def responseText = connection.getInputStream().getText("UTF-8")

        logger.info("通知响应: code=$responseCode, body=$responseText")

        def slurper = new groovy.json.JsonSlurper()
        def response = slurper.parseText(responseText)

        if (response.success) {
            logger.info("通知处理成功: actionType=${response.actionType}")
        } else {
            logger.error("通知处理失败: ${response.errorMessage}")
        }

    } catch (Exception e) {
        logger.error("afterEvent通知异常: ${e.message}", e)
    }
}

logger.info("afterEvent通知已异步启动")