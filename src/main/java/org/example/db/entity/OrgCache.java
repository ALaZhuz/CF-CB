package org.example.db.entity;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 组织架构缓存实体类
 *
 * 对应数据库表 org_cache，用于缓存员工与科长/部长的关系。
 * 减少钉钉API调用频率，提高定时通知性能。
 *
 * @author system
 * @since 1.0
 */
@Data
public class OrgCache {

    /** 员工钉钉 userid（主键） */
    private String userid;

    /** 科长 userid */
    private String managerUserid;

    /** 部长 userid */
    private String directorUserid;

    /** 部门ID */
    private String deptId;

    /** 最后同步时间 */
    private LocalDateTime lastSyncTime;
}