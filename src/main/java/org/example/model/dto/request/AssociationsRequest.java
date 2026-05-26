/*
 * @Author: 张阳阳 1401459021@qq.com
 * @Date: 2026-04-09 17:44:02
 * @LastEditors: 张阳阳 1401459021@qq.com
 * @LastEditTime: 2026-05-25 11:20:12
 * @FilePath: \cf-cb\src\main\java\org\example\model\dto\request\AssociationsRequest.java
 * @Description: 这是默认设置,请设置`customMade`, 打开koroFileHeader查看配置 进行设置: https://github.com/OBKoro1/koro1FileHeader/wiki/%E9%85%8D%E7%BD%AE
 */
package org.example.model.dto.request;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.example.model.cb.TrackerItem;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
@NoArgsConstructor
public class AssociationsRequest {
    private List<ItemId> items;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ItemId {
        private Integer id;
    }

    public AssociationsRequest(List<TrackerItem> sourceList) {
        this.items = sourceList.stream()
                .map(TrackerItem::getId)
                .map(ItemId::new)
                .collect(Collectors.toList());
    }

    // 取 Map 的 value 构建 items
    public AssociationsRequest(Map<Integer, Integer> itemsRelationMap) {
        this.items = itemsRelationMap.values().stream()
                .map(ItemId::new)
                .collect(Collectors.toList());
    }

    // 通用：根据id集合构建items
    public AssociationsRequest(Collection<Integer> ids) {
        this.items = ids.stream()
                .filter(id -> id != null)
                .map(ItemId::new)
                .collect(Collectors.toList());
    }
}
