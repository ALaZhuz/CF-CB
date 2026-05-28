package org.example.db.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.springframework.context.annotation.Configuration;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * MyBatis LocalDateTime类型处理器
 *
 * 用于将Java的LocalDateTime与SQLite的TEXT类型进行转换。
 * SQLite不支持DATETIME类型，使用TEXT存储时间字符串。
 *
 * 存储格式：yyyy-MM-dd HH:mm:ss（不含时区信息）
 * 读取兼容：ISO格式（yyyy-MM-ddTHH:mm:ss.SSSSSSSSS）和自定义格式
 *
 * @author system
 * @since 1.0
 */
@Configuration
public class SQLiteLocalDateTimeTypeHandler extends BaseTypeHandler<LocalDateTime> {

    /** 写入数据库使用的格式 */
    private static final DateTimeFormatter writeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 读取时尝试的格式列表（兼容旧数据ISO格式） */
    private static final DateTimeFormatter[] readFormatters = {
            writeFormatter,                                      // yyyy-MM-dd HH:mm:ss
            DateTimeFormatter.ISO_LOCAL_DATE_TIME               // yyyy-MM-ddTHH:mm:ss.SSSSSSSSS
    };

    /**
     * 设置非空参数
     *
     * 将LocalDateTime转换为字符串存储到SQLite。
     *
     * @param ps PreparedStatement
     * @param i 参数索引
     * @param parameter LocalDateTime参数
     * @param jdbcType JDBC类型
     */
    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, LocalDateTime parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.format(writeFormatter));
    }

    /**
     * 根据列名获取可空结果
     *
     * 从SQLite TEXT字段解析为LocalDateTime。
     * 兼容ISO格式和自定义格式。
     *
     * @param rs ResultSet
     * @param columnName 列名
     * @return LocalDateTime对象，null值返回null
     */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return parseDateTime(value);
    }

    /**
     * 根据列索引获取可空结果
     *
     * @param rs ResultSet
     * @param columnIndex 列索引
     * @return LocalDateTime对象
     */
    @Override
    public LocalDateTime getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return parseDateTime(value);
    }

    /**
     * 从CallableStatement获取可空结果
     *
     * @param cs CallableStatement
     * @param columnIndex 列索引
     * @return LocalDateTime对象
     */
    @Override
    public LocalDateTime getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return parseDateTime(value);
    }

    /**
     * 解析时间字符串
     *
     * 尝试多种格式解析，兼容旧数据和新数据。
     *
     * @param value 时间字符串
     * @return LocalDateTime对象，null或空字符串返回null
     */
    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }

        // 尝试多种格式解析
        for (DateTimeFormatter formatter : readFormatters) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException e) {
                // 继续尝试下一个格式
            }
        }

        // 如果所有格式都失败，尝试默认解析（ISO格式不带格式器）
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new RuntimeException("无法解析时间字符串: " + value, e);
        }
    }
}