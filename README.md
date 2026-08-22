# Yang AI Code Mother — AI 零代码应用生成平台

**一句话需求 → 自动生成可运行的网页应用**。输入自然语言，AI 生成完整代码并以 SSE 流式输出实时预览，支持对话式改代码、可视化点选编辑、一键部署到 nginx 公网访问。生成完的网站可以直接用、可以继续"聊"着改。

```
用户一句话需求
     │
     ▼
┌────────────────────────────────────────────────────────────┐
│  CodeGenFacade（核心门面）按 codeGenType 分派                 │
│                                                            │
│  HTML 模式     → 单文件 index.html（严格 JSON 输出）          │
│  多文件模式    → index.html + style.css + script.js         │
│  Vue 项目模式  → Agent 用 writeFile 工具逐个生成几十个文件     │
│                                                            │
│       │ SSE 流式（SseEmitter + 心跳）                        │
│       ▼                                                     │
│  实时预览（iframe）→ 对话改代码（chat/send）→ 可视化点选编辑     │
│       │                                                     │
│       ▼                                                     │
│  一键部署 → nginx（{deployKey}/ 公网访问）· ZIP 下载          │
└────────────────────────────────────────────────────────────┘
```

## 三种生成模式

| 模式 | 产物 | 生成方式 |
|---|---|---|
| **HTML 单页** | 单个 `index.html` | 单轮生成，强制严格 JSON 输出 `{htmlCode, description}` |
| **原生多文件** | `index.html` + `style.css` + `script.js` | 单轮生成，结构化 JSON |
| **Vue 工程** | 完整 Vue 项目（几十个文件） | **Agent 工具调用**：`writeFile(path, content)` 逐文件生成，token 预算管控 |

## 核心能力

| 能力 | 说明 |
|---|---|
| **一句话生成** | 自然语言需求 → 大模型生成代码 → SSE 逐字流式输出 → 实时预览 |
| **对话即改代码** | 生成后继续对话（"把标题改成蓝色"），AI 重写代码，已部署的自动重新部署 |
| **可视化点选编辑** | iframe 内注入编辑脚本 + postMessage 跨窗口通信，点选改文字/样式 |
| **一键部署** | 生成代码落到 nginx 站点根，公网 `/{deployKey}/` 访问，支持流式部署进度 |
| **代码下载** | Vue 工程打包 ZIP 下载 |
| **对话记忆** | Redis 短缓存（TTL 续期）+ MySQL 持久主存储，游标（keyset）分页 |
| **精选应用广场** | 公开应用列表 + 封面 PNG（本地 Chrome 截图生成） |

## 技术栈

- **后端**：Spring Boot **4.1** · Java **21** · MyBatis-Flex（`spring-boot4-starter`）· MySQL 8 · Redis（Jedis）
- **LLM**：**langchain4j 1.18** + DeepSeek（`@Tool` 函数调用）；刻意不用 langchain4j 的 Spring Boot starter（基于 Boot 3.5 编译，与 Boot 4 不兼容），模型 Bean 全部手动装配
- **前端**：Vue 3.5 + TypeScript + Vite 8 + Ant Design Vue 4 + Pinia（黑客终端绿黑风格）
- **接口文档**：Knife4j（OpenAPI3）

## 核心接口（统一前缀 `/api`）

**应用 `/app`**：`generate`（同步）· `generate/stream`（**SSE 流式生成**）· `code` · `code/edit-text` · `code/edit-style`（可视化编辑）· `deploy` · `deploy/stream`（**SSE 部署进度**）· `download`（ZIP）· `my/list/page` · `featured/list/page`（广场）· `cover/{appId}`

**对话 `/chat`**：`add` · `send`（**SSE 流式对话 + 对话即改代码**）· `my/conversations` · `list/page` · `cursor/list`（游标分页）· `admin/list/page`

**用户 `/user`**：`register` · `login` · `logout` · `get/login` · `admin/*`

## 工程亮点

1. **Agent 生成完整 Vue 工程**：`VueProjectTool` 暴露 `writeFile` / `finishProject` 两个 `@Tool`，路径安全校验（拒绝绝对路径 / `..` / 盘符 / 反斜杠），`AiServices` 临时构建（工具实例绑定本次 projectDir/预算/回调）
2. **双预算管控**：`VueProjectTokenBudget` 文件数上限（100）+ 累计字符上限（200000），`synchronized` 校验，超限把拒绝原因作为工具返回值回传给模型继续协商
3. **确定性加固代替模型自修循环**：`VueProjectScaffolder` 黄金模板补齐 6 个关键文件 + `package.json` 深度合并；`VueImportRepairer` 为缺失 import 补可见占位桩——生成期与部署期各跑一遍
4. **三级降级解析器** `CodeParser`：JSON → Markdown 代码块 → 裸 HTML；文件名三级命名优先级
5. **代码净化** `CodeSanitizer`：移除 `a` 标签 / base / 外部 url / location 跳转（Vue 模式例外）
6. **SSE + fetch ReadableStream 手动解析**：EventSource 不支持 POST；`data:` 字段不能 trim（空格是代码的一部分）
7. **双存储对话记忆**：Redis 短缓存（`setex` 续期）未命中时从 MySQL 重建最近 10 条回填
8. **雪花 ID 转字符串**：JacksonConfig 全局 Long → String，防前端 JS 精度丢失
9. **绝对路径零泄漏**：`CodeGenResult.saveDir` 加 `@JsonIgnore`
10. **DeepSeek 禁用 thinking**：首 token 延迟从 38~95s 降到 ~1.3s（`customParameters({"thinking":{"type":"disabled"}})`）
11. **流式健壮性**：`SseEmitter` 6 分钟超时 + 5 秒心跳；生成失败解析重试一次；单文件/多文件模式代码落盘后才返回

## 数据库（`yang_ai_code_mother`）

| 表 | 说明 |
|---|---|
| `app` | 应用（appName / initPrompt / codeGenType / deployKey 唯一 / 优先级 / userId，逻辑删除） |
| `chat_history` | 对话历史（message / messageType / appId / userId），`idx_appId_createTime` 支撑游标分页 |
| `app_cover` | 应用封面 PNG（1:1 关联 app） |

> `user` 表：实体已定义，但建表 DDL 未随仓库提供，环境初始化需手动补建。

## 快速开始

依赖：MySQL 8（库 `yang_ai_code_mother`）、Redis、DeepSeek API Key。

```bash
# 1. 建库（create_table.sql + 手动补建 user 表）
mysql -u root -p < sql/create_table.sql

# 2. 配置 application-local.yml（数据库 / Redis / DeepSeek key）
#    密钥仅写本地 profile，已 .gitignore 忽略，勿提交

# 3. 启动后端（端口 8224，/api）
./mvnw spring-boot:run
# 接口文档 http://localhost:8224/api/doc.html

# 4. 启动前端（端口 5173，/api 代理到 8224）
cd yang-ai-code-monther-frontend && npm install && npm run dev
```

**nginx 部署**：前端 `npm run build` 产物 + `/api/` 反代 8224 + `/apps/` 静态站点；SSE 必须 `proxy_buffering off`（示例见 `nginx/prod-nginx.conf.example`）。

## 配置项（环境变量 / local profile，勿提交真实值）

- `spring.datasource.*`、`chat.memory.redis.*`（host/port/password/ttl）
- `langchain4j.open-ai.chat-model.*`（base-url / api-key / model-name，DeepSeek）
- `code.deploy.*`（source-root 生成目录 / web-root 站点根 / base-url）
- `code.vue.*`（max-tokens / max-files / scaffold-on-generate）
- `code.cover.*`（封面截图：chrome-path / settle-ms）

## 说明

- 生成代码归属用户、仅供学习与个人使用，部署内容请遵守相关法律法规与版权要求
- 仓库不含任何真实 API Key / 数据库密码，均需在本地 profile 或环境变量自行填写
