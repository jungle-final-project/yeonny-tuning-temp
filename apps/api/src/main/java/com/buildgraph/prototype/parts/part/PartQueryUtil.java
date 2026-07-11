package com.buildgraph.prototype.parts.part;

import static com.buildgraph.prototype.parts.util.RuleValueReader.numberLong;
import static com.buildgraph.prototype.parts.util.RuleValueReader.objectMap;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.parts.tool.ToolBuildPart;

public class PartQueryUtil {

    /* 순서를 보장하는 쿼리문 */
    public static String orderBy(String sort) {
        return switch (sort) {
            case "compatibility" -> "coalesce((p.attributes->>'toolReady')::boolean, false) DESC, p.price ASC, p.id ASC";
            case "price_asc" -> "p.price ASC, p.id ASC";
            case "price_desc" -> "p.price DESC, p.id ASC";
            case "name" -> "p.name ASC, p.id ASC";
            default -> """
                    CASE p.category
                        WHEN 'CPU' THEN 1
                        WHEN 'MOTHERBOARD' THEN 2
                        WHEN 'RAM' THEN 3
                        WHEN 'GPU' THEN 4
                        WHEN 'STORAGE' THEN 5
                        WHEN 'PSU' THEN 6
                        WHEN 'CASE' THEN 7
                        WHEN 'COOLER' THEN 8
                        ELSE 99
                    END,
                    p.id ASC
                    """;
        };
    }

    /* 객체 형태로 반환 수행 */
    public static ToolBuildPart part(ResultSet rs) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("attributes", rs.getObject("attributes"));
        return new ToolBuildPart(
                numberLong(rs.getObject("internal_id")),
                rs.getString("id"),
                rs.getString("category"),
                rs.getString("name"),
                rs.getString("manufacturer"),
                rs.getInt("price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }
}
