#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CodeBeamer工作流通知处理器
用于处理工作流状态转换时的通知逻辑
"""

import yaml
import requests
import json
import logging
import logging.handlers
import os
import sys
from typing import Dict, List, Optional, Any
from tracker_matcher import TrackerMatcher
import io

try:
    if sys.stdout:
        sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8')
    if sys.stderr:
        sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding='utf-8')
except Exception as e:
    print(f"Warning: Failed to reconfigure stdout/stderr to UTF-8: {e}")


# 配置日志系统
def setup_logging(config: Dict) -> logging.Logger:
    """设置日志系统"""
    # 配置根日志器，这样所有模块都会继承配置
    log_level = getattr(logging, config['logging']['level'].upper())
    logging.getLogger().setLevel(log_level)

    # 文件处理器
    log_file = config['logging']['file']
    file_handler = logging.handlers.RotatingFileHandler(
        log_file,
        maxBytes=config['logging']['max_size_mb'] * 1024 * 1024,
        backupCount=config['logging']['backup_count']
    )

    # 控制台处理器
    console_handler = logging.StreamHandler()

    formatter = logging.Formatter(
        '%(asctime)s - %(name)s - %(levelname)s - %(message)s'
    )
    file_handler.setFormatter(formatter)
    console_handler.setFormatter(formatter)

    # 为根日志器添加处理器，这样所有模块都会使用这个处理器
    logging.getLogger().addHandler(file_handler)
    logging.getLogger().addHandler(console_handler)

    # 返回workflow_notifier的日志器（现在会显示日志，因为根日志器已配置）
    return logging.getLogger('workflow_notifier')


class CodeBeamerClient:
    """CodeBeamer API客户端"""

    def __init__(self, config: Dict, environment: str = "test"):
        """
        初始化CodeBeamer客户端

        Args:
            config: 配置文件字典
            environment: 环境类型（production/test）
        """
        env_config = config['codebeamer'][environment]
        self.base_url = env_config['base_url']
        self.username = env_config['username']
        self.password = env_config['password']
        self.session = requests.Session()
        self.session.auth = (self.username, self.password)
        self.timeout = config['execution']['timeout_seconds']
        self.max_retries = config['execution']['max_retries']
        self.retry_delay = config['execution']['retry_delay_seconds']

    def get_item_info(self, item_id: int) -> Optional[Dict]:
        """
        获取Tracker Item信息

        Args:
            item_id: TrackerItem ID

        Returns:
            项目信息字典，失败返回None
        """
        url = f"{self.base_url}/api/v3/items/{item_id}"

        for attempt in range(self.max_retries):
            try:
                response = self.session.get(url, timeout=self.timeout)
                if response.status_code == 200:
                    return response.json()
                else:
                    print(f"Attempt {attempt + 1}/{self.max_retries} failed: "
                          f"{response.status_code}, Message: {response.text}")
                    if attempt < self.max_retries - 1:
                        import time
                        time.sleep(self.retry_delay)
                        continue
                    return None
            except Exception as e:
                print(f"Attempt {attempt + 1}/{self.max_retries} failed: {str(e)}")
                if attempt < self.max_retries - 1:
                    import time
                    time.sleep(self.retry_delay)
                    continue
                return None

        return None

    def get_tracker_statuses(self, tracker_id: int) -> Optional[Dict[str, int]]:
        """
        获取Tracker的状态列表及其ID

        Args:
            tracker_id: Tracker ID

        Returns:
            状态名称到状态ID的映射字典，失败返回None
        """
        # CodeBeamer API: 获取Tracker的状态列表
        # 示例URL: /api/v3/trackers/{trackerId}/statuses
        url = f"{self.base_url}/api/v3/trackers/{tracker_id}/statuses"

        for attempt in range(self.max_retries):
            try:
                response = self.session.get(url, timeout=self.timeout)
                if response.status_code == 200:
                    statuses_data = response.json()

                    # 构建状态名称到ID的映射
                    status_mapping = {}
                    for status in statuses_data:
                        status_name = status.get('name')
                        status_id = status.get('id')
                        if status_name and status_id:
                            status_mapping[status_name] = status_id

                    return status_mapping
                else:
                    print(f"获取状态列表失败 (Attempt {attempt + 1}/{self.max_retries}): "
                          f"{response.status_code}, Message: {response.text}")
                    if attempt < self.max_retries - 1:
                        import time
                        time.sleep(self.retry_delay)
                        continue
                    return None
            except Exception as e:
                print(f"获取状态列表异常 (Attempt {attempt + 1}/{self.max_retries}): {str(e)}")
                if attempt < self.max_retries - 1:
                    import time
                    time.sleep(self.retry_delay)
                    continue
                return None

        return None

    def get_user_display_names(self, users: List[Dict]) -> Dict[str, str]:
        """
        获取用户姓名到显示姓名的映射

        Args:
            users: 用户信息列表

        Returns:
            姓名到显示姓名的映射字典
        """
        name_to_display = {}
        for user in users:
            name = user.get('name')
            display = user.get('displayName')
            if name and display:
                name_to_display[name] = display
        return name_to_display


class DingTalkNotifier:
    """钉钉通知器"""

    def __init__(self, config: Dict, use_enterprise: bool = False):
        """
        初始化钉钉通知器

        Args:
            config: 配置文件字典
            use_enterprise: 是否使用企业钉钉（False使用个人钉钉）
        """
        if use_enterprise:
            self.config = config['dingtalk']['enterprise']
            self.bot_type = 'enterprise'
        else:
            self.config = config['dingtalk']['personal']
            self.bot_type = 'personal'

        self.message_template = self.config['message_template']

        # 新增：加载类型映射配置
        self.type_mappings = config.get('type_mappings', {})

        if self.bot_type == 'enterprise':
            # 企业钉钉需要更复杂的初始化
            self.agent_id = self.config['agent_id']
            self.client_id = self.config['client_id']
            self.client_secret = self.config['client_secret']
            self.robot_code = self.config['robot_code']
            self.access_token = None
        else:
            # 个人钉钉使用简单配置
            from dingTalkPerson import PersonalDingTalkBot
            self.bot = PersonalDingTalkBot(self.config['webhook_url'])

    def get_enterprise_access_token(self) -> Optional[str]:
        """获取企业钉钉的access token"""
        if self.bot_type != 'enterprise':
            return None

        url = "https://oapi.dingtalk.com/gettoken"
        params = {
            "appkey": self.client_id,
            "appsecret": self.client_secret
        }

        try:
            response = requests.get(url, params=params, timeout=10)
            result = response.json()

            if result.get("errcode") == 0:
                self.access_token = result.get("access_token")
                return self.access_token
            else:
                print(f"获取token失败: {result.get('errmsg')}")
                return None
        except Exception as e:
            print(f"请求异常: {str(e)}")
            return None

    def send_enterprise_message(self, user_ids: List[str], msg_content: str) -> bool:
        """发送企业钉钉消息"""
        if not self.access_token and not self.get_enterprise_access_token():
            return False

        url = f"https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2?access_token={self.access_token}"

        message_body = {
            "agent_id": self.agent_id,
            "msg": {
                "msgtype": "text",
                "text": {
                    "content": msg_content
                }
            },
            "userid_list": ",".join(user_ids)
        }

        headers = {
            "Content-Type": "application/json"
        }

        try:
            response = requests.post(url, json=message_body, headers=headers, timeout=10)
            result = response.json()

            if result.get("errcode") == 0:
                print(f"企业钉钉消息发送成功! Task ID: {result.get('task_id')}")
                return True
            else:
                print(f"企业钉钉消息发送失败: {result.get('errmsg')}")
                return False
        except Exception as e:
            print(f"企业钉钉请求异常: {str(e)}")
            return False

    def send_personal_message(self, msg_content: str) -> bool:
        """发送个人钉钉消息"""
        from dingTalkPerson import PersonalDingTalkBot
        bot = PersonalDingTalkBot(self.config['webhook_url'])
        return bot.send_text(msg_content)

    def format_message(self, **kwargs) -> str:
        """
        格式化消息

        Args:
            **kwargs: 消息模板变量

        Returns:
            格式化后的消息
        """
        return self.message_template.format(**kwargs)

    def format_message_with_type(self, tracker_type: str, **kwargs) -> str:
        """
        根据tracker类型格式化消息并应用前缀映射

        Args:
            tracker_type: tracker类型名称
            **kwargs: 消息模板变量

        Returns:
            格式化后的消息
        """
        # 先使用原有模板格式化消息
        message = self.message_template.format(**kwargs)

        # 应用类型映射
        if tracker_type in self.type_mappings:
            prefix = self.type_mappings[tracker_type]
            # 替换固定的字段前缀
            message = message.replace("名称:", f"{prefix}名称:")
            message = message.replace("状态:", f"{prefix}状态:")
            message = message.replace("链接:", f"{prefix}链接:")

        return message

    def notify(self, message: str, user_ids: Optional[List[str]] = None) -> bool:
        """
        发送通知

        Args:
            message: 消息内容
            user_ids: 企业钉钉的用户ID列表（个人钉钉不需要）

        Returns:
            发送成功返回True，否则返回False
        """
        if self.bot_type == 'enterprise':
            if not user_ids:
                print("企业钉钉需要指定user_ids")
                return False
            return self.send_enterprise_message(user_ids, message)
        else:
            return self.send_personal_message(message)


class WorkflowNotifier:
    """工作流通知器主类"""

    def __init__(self, config_path: str = "workflow_config.yaml"):
        """
        初始化工作流通知器

        Args:
            config_path: 配置文件路径
        """
        self.config = self.load_config(config_path)
        self.logger = setup_logging(self.config)
        self.codebeamer_client = CodeBeamerClient(self.config, self.config['environment'])
        self.dingtalk_notifier = DingTalkNotifier(self.config, use_enterprise=False)

        # 新增：记录类型映射配置
        if 'type_mappings' in self.config:
            self.logger.info(f"加载类型映射配置: {len(self.config['type_mappings'])}个类型")
        else:
            self.logger.info("未配置type_mappings，类型映射功能禁用")

        # 初始化Tracker匹配器
        if 'tracker_matching' in self.config:
            self.tracker_matcher = TrackerMatcher(self.config['tracker_matching'])
            self.logger.info("Tracker匹配器初始化完成")
        else:
            self.tracker_matcher = None
            self.logger.warning("配置中缺少tracker_matching部分")

        # 获取工作流模板和配置
        self.workflow_templates = self.config.get('workflow_templates', {})
        self.workflows = self.config.get('workflows', {})


        # 状态映射缓存：tracker_name -> {status_name: status_id}
        self.status_mappings = {}

        # tracker名称到ID的映射缓存
        self.tracker_name_to_id = {}

        # 模板到状态映射的缓存：template_name -> {status_name: notify_field}
        self.template_status_mappings = {}

    @staticmethod
    def load_config(config_path: str) -> Dict:
        """加载YAML配置文件"""
        if not os.path.exists(config_path):
            raise FileNotFoundError(f"配置文件不存在: {config_path}")

        with open(config_path, 'r', encoding='utf-8') as f:
            config = yaml.safe_load(f)

        return config

    def get_status_config(self, tracker_name: str, status_name: str, tracker_type: str) -> Optional[str]:
        """
        获取状态配置（根据状态名称获取通知字段）
        支持Tracker匹配器或多Tracker配置

        Args:
            tracker_name: tracker名称
            status_name: 当前状态名称
            tracker_type: tracker类型（必填）

        Returns:
            通知字段名称，未找到返回None

        Raises:
            ValueError: 如果tracker_name为空或tracker_type为空
        """
        # 检查tracker_name和tracker_type是否为空
        if not tracker_name:
            raise ValueError("Tracker名称不能为空")
        if not tracker_type:
            raise ValueError("Tracker类型不能为空")

        # 使用Tracker匹配器
        if not self.tracker_matcher:
            self.logger.error(f"Tracker匹配器未初始化，无法为'{tracker_name}'获取状态配置。请检查配置文件中是否有tracker_matching部分。")
            return None

        try:
            # 获取匹配的模板
            template_name = self.tracker_matcher.find_template(tracker_name, tracker_type)
            self.logger.info(f"Tracker '{tracker_name}' 匹配到模板: {template_name}")

            # 从缓存或配置中获取模板的状态映射
            if template_name in self.template_status_mappings:
                status_mappings = self.template_status_mappings[template_name]
            else:
                # 从workflow_templates配置中获取模板
                template_config = self.workflow_templates.get(template_name)
                if not template_config:
                    self.logger.warning(f"模板 '{template_name}' 未在workflow_templates中定义")
                    return None

                status_mappings = template_config.get('status_mappings', {})
                # 缓存模板的状态映射
                self.template_status_mappings[template_name] = status_mappings

            # 查找对应的通知字段
            notify_field = status_mappings.get(status_name)
            if notify_field:
                return notify_field

            self.logger.warning(f"在模板 '{template_name}' 中未找到状态配置: {status_name}")
            return None

        except ValueError as e:
            # 重新抛出ValueError，不要捕获
            raise e
        except Exception as e:
            self.logger.error(f"Tracker匹配器处理失败: {str(e)}")


    def get_tracker_statuses(self, tracker_name: str, item_info: Dict) -> Optional[Dict[str, int]]:
        """
        获取Tracker的状态映射（名称到ID）

        Args:
            tracker_name: tracker名称
            item_info: Tracker Item信息（用于获取tracker ID）

        Returns:
            状态名称到ID的映射字典，失败返回None
        """
        # 检查缓存
        if tracker_name in self.status_mappings:
            return self.status_mappings[tracker_name]

        # 从item信息中获取tracker ID
        tracker = item_info.get('tracker', {})
        tracker_id = tracker.get('id')

        if not tracker_id:
            self.logger.warning(f"无法获取tracker ID: {tracker_name}")
            return None

        # 缓存tracker ID
        self.tracker_name_to_id[tracker_name] = tracker_id

        # 通过API获取状态列表
        status_mapping = self.codebeamer_client.get_tracker_statuses(tracker_id)

        if status_mapping:
            self.status_mappings[tracker_name] = status_mapping
            self.logger.info(f"获取到{tracker_name}的状态映射: {len(status_mapping)}个状态")
            return status_mapping
        else:
            self.logger.warning(f"无法获取{tracker_name}的状态列表")
            return None

    def get_status_id_by_name(self, tracker_name: str, status_name: str, item_info: Dict) -> Optional[int]:
        """
        根据状态名称获取状态ID

        Args:
            tracker_name: tracker名称
            status_name: 状态名称
            item_info: Tracker Item信息

        Returns:
            状态ID，失败返回None
        """
        status_mapping = self.get_tracker_statuses(tracker_name, item_info)

        if status_mapping and status_name in status_mapping:
            return status_mapping[status_name]

        self.logger.warning(f"未找到状态ID: {tracker_name} -> {status_name}")
        return None

    def get_notify_users(self, item_info: Dict, notify_field: str) -> Dict[str, List[str]]:
        """
        获取需要通知的用户

        Args:
            item_info: Tracker Item信息
            notify_field: 通知字段名称

        Returns:
            包含用户姓名和显示姓名的字典
        """
        result = {
            'names': [],
            'display_names': []
        }

        # 内置字段处理
        if notify_field == 'assignedTo':
            assigned_users = item_info.get('assignedTo', [])
            result['names'] = [user.get('name') for user in assigned_users if user.get('name')]
            result['display_names'] = [user.get('displayName', user.get('name'))
                                      for user in assigned_users]

        elif notify_field == 'submitter':
            submitter = item_info.get('submitter')
            if submitter:
                result['names'] = [submitter.get('name')] if submitter.get('name') else []
                result['display_names'] = [submitter.get('displayName', submitter.get('name'))]

        elif notify_field in ['supervisors', 'owners']:
            supervisors = item_info.get('supervisors', [])
            result['names'] = [user.get('name') for user in supervisors if user.get('name')]
            result['display_names'] = [user.get('displayName', user.get('name'))
                                      for user in supervisors]

        # 自定义字段处理
        else:
            custom_fields = item_info.get('customFields', [])
            for field in custom_fields:
                field_name = field.get('name', '')
                # 处理可能的编码问题
                if isinstance(field_name, bytes):
                    field_name = field_name.decode('utf-8')

                if field_name == notify_field:
                    values = field.get('values', [])
                    result['names'] = [v.get('name') for v in values if v.get('name')]
                    result['display_names'] = [v.get('displayName', v.get('name'))
                                              for v in values]
                    break

        # 过滤空值
        result['names'] = [name for name in result['names'] if name]
        result['display_names'] = [name for name in result['display_names'] if name]

        return result

    def handle_status_notification(self, tracker_name: str, item_id: int,
                                  current_status: str, tracker_type: str) -> bool:
        """
        基于当前状态：处理状态通知

        Args:
            tracker_name: tracker名称
            item_id: Tracker Item ID
            current_status: 当前状态名称
            tracker_type: tracker类型（必填）

        Returns:
            处理成功返回True，否则返回False
        """
        self.logger.info(f"处理状态通知: {tracker_name} #{item_id} 状态: {current_status}")

        # 获取当前的状态应该通知的属性字段
        notify_field = self.get_status_config(tracker_name, current_status, tracker_type)
        if not notify_field:
            return False

        # 获取Item信息，返回条目的响应json
        item_info = self.codebeamer_client.get_item_info(item_id)
        if not item_info:
            self.logger.error(f"无法获取Item信息: {item_id}")
            return False

        # 获取需要通知的用户
        notify_users = self.get_notify_users(item_info, notify_field)
        if not notify_users['names']:
            self.logger.warning(f"未找到需要通知的用户: 字段={notify_field}")
            return False

        # 需通知属性的原名
        notify_field_name = notify_field

        # 获取tracker名称和链接
        item_name = item_info.get('name', '')
        common_item_id = item_info.get('commonItemId', '')

        # 构建项目链接
        if common_item_id:
            base_url = self.codebeamer_client.base_url
            item_url = f"{base_url}/issue/{common_item_id}"
        else:
            item_url = ""

        # 构建消息
        message_vars = {
            'tracker_name': tracker_name,
            'item_name': item_name,
            'status_name': current_status,
            'notify_field_name': notify_field_name,
            'notify_display_names': ','.join(notify_users['display_names']),
            'item_url': item_url
        }

        # 将tracker类型和消息模板进行匹配
        message = self.dingtalk_notifier.format_message_with_type(
            tracker_type, **message_vars
        )

        # 发送通知
        success = self.dingtalk_notifier.notify(
            message=message,
            user_ids=notify_users['names'] if self.dingtalk_notifier.bot_type == 'enterprise' else None
        )

        if success:
            self.logger.info(f"通知发送成功: {item_id}, 状态: {current_status}, 通知用户: {notify_users['names']}")
        else:
            self.logger.error(f"通知发送失败: {item_id}")

        return success

    def process_groovy_trigger(self, tracker_name: str, item_id: int,
                             current_status: str, tracker_type: str) -> Dict[str, Any]:
        """
        处理Groovy脚本触发的状态通知

        Args:
            tracker_name: tracker名称
            item_id: Tracker Item ID
            current_status: 当前状态名称
            tracker_type: tracker类型（必填）

        Returns:
            处理结果字典
        """
        try:
            success = self.handle_status_notification(tracker_name, item_id, current_status, tracker_type)

            return {
                'success': success,
                'message': '状态通知处理完成',
                'item_id': item_id,
                'current_status': current_status,
                'tracker_type': tracker_type
            }

        except Exception as e:
            self.logger.error(f"处理状态通知时发生异常: {str(e)}")

            return {
                'success': False,
                'message': f'处理失败: {str(e)}',
                'item_id': item_id,
                'current_status': current_status,
                'tracker_type': tracker_type
            }

    def process_groovy_trigger_by_id(self, item_id: int) -> Dict[str, Any]:
        """
        处理Groovy脚本触发的状态通知（只通过ID）

        Args:
            item_id: Tracker Item ID

        Returns:
            处理结果字典
        """
        try:
            # 获取Item信息
            item_info = self.codebeamer_client.get_item_info(item_id)
            if not item_info:
                self.logger.error(f"无法获取Item信息: {item_id}")
                return {
                    'success': False,
                    'message': f'无法获取Item信息: {item_id}',
                    'item_id': item_id
                }

            # 从Item信息中提取tracker_name和current_status
            tracker = item_info.get('tracker', {})
            tracker_name = tracker.get('name', '')
            if not tracker_name:
                self.logger.error(f"Item中缺少tracker名称: {item_id}")
                return {
                    'success': False,
                    'message': f'Item中缺少tracker名称: {item_id}',
                    'item_id': item_id
                }

            # 获取当前状态
            current_status = '未知'
            # 尝试从status字段获取状态
            status_info = item_info.get('status')
            if status_info:
                current_status = status_info.get('name', '未知')

            # 如果状态是"未知"，记录调试信息
            if current_status == '未知':
                self.logger.warning(f"状态提取失败，item_id={item_id}")
                self.logger.warning(f"status字段值: {status_info}")

            # 获取条目类型----> tracker类型
            tracker_type = item_info.get('typeName', None)
            if not tracker_type:
                self.logger.error(f"无法获取tracker类型，item_id={item_id}")
                return {
                    'success': False,
                    'message': f'无法获取tracker类型，item_id={item_id}',
                    'item_id': item_id,
                    'tracker_name': tracker_name,
                    'current_status': current_status
                }

            # 调用处理函数，传递tracker类型
            success = self.handle_status_notification(tracker_name, item_id, current_status, tracker_type)

            return {
                'success': success,
                'message': '状态通知处理完成',
                'item_id': item_id,
                'tracker_name': tracker_name,
                'current_status': current_status
            }

        except Exception as e:
            self.logger.error(f"处理状态通知时发生异常: {str(e)}")

            return {
                'success': False,
                'message': f'处理失败: {str(e)}',
                'item_id': item_id
            }


def main():
    """主函数（用于测试）"""
    # 解析命令行参数
    if len(sys.argv) < 2:
        print("用法: python workflow_notifier.py <item_id>")
        print("示例: python workflow_notifier.py 1h2345")
        sys.exit(1)

    item_id = int(sys.argv[1])

    try:
        notifier = WorkflowNotifier()

        # 只传递ID，在process_groovy_trigger内部获取tracker_name和current_status
        result = notifier.process_groovy_trigger_by_id(item_id)

        if result['success']:
            print(f"成功: {result['message']}")
            sys.exit(0)
        else:
            print(f"失败: {result['message']}")
            sys.exit(1)

    except Exception as e:
        print(f"程序异常: {str(e)}")
        sys.exit(1)


if __name__ == "__main__":
    main()