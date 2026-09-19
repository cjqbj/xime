# Xime 输入法

## 项目简介
这是一个基于 rime 框架实现的安卓手机输入法，采用 kotlin + jetpack compose 构建。

## 快速开始
- 构建： `./build.sh`
- 测试： `./gradlew test`

## 插件开发
- 清除插件数据： `./gradlew clearPlugins`
- 完全卸载主应用： `./gradlew uninstallApp`
- [插件开发指南](https://ime.ximei.me/plugins/PLUGIN_DEVELOPMENT_GUIDE) - 开发插件时必读

## 硬性规则（必须遵守，CI 会验证）
- 禁止自己提交代码
- PR 必须遵循最小修改原则
- 贡献前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)
- 禁止使用 `./gradlew clean`
- 必须使用中文回复
- 修改功能时，需要审查是否影响到其他功能。
- UI 要遵循material 3 设计

## 工作规则
- 每次只做一个功能点
- 当前功能点端到端验证通过后，才能开始下一个
- 不要在实现功能 A 时"顺便"重构功能 B
- 当觉得有必要时，就添加单元测试


## 每次会话开始时（上班打卡）
1. 读 PROGRESS.md 了解当前状态, 没有就创建
2. 读 DECISIONS.md 了解重要决策 , 没有就创建
3. 从 PROGRESS.md 的"下一步"部分继续工作

## 每次会话结束前（下班打卡）
1. 更新 PROGRESS.md
2. 跑 `./build.sh` 确认没有错误
3. 提交所有已完成的工作

## Jetpack Compose
For all Compose/Android UI tasks, follow the instructions in
`.skills/compose-expert/SKILL.md` and consult the reference
files in `.skills/compose-expert/references/` before answering.

## rpc功能 用与暴露内部状态，控制和调试
/workspaces/Xime/app/src/main/python/rpc.py
尽量保证所有功能都能被rpc操作
例子：
ip='192.168.1.106'
!curl "http://{ip}:1144/from%20com.kingzcheung.xime.util%20import%20RpcUiController%3Br=RpcUiController.setOnlySchema('pinyin_simp')"
{"ok":true,"id":"pinyin_simp","name":"简体拼音","switched":true,"requires_deploy":false}