# remediation-orchestrator-java

本地可运行的告警修复编排能力：策略匹配生成修复任务，覆盖重复告警去重、维护窗口抑制、
人工审批拦截、失败重试与重启恢复，任务状态与审计记录全程可追溯。仅依赖本地资源与
Spring Boot 已有依赖，不接入外部服务。

快速开始：

```bash
mvn test          # 34 个用例，覆盖去重/并发/维护窗口/审批/重试/关停与重启恢复/复发升级
mvn spring-boot:run
```

设计、状态模型、配置项、恢复语义与本地验证方法见：
[docs/remediation-orchestrator.md](docs/remediation-orchestrator.md)
