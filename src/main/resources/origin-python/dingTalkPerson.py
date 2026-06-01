# -*- coding: utf-8 -*-
import requests
import json

class PersonalDingTalkBot:
    """
    个人钉钉机器人Webhook客户端
    使用钉钉机器人的Webhook地址发送消息
    """

    def __init__(self, webhook_url):
        """
        初始化个人钉钉机器人

        Args:
            webhook_url: 钉钉机器人的Webhook地址
        """
        self.webhook_url = webhook_url

    def send_text(self, content):
        """
        发送文本消息

        Args:
            content: 消息内容

        Returns:
            bool: 发送成功返回True，否则返回False
        """
        message = {
            "msgtype": "text",
            "text": {
                "content": content
            }
        }
        return self._send_message(message)

    
    def _send_message(self, message):
        """发送消息到钉钉Webhook"""
        headers = {"Content-Type": "application/json"}

        try:
            response = requests.post(
                self.webhook_url,
                data=json.dumps(message),
                headers=headers,
                timeout=10
            )
            result = response.json()
            return result.get("errcode") == 0
        except Exception:
            return False
