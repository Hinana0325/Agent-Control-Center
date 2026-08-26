package com.agentcontrolcenter.desktop.core.error

/**
 * 应用统一错误码注册表 — 与 Android (Kotlin) / iOS (Swift) 端
 * `protocol/schemas/error-codes.json` 完全对齐（37 个错误码，10 个类别）。
 *
 * 三端必须使用完全相同的 code/name/message/category，
 * 禁止硬编码未登记的错误码字符串。
 */
enum class AppErrorCode(
    val code: Int,
    val errorName: String,
    val message: String,
    val category: String
) {
    // Transport (1xxx)
    TRANSPORT_CONNECT_FAILED(1001, "TRANSPORT_CONNECT_FAILED", "传输连接失败：无法建立到服务端的连接", "Transport"),
    TRANSPORT_AUTH_FAILED(1002, "TRANSPORT_AUTH_FAILED", "传输认证失败：API Key 无效或未授权", "Transport"),
    TRANSPORT_TIMEOUT(1003, "TRANSPORT_TIMEOUT", "传输超时：请求在规定时间内未收到响应", "Transport"),
    TRANSPORT_DISCONNECTED(1004, "TRANSPORT_DISCONNECTED", "传输连接断开：连接被异常关闭", "Transport"),
    TRANSPORT_RECONNECT_FAILED(1005, "TRANSPORT_RECONNECT_FAILED", "传输重连失败：自动重连尝试未成功", "Transport"),

    // Protocol (2xxx)
    PROTOCOL_INVALID_MESSAGE(2001, "PROTOCOL_INVALID_MESSAGE", "协议消息无效：消息不符合协议规范", "Protocol"),
    PROTOCOL_UNKNOWN_TYPE(2002, "PROTOCOL_UNKNOWN_TYPE", "协议未知类型：收到无法识别的消息类型", "Protocol"),
    PROTOCOL_PARSE_ERROR(2003, "PROTOCOL_PARSE_ERROR", "协议解析错误：JSON 解析或字段提取失败", "Protocol"),
    PROTOCOL_VERSION_MISMATCH(2004, "PROTOCOL_VERSION_MISMATCH", "协议版本不匹配：客户端与服务端协议版本不兼容", "Protocol"),

    // Agent (3xxx)
    AGENT_NOT_FOUND(3001, "AGENT_NOT_FOUND", "Agent 未找到：指定的 Agent 不存在", "Agent"),
    AGENT_OFFLINE(3002, "AGENT_OFFLINE", "Agent 离线：目标 Agent 当前不在线", "Agent"),
    AGENT_NO_CAPABILITY(3003, "AGENT_NO_CAPABILITY", "Agent 无此能力：Agent 不支持所需能力（如 WORKFLOW、MCP）", "Agent"),
    AGENT_CONFIG_MISSING(3004, "AGENT_CONFIG_MISSING", "Agent 配置缺失：缺少必要的服务端地址或 API Key", "Agent"),
    AGENT_RESPONSE_EMPTY(3005, "AGENT_RESPONSE_EMPTY", "Agent 响应为空：Agent 返回了空内容", "Agent"),

    // Session (4xxx)
    SESSION_NOT_FOUND(4001, "SESSION_NOT_FOUND", "会话未找到：指定的 Session 不存在", "Session"),
    SESSION_CREATE_FAILED(4002, "SESSION_CREATE_FAILED", "会话创建失败：无法创建新会话", "Session"),

    // Task (5xxx)
    TASK_NOT_FOUND(5001, "TASK_NOT_FOUND", "任务未找到：指定的 Task 不存在", "Task"),
    TASK_ALREADY_RUNNING(5002, "TASK_ALREADY_RUNNING", "任务已在运行：不能重复启动正在运行的任务", "Task"),
    TASK_CANCELLED(5003, "TASK_CANCELLED", "任务已取消：任务被用户或系统取消", "Task"),
    TASK_TIMEOUT(5004, "TASK_TIMEOUT", "任务超时：任务执行超过最大允许时间", "Task"),

    // Workflow (6xxx)
    WORKFLOW_INVALID_DAG(6001, "WORKFLOW_INVALID_DAG", "工作流无效 DAG：节点与边构成非法的有向图（如存在孤立必经节点）", "Workflow"),
    WORKFLOW_NODE_FAILED(6002, "WORKFLOW_NODE_FAILED", "工作流节点失败：单个节点执行出错（含 60 秒超时）", "Workflow"),
    WORKFLOW_CYCLE_DETECTED(6003, "WORKFLOW_CYCLE_DETECTED", "工作流检测到环：图中存在循环依赖，无法拓扑排序", "Workflow"),
    WORKFLOW_TIMEOUT(6004, "WORKFLOW_TIMEOUT", "工作流超时：整个工作流执行超过最大允许时间", "Workflow"),

    // Plugin (7xxx)
    PLUGIN_NOT_FOUND(7001, "PLUGIN_NOT_FOUND", "插件未找到：指定的 Plugin 不存在", "Plugin"),
    PLUGIN_DISABLED(7002, "PLUGIN_DISABLED", "插件已禁用：Plugin.isEnabled 为 false，无法执行", "Plugin"),
    PLUGIN_EXECUTION_FAILED(7003, "PLUGIN_EXECUTION_FAILED", "插件执行失败：PluginAction 执行出错（HTTP 调用失败、Intent 发送失败等）", "Plugin"),

    // MCP (8xxx)
    MCP_SERVER_UNREACHABLE(8001, "MCP_SERVER_UNREACHABLE", "MCP 服务端不可达：无法连接到 MCP Server 的 transportUrl", "MCP"),
    MCP_TOOL_NOT_FOUND(8002, "MCP_TOOL_NOT_FOUND", "MCP 工具未找到：指定的工具在 MCP Server 上不存在", "MCP"),
    MCP_TOOL_EXECUTION_ERROR(8003, "MCP_TOOL_EXECUTION_ERROR", "MCP 工具执行错误：tools/call 返回 isError=true 或调用异常", "MCP"),
    MCP_INITIALIZATION_FAILED(8004, "MCP_INITIALIZATION_FAILED", "MCP 初始化失败：initialize 握手失败或能力协商出错", "MCP"),

    // File (9xxx)
    FILE_TOO_LARGE(9001, "FILE_TOO_LARGE", "文件过大：文件大小超过 10MB 上限（压缩前）", "File"),
    FILE_NOT_FOUND(9002, "FILE_NOT_FOUND", "文件未找到：指定的本地文件不存在", "File"),
    FILE_TRANSFER_FAILED(9003, "FILE_TRANSFER_FAILED", "文件传输失败：上传或下载过程中出错", "File"),

    // Crypto (10xxx)
    CRYPTO_DECRYPT_FAILED(10001, "CRYPTO_DECRYPT_FAILED", "解密失败：AES-256-GCM 解密失败（密文损坏或 IV 不匹配）", "Crypto"),
    CRYPTO_KEYSTORE_UNAVAILABLE(10002, "CRYPTO_KEYSTORE_UNAVAILABLE", "密钥库不可用：密钥存储不可用或未授权", "Crypto"),
    CRYPTO_E2E_KEY_MISMATCH(10003, "CRYPTO_E2E_KEY_MISMATCH", "端到端密钥不匹配：E2E 加密的密钥协商结果不一致", "Crypto");

    companion object {
        /** 按 code 整数反查错误码枚举，未登记返回 null。 */
        fun fromCode(code: Int): AppErrorCode? =
            entries.firstOrNull { it.code == code }
    }
}
