package com.intland.codebeamer.workflow

import com.intland.codebeamer.persistence.dto.*;
import com.intland.codebeamer.persistence.dto.base.*;
import com.intland.codebeamer.persistence.dao.*;
import com.intland.codebeamer.manager.*;
import com.intland.codebeamer.controller.importexport.*;

if (beforeEvent) {
    return; // 必须在 After-event 执行，确保有 ID 且状态已更新
}

Integer id = subject.getId();
if (id == null) {
    logger.error("Item ID is null, cannot execute script.");
    return;
}

logger.info("Triggering async notification for item ID: " + id);

def env = ["PYTHONIOENCODING=utf-8"]
String command = "python workflow_notifier.py " + id

Thread.start {
    try {
        logger.info("Starting async process execution...")
        Process process = command.execute(env, null)

        InputStream stdout = process.getInputStream()
        InputStream stderr = process.getErrorStream()

        // 读取标准输出
        BufferedReader reader = new BufferedReader(new InputStreamReader(stdout, "UTF-8"))
        String line
        while ((line = reader.readLine()) != null) {
            logger.info(line)
        }

        // 读取错误输出
        reader = new BufferedReader(new InputStreamReader(stderr, "UTF-8"))
        while ((line = reader.readLine()) != null) {
            logger.error(line) 
        }

        int exitCode = process.waitFor()
        logger.info("Async script finished with exit code: $exitCode")

    } catch (Exception e) {
        logger.error("Async script execution failed", e)
    }
}
