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

// ========== beforeEvent阶段：校验逻辑 ==========
if (beforeEvent) {
    Integer itemId = subject.getId()
    // 新建条目时itemId为null，跳过校验
    if (itemId == null) {
        logger.info("新建条目，跳过beforeEvent校验")
        return;
    }

    // 获取目标状态
    String targetState = subject.getStatus()?.getName()

    if (targetState == null) {
        logger.info("目标状态为空，跳过校验")
        return;
    }

    logger.info("开始beforeEvent校验: itemId=$itemId, targetState=$targetState")

    try {
        // 构建请求JSON（只传必要信息：itemId和targetState）
        def requestBody = """
        {
            "itemId": $itemId,
            "targetState": "$targetState"
        }
        """

        // 发送HTTP请求
        def url = new URL(baseUrl + "/validate")
        def connection = url.openConnection()
        connection.setRequestMethod("POST")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setDoOutput(true)

        connection.getOutputStream().write(requestBody.getBytes("UTF-8"))
        connection.getOutputStream().flush()

        int responseCode = connection.getResponseCode()
        def responseText = connection.getInputStream().getText("UTF-8")

        logger.info("校验响应: code=$responseCode, body=$responseText")

        // 解析响应
        def slurper = new groovy.json.JsonSlurper()
        def response = slurper.parseText(responseText)

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

// 获取转换前状态
String previousState = null

// 尝试从binding获取previousStatus
if (binding.hasVariable("previousStatus")) {
    def previousStatusObj = binding.getVariable("previousStatus")
    previousState = previousStatusObj?.getName()
}

logger.info("开始afterEvent通知: itemId=$itemId, previousState=$previousState, targetState=$targetState")

// 异步执行通知（只传必要信息：itemId、previousState、targetState）
Thread.start {
    try {
        def requestBody = """
        {
            "itemId": $itemId,
            "previousState": "${previousState ?: ''}",
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