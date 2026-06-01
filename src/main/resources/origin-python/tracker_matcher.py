#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Tracker匹配器组件

根据配置的匹配规则为不同的Tracker找到合适的工作流模板。
支持匹配类型：
1. name_pattern - 名称匹配（完全匹配，忽略大小写）
2. tracker_type - 类型匹配（忽略大小写）
"""

import logging
from typing import Dict, List, Optional
import io

try:
    if sys.stdout:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    if sys.stderr:
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')
except Exception as e:
    print(f"Warning: Failed to reconfigure stdout/stderr to UTF-8: {e}")



logger = logging.getLogger(__name__)


class TrackerMatcher:
    """Tracker匹配器"""

    def __init__(self, matching_config: Dict):
        """
        初始化Tracker匹配器

        Args:
            matching_config: 匹配配置字典，包含rules列表
                [
                    {
                        'match_type': 'name_pattern',
                        'pattern': 'software requirement',
                        'template': 'software_requirement'
                    },
                    {
                        'match_type': 'tracker_type',
                        'tracker_type': 'Requirement',
                        'template': 'standard_requirement'
                    }
                ]

        Raises:
            ValueError: 如果配置无效或缺少必要字段
        """
        if not matching_config:
            raise ValueError("匹配配置不能为空")

        if 'rules' not in matching_config:
            raise ValueError("匹配配置缺少rules字段")

        self.rules = self._validate_rules(matching_config['rules'])
        logger.info(f"初始化Tracker匹配器，共 {len(self.rules)} 条规则")

    def _validate_rules(self, raw_rules: List[Dict]) -> List[Dict]:
        """
        验证并清理匹配规则

        Args:
            raw_rules: 原始规则列表

        Returns:
            验证后的规则列表
        """
        validated_rules = []

        for rule in raw_rules:
            # 验证必要字段
            if 'match_type' not in rule:
                logger.error(f"规则缺少match_type字段，跳过该规则: {rule}")
                continue

            if 'template' not in rule:
                logger.error(f"规则缺少template字段，跳过该规则: {rule}")
                continue

            match_type = rule['match_type']

            # 根据匹配类型验证特定字段
            if match_type == 'name_pattern':
                if 'pattern' not in rule or not rule['pattern']:
                    logger.error(f"名称匹配规则缺少pattern字段，跳过该规则: {rule}")
                    continue
                # 标准化pattern为小写用于忽略大小写匹配
                rule['pattern_lower'] = rule['pattern'].lower()
                logger.debug(f"规则验证通过: name_pattern, pattern='{rule['pattern']}', template='{rule['template']}'")

            elif match_type == 'tracker_type':
                if 'tracker_type' not in rule or not rule['tracker_type']:
                    logger.error(f"Tracker类型匹配规则缺少tracker_type字段，跳过该规则: {rule}")
                    continue
                # 标准化tracker_type为小写用于忽略大小写匹配
                rule['tracker_type_lower'] = rule['tracker_type'].lower()
                logger.debug(f"规则验证通过: tracker_type, type='{rule['tracker_type']}', template='{rule['template']}'")

            else:
                logger.error(f"无效的match_type: '{match_type}'，跳过该规则: {rule}")
                continue

            validated_rules.append(rule)

        return validated_rules

    def _matches_rule(self, rule: Dict, tracker_name: str, tracker_type: str) -> bool:
        """
        检查Tracker是否匹配规则

        Args:
            rule: 规则字典
            tracker_name: Tracker名称
            tracker_type: Tracker类型

        Returns:
            如果匹配返回True，否则返回False
        """
        match_type = rule['match_type']

        if match_type == 'name_pattern':
            # 名称完全匹配（忽略大小写）
            pattern_lower = rule.get('pattern_lower')
            if not pattern_lower:
                return False
            return tracker_name.lower() == pattern_lower

        elif match_type == 'tracker_type':
            # Tracker类型匹配（忽略大小写）
            required_type_lower = rule.get('tracker_type_lower')
            if not required_type_lower or tracker_type is None:
                return False
            return tracker_type.lower() == required_type_lower

        return False

    def find_template(self, tracker_name: str, tracker_type: str) -> str:
        """
        为Tracker找到合适的工作流模板

        Args:
            tracker_name: Tracker名称
            tracker_type: Tracker类型

        Returns:
            模板名称字符串

        Raises:
            ValueError: 如果tracker_name为空或没有匹配的规则
        """
        if not tracker_name:
            raise ValueError("Tracker名称不能为空")

        logger.info(f"为Tracker寻找模板: name='{tracker_name}', type='{tracker_type}'")

        # 按顺序遍历所有规则，找到第一个匹配的规则
        for rule in self.rules:
            if self._matches_rule(rule, tracker_name, tracker_type):
                template = rule['template']
                logger.info(f"规则匹配成功: match_type='{rule['match_type']}', template='{template}'")
                return template

        # 没有匹配的规则
        raise ValueError(f"没有找到匹配的规则: tracker_name='{tracker_name}', tracker_type='{tracker_type}'")