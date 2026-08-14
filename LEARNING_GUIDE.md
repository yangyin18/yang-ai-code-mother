# yang-ai-code-mother 项目完整学习文档

> 目标：让你能向任何人（面试官、同事、朋友）清楚解释这个项目的**每一个细节**，并且能应对各种角度的追问。
> 本文档覆盖：项目概述 → 架构 → 核心流程 → 逐模块代码细节 → 数据库 → 部署 → 项目亮点 → 面试 Q&A。

---

## 目录

1. [项目概述（30 秒电梯陈述）](#一项目概述30-秒电梯陈述)
2. [系统架构总览](#二系统架构总览)
3. [技术栈与选型理由](#三技术栈与选型理由)
4. [核心业务流程详解](#四核心业务流程详解)
5. [AI 模块代码细节](#五ai-模块代码细节)
6. [核心生成管线（解析/保存/净化）](#六核心生成管线解析保存净化)
7. [Vue 项目质量加固](#七vue-项目质量加固)
8. [业务服务与权限体系](#八业务服务与权限体系)
9. [通用设施（返回格式/异常/序列化/CORS）](#九通用设施返回格式异常序列化cors)
10. [数据库设计](#十数据库设计)
11. [前端详解](#十一前端详解)
12. [部署与运维](#十二部署与运维)
13. [项目亮点（面试加分区）](#十三项目亮点面试加分区)
14. [面试 Q&A 大全](#十四面试-qa-大全)
15. [快速启动指南](#十五快速启动指南)

---

## 一、项目概述（30 秒电梯陈述）

> **一句话定位**：这是一个 **AI 零代码应用平台** —— 用户输入一句话需求，AI 自动生成完整可运行的网页应用（纯 HTML 页面、多文件页面、或者**完整的 Vue 工程**），并支持在线实时预览、对话式修改、可视化编辑、一键部署到 nginx 公网访问。

**三个核心卖点**：
1. **一句话生成**：输入自然语言需求 → AI 生成代码 → 流式呈现 → 实时预览。
2. **对话即改代码**：生成后可以继续对话（"把标题改成蓝色"），AI 直接改代码并自动重新部署。
3. **Vue 深度开发模式**：AI 作为一个 agent，通过工具调用（`writeFile`）逐个生成完整 Vue 工程文件，并在本地构建、部署。

**项目规模**：后端 Java（Spring Boot 4.1.0 / Java 21）约 100+ 类文件，前端 Vue 3 + TypeScript + Vite 约 40 个文件，前后端通过 SSE 流式通信。

**技术栈一句话**：Spring Boot 4.1 + MyBatis-Flex + langchain4j（接 DeepSeek 大模型）+ Redis + MySQL + nginx，前端 Vue 3 + Vite + Pinia + Ant Design Vue + TypeScript。

**三种代码生成模式**（`CodeGenTypeEnum`）：
| 模式 | value | 产出 | 特点 |
|---|---|---|---|
| 原生 HTML | `html` | 单个 index.html | 最简单，快速 |
| 原生多文件 | `multi_file` | index.html + style.css + script.js | 三文件分离 |
| Vue 项目 | `vue` | 完整 Vue 工程（几十个文件） | agent 工具调用，最复杂 |

---

## 二、系统架构总览

### 2.1 整体架构图

```
┌─────────────────────────────────────────────────────────────┐
│                        前端 (Vue 3 + Vite)                    │
│  HomeView / GenerateView / ChatView / VueProjectView / ...    │
│  Pinia store · fetch ReadableStream 解析 SSE · 可视化编辑     │
└──────────────┬──────────────────────────────┬────────────────┘
               │ /api (开发代理到 8224)          │ nginx 80 托管部署产物
               ▼                                ▼
┌─────────────────────────────────────────────┐   ┌────────────────────┐
│   Spring Boot 后端 (8224, context-path=/api) │   │  nginx 静态服务器    │
│                                             │   │  http://localhost/   │
│  Controller 层 (App/User/ChatHistory/Health)│   │  apps/{deployKey}/   │
│      │  AOP AuthCheck 切面鉴权               │   └────────────────────┘
│      ▼                                      │
│  Service 层 (AppService/ChatHistoryService/  │
│            UserService/DeployService)        │
│      │                                      │
│      ▼                                      │
│  CodeGenFacade（核心门面，编排 AI + 解析+落盘） │
│      │                                      │
│      ▼                                      │
│  AI 层 (langchain4j AiServices 动态代理)      │
│   AiCodeGeneratorService（生成 HTML/多文件）  │
│   AiVueProjectService（Vue agent + 工具调用） │
│   AiChatService（对话，带 Redis 记忆）        │
│      │                                      │
│      ▼                                      │
│  核心管线：CodeParser(解析) → CodeSaver(落盘) │
│            → CodeSanitizer(净化) → Deploy    │
│  质量加固：VueProjectScaffolder +            │
│            VueImportRepairer                 │
│                                             │
│  基础设施：MySQL(持久) · Redis(对话短缓存)    │
│            · DeepSeek API(大模型) · nginx    │
└─────────────────────────────────────────────┘
```

### 2.2 分层职责

| 层 | 位置 | 职责 |
|---|---|---|
| Controller | `controller/` | 接收 HTTP/SSE 请求，参数校验，返回 `BaseResponse<T>` |
| Service | `service/` | 业务逻辑、数据权限校验、事务 |
| 门面 | `core/CodeGenFacade` | **核心编排器**：AI 调用 + 解析 + 落盘 + 加固，一个门面管三种生成模式 |
| AI | `ai/` | langchain4j 服务接口 + 手动装配的 Bean + Redis 记忆 + Vue 工具 |
| 核心管线 | `core/parser` `core/saver` `core/CodeSanitizer` | 解析 AI 输出为结构化文件 → 净化 → 落盘 |
| 质量加固 | `service/VueProjectScaffolder` `service/VueImportRepairer` | 保证 Vue 工程可构建 |
| Mapper | `mapper/` | MyBatis-Flex `BaseMapper`，零 SQL/XML |
| 通用 | `common/` `exception/` `config/` `annotation/` `aop/` | 返回格式、异常、序列化、CORS、鉴权 |

### 2.3 前后端通信方式

- **普通接口**：axios + `BaseResponse<T>`（`code=0` 成功）。
- **流式接口**：SSE（Server-Sent Events），POST 方式。
  - ⚠️ 原生 `EventSource` 只支持 GET，所以**前端用 `fetch` 拿 `ReadableStream` 手动解析 SSE 协议**（`src/api/generation.ts` 的 `parseSse`）。
- **可视化编辑**：父页面 `postMessage` ↔ iframe 内的注入脚本。

---

## 三、技术栈与选型理由

### 3.1 后端

| 技术 | 版本 | 用途 / 为什么选 |
|---|---|---|
| Spring Boot | 4.1.0 | Web 框架。**注意：用了最新的 Boot 4**，这是很多坑的来源 |
| Java | 21 | 语言（record、switch 表达式、模式匹配） |
| MyBatis-Flex | 1.11.8 | 轻量 ORM，比 MyBatis-Plus 更轻、支持 Boot 4 的 starter（`mybatis-flex-spring-boot4-starter`） |
| langchain4j | 1.18.1 | LLM 集成框架（AI 服务接口 + 工具调用 + 对话记忆） |
| Redis (jedis) | 7.4.1 | 对话记忆短缓存 |
| MySQL | 8.x | 主存储（app / user / chat_history） |
| Hutool | 5.8.32 | 工具类（文件操作 FileUtil 等） |
| knife4j | 4.4.0 | OpenAPI 接口文档（`/api/doc.html`） |

### 3.2 前端

| 技术 | 版本 | 用途 |
|---|---|---|
| Vue | 3.5 | 组合式 API + `<script setup>` |
| Vite | 8.x | 构建/开发服务器 |
| TypeScript | ~6.0 | 类型安全 |
| Pinia | 4.x | 状态管理（user / generation 两个 store） |
| Vue Router | 4.x | 10 个路由，HTML5 history 模式 |
| Ant Design Vue | 4.x | UI 组件库（深色主题） |
| axios | 1.x | HTTP 客户端 |
| highlight.js | 11.x | 代码高亮 |

### 3.3 关键选型决策（面试必问）

**Q: 为什么不用 langchain4j 的 spring-boot-starter？**

因为项目用了 **Spring Boot 4**。langchain4j 官方 starter 基于 Spring Boot 3.5 编译，其 `SpringRestClient` 依赖 `org.springframework.boot.http.client.ClientHttpRequestFactorySettings`，这个类在 Spring Boot 4 已经被移除，启动即抛 `NoClassDefFoundError`，而且当时没有支持 Boot 4 的版本。

**解决方案**：改用纯 `langchain4j-open-ai` 核心包（内部用 JDK 原生 HttpClient，不依赖 Spring），加上 `langchain4j` 高层模块（提供 AiServices、@SystemMessage 等）。模型 Bean 全部由 `ChatModelConfig` 手动 `builder().build()` 构建。这也是 `AiCodeGeneratorServiceFactory` 需要手动装配 AiServices 的根因。

**Q: 为什么用 MyBatis-Flex 而不是 MyBatis-Plus / JPA？**

- MyBatis-Flex 比 Plus 更轻量，**有专门适配 Spring Boot 4 的 starter**（`mybatis-flex-spring-boot4-starter`），这是最大理由。
- 实体注解映射 + `BaseMapper`，零 XML；查询用 `QueryWrapper` lambda 语法。
- 支持雪花 ID 主键、逻辑删除（`@Column(isLogicDelete=true)`）。

**Q: 大模型用的是哪个？**

DeepSeek（OpenAI 兼容协议），模型 `deepseek-v4-flash`，base-url `https://api.deepseek.com`。通过 langchain4j 的 `OpenAiChatModel` 接入。

**关键优化**：DeepSeek 是推理模型，代码生成时默认会先输出大段 `reasoning_content`（思考过程），导致**首个正文 token 要等 38~95 秒**。通过 `customParameters({"thinking": {"type": "disabled"}})` 禁用思考后，首 token 降到 ~1.3s，总耗时从 ~95s 降到 ~10s。这是一个非常体现工程感的调优点。

---

## 四、核心业务流程详解

### 4.1 用户注册 / 登录流程

**注册**（`UserServiceImpl.userRegister`）：
1. 参数校验：非空、账号 ≥ 4、密码 ≥ 8、两次一致。
2. 查重：按 `userAccount` 查库，存在即抛"账号重复"。
3. 加密：`MD5(密码 + 固定盐"yang")`。
4. 建实体：`userName="无名"`、`userRole="user"`、save，返回 id。

**登录**（`UserServiceImpl.login`）：
1. 按账号查库。
2. 校验 `user.getUserPassword().equals(加密后的密码)`，失败统一抛"账号或密码错误"（**防枚举账号**）。
3. 成功 → `request.getSession().setAttribute("user_login_state", user)`（**Session 保存登录态**）。
4. 返回脱敏的 `LoginUserVO`。

**为什么用 Session 而不是 JWT？**
- 单体应用、无分布式需求，Session 最简单。
- 支持**实时失效**：账号被删/禁，因为每次请求都重新查库，下一请求立刻拒绝；JWT 做不到（需黑名单）。
- 每次请求 `getLoginUser` 都用 session 里的 id **重新查库刷新**，避免脏数据。
- 代价：Tomcat 内存 Session 无法横向扩展（多实例需引入 spring-session）；密码是 MD5+固定盐（弱加密，见改进项）。

### 4.2 代码生成全流程（HTML / 多文件，非流式）

```
POST /api/app/generate  (AppController.generate)
  → CodeGenFacade.generate(requirement, appId)
    → 按 app.codeGenType 分派（switch 表达式）
      → generateHtml / generateMultiFile
        → buildPrompt(initPrompt, userMessage)   # 基础指令 + 本次需求
        → aiCodeGeneratorService.generateCode(prompt)   # 调大模型，返回 POJO
        → CodeSanitizer.sanitize(result)         # 安全净化（移除外部链接）
        → CodeSaver.saveHtml / saveMultiFile      # 落盘到 tmp/code_output/{bizType}_{appId}
        → 组装 CodeGenResult 返回
```

`AiCodeGeneratorService` 是 langchain4j 的接口，`@SystemMessage(fromResource = "prompt/codegen-html-prompt.txt")`，提示词**强制要求模型输出严格 JSON**：`{"htmlCode": "...", "description": "..."}`（多文件模式是 4 个字段）。非流式路径直接由 AiServices 反序列化到 POJO。

### 4.3 流式生成（SSE）流程 —— 项目核心

**后端**（`AppController.generateStream`）：
1. 创建 `SseEmitter(600_000L)`（6 分钟超时）。
2. 注册**心跳**：每 5 秒推 `heartbeat` 事件，防代理/防火墙断开连接（首个 message 到达后取消）。
3. 连接建立立即推 `started` 事件。
4. 调用 `codeGenFacade.generateStream(...)`，实现 `CodeGenStreamCallback` 回调接口：
   - `onPartial` → 推 `message` 事件（增量文本，**过滤整段空白但绝不 trim** —— trim 会吃掉代码开头的空格）。
   - `onComplete` → 推 `complete` 事件（data 是 CodeGenResult JSON）。
   - `onError` → `emitter.completeWithError(error)`。
5. **异步执行**：模型流式回调里完成 `CodeParser.parse → CodeSaver.saveFiles → callback.onComplete`。

**前端**：
- 因为 EventSource 不支持 POST，前端用 `fetch` 拿 `body.getReader()` + `TextDecoder`，**按 `\n\n` 手动切分 SSE 帧**，解析 `event:` / `data:` 行。
- ⚠️ 关键细节：`data:` 内容**不能 `trimStart`** —— 空格是代码的一部分（缩进、`<!DOCTYPE html>` 前的空格）。
- 事件分派：`started` / `heartbeat` / `file` 不进代码缓冲；`message` 累积到 `streamingCode`；`complete` 解析 JSON 存结果并 resolve。
- 显示层：`extractHtmlCode(raw)` 清洗流（剥 Markdown 围栏 → 解析 JSON 包裹 → 裸 HTML），配合**打字机光标**（`▋` CSS 闪烁）实现逐字输出效果。

### 4.4 Vue 项目生成（agent 工具调用）流程

这是最亮眼的部分，AI 不是"一次输出代码"，而是**作为一个 agent，通过工具调用逐个写文件**：

```
CodeGenFacade.generateVueProject / generateVueProjectStream
  → prepareVueDir(appId)      # CodeSaver.resolveDir("vue", appId) + 清空旧产物
  → new VueProjectTokenBudget(vueMaxTokens=200000, vueMaxFiles=100)
  → new VueProjectTool(projectDir, budget, onFileWritten)
  → buildVueService(tool)     # AiServices.builder(AiVueProjectService.class)
                                  .chatModel(chatModel)
                                  .streamingChatModel(vueStreamingChatModel)
                                  .tools(tool)      # 注入工具！
                                  .build()
  → service.generateVueProjectStream(prompt)
    → 模型: 先输出计划 → 逐个调用 tool.writeFile(path, content) → 最后 tool.finishProject(summary)
    → 每写一个文件，onFileWritten 回调推 SSE "file" 事件
  → hardenVueProject          # 黄金兜底：Scaffolder + ImportRepairer
  → callback.onComplete
```

**VueProjectTool 的 `writeFile`**：
- 路径安全校验：拒绝绝对路径、`..`、反斜杠统一转 `/`、拒绝盘符。
- Token 预算校验（`VueProjectTokenBudget.tryWrite`，`synchronized`）：文件数超限或累计字符超限就拒绝，并把**拒绝原因作为工具返回值回传给模型**，引导模型停止。
- 写盘 + 记录路径 + 触发 `file` 事件。

**为什么 AiVueProjectService 不是 Spring Bean？** 因为每次生成要注入不同的工具实例（工具里带着本次的 projectDir / budget / callback），做成单例 Bean 无法满足，所以放在 `CodeGenFacade.buildVueService` 每次临时构建。

### 4.5 对话即改代码流程

`ChatView.vue` → `POST /api/chat/send`（SSE）：
1. `sendUserMessage` 落库用户消息。
2. `aiChatService.chatStream(appId, appDescription, message)` 流式回复（带记忆）。
3. 结束时把 AI 回复落库。
4. **`applyChatUpdateToPreview`**：如果这次对话是要改代码，则调 `generateStream` 重新生成 + `redeployAppStream` 重新部署，通过 `appUpdating` / `codeChunk` / `progress` / `appUpdated` 事件推给前端刷新预览。

### 4.6 部署流程

```
POST /api/app/deploy/stream (SSE，带 progress 事件)
  → AppServiceImpl.deployAppStream
    → DeployService.deploy(app, progress)
      → 定位源码目录 {sourceRoot}/{codeGenType}_{appId}
      → deployKey：已有复用（地址稳定），否则随机 8 位（最多重试 20 次去重）
      → 目标目录 {webRoot}/{deployKey}，先删后拷（覆盖发布）
      → 非 Vue：源码即产物，直接 FileUtil.copyContent 拷贝
      → Vue：
          VueProjectScaffolder.scaffold() 补齐关键文件
          VueImportRepairer.repair() 补桩缺失引用
          写 deploy vite 配置（base:'./' + 完整版 vue）
          npm install（有 lock 跳过）+ npm run build --config vite.deploy.config.js
          拷贝 dist 到站点目录
      → 本机部署后主动 GET 探测站点，nginx 未启动则抛清晰异常
      → 写回 app.deployKey / app.deployedTime
```

---

## 五、AI 模块代码细节

### 5.1 `ChatModelConfig` — 手动装配模型 Bean

三个 Bean：
- `chatModel`：`OpenAiChatModel`，非流式。
- `streamingChatModel`：`OpenAiStreamingChatModel`，流式对话。
- `vueStreamingChatModel`：Vue 生成专用，多了 `.maxTokens(200000)`（因为 Vue 走工具调用，整个文件内容是 `writeFile` 工具参数输出，单轮 max_tokens 就是硬上限）。

共同点：`.customParameters(DISABLE_THINKING)` 禁用推理模型的思考过程（性能优化，见 3.3）。

### 5.2 `AiCodeGeneratorServiceFactory` — AiServices 装配工厂

```java
@Configuration
public class AiCodeGeneratorServiceFactory {
    @Bean
    public AiCodeGeneratorService aiCodeGeneratorService() {
        return AiServices.builder(AiCodeGeneratorService.class)
                .chatModel(chatModel).streamingChatModel(streamingChatModel)
                .build();
    }
    @Bean
    public AiChatService aiChatService() {
        return AiServices.builder(AiChatService.class)
                .chatModel(chatModel).streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId).maxMessages(10)
                        .chatMemoryStore(redisChatMemoryStore)   // Redis 记忆
                        .build())
                .build();
    }
}
```

要点：langchain4j 的 `AiServices.builder()` 会为接口生成**动态代理实现**——你定义一个接口（方法带 `@SystemMessage` / `@UserMessage` / `@MemoryId` 注解），运行时自动调用模型并解析返回值。这是 langchain4j 的"声明式 AI"模式。

### 5.3 `AiChatService` vs `AiVueProjectService`

| 维度 | AiChatService（对话） | AiVueProjectService（Vue 生成 agent） |
|---|---|---|
| 定位 | 普通流式聊天 | agent 工具调用 |
| 记忆 | ✅ 有（10 条窗口 + Redis） | ❌ 无（一次性生成） |
| Bean | ✅ Spring 单例 | ❌ 每次临时构建 |
| 返回 | TokenStream | String / TokenStream |

### 5.4 `RedisChatMemoryStore` — 对话记忆（双存储设计）

- 实现 langchain4j `ChatMemoryStore` 接口。
- Key：`chat:memory:{appId}`（每个应用一个槽位）。
- TTL：默认 1 小时，**每次写入 `setex` 重置 TTL** → 活跃对话持续续期，不活跃自动删除。
- 读取：`getMessages` → Redis 未命中 → **从 MySQL 重建**最近 10 条（倒序取最新，翻正，只保留 user/ai 类型）→ 回填 Redis。
- **设计哲学**：Redis 只是"AI 上下文短缓存"，MySQL `chat_history` 才是持久主存储。缓存过期丢的只是缓存，历史永远可以从库里重建。

### 5.5 前端 SSE 对话事件扩展

`parseChatSse` 在生成事件之外扩展了：`appUpdating`、`codeChunk`（代码增量，节流刷新预览）、`progress`（npm 构建进度）、`appUpdated`。
⚠️ 时序坑：`complete` 事件在前，`codeChunk`/`appUpdated` 在后且流未关，所以 Promise 必须等**流关闭后**才 resolve。

---

## 六、核心生成管线（解析/保存/净化）

### 6.1 `CodeParser` — 解析 AI 输出（三级降级策略）

`CodeParser.parse(rawText, type)` 是**逐级降级**的解析链，命中即返回：

1. **JSON 优先**：剥 ` ```json ` 围栏 → 手工括号配对抠出第一个 JSON 对象（容忍前后解释文字）→ 要求有 `htmlCode` 字段，按固定顺序生成 `index.html → style.css → script.js`。
2. **Markdown**：提取 ` ```lang ... ``` ` 代码块，文件名优先级 **文件名标签 > 语言标签 > 出现顺序**。
3. **裸 HTML**：`looksLikeHtml`（以 `<!doctype` / `<html` 开头，或含 `</html>`）→ 整体作为 index.html。
4. 全都不行 → 抛 `CodeParserException`。

每一步成功后都会调 `CodeSanitizer.sanitize` 净化。

**健壮性细节**：CSS 里 `body{margin:0}` 可能被误当 JSON 抠出来，此时 `MAPPER.readTree` 抛异常被捕获返回 null，交给下一级策略。

**数据模型**：`record CodeFile(String name, String content)` —— 极简的 Java record。

### 6.2 `CodeSaver` — 落盘

- 根目录：`tmp/code_output`（配置 `code.deploy.source-root`）。
- 目录结构：`{bizType}_{appId}`，如 `html_1001`、`vue_1001`。
- 每个文件落盘前再过一遍 `CodeSanitizer.sanitize(content, name)`（**双保险**）。
- `buildCurrentCodeContext`：把已生成代码拼成"小幅修改基线"文本注入 AI 上下文（递归收集，跳过 node_modules/dist/.git，只收白名单扩展名，总量 2 万字符截断）。**只进 AI 上下文，绝不返回客户端**。

### 6.3 `CodeSanitizer` — 安全净化（defense in depth）

把 AI 生成代码里**一切外部链接/跳转**移除，保证应用自包含、不跳转外部平台：
- **HTML**：移除 `<a>` 锚点、`<base>`；删除绝对地址属性（href/src/action）；移除 `<meta refresh>`；CSS 里 `url(绝对地址)` → `none`。
- **CSS**：移除外部 `@import`，`url(...)` → `none`。
- **JS**：字符串里的绝对 URL → `""`（中和 fetch/window.open）；`location.href/replace/assign =` → 注释掉。

保留相对引用（style.css、script.js），保证应用仍能正常预览部署。

> ⚠️ 例外：Vue 模式**跳过净化** —— 因为 Vue 提示词允许 `picsum.photos` 占位图，与净化器"移除一切外部链接"冲突，故原样落盘。

### 6.4 绝对路径防泄漏设计（重点）

- `CodeGenResult.saveDir` 字段标了 **`@JsonIgnore`** —— 服务端绝对路径绝不序列化给客户端。
- SSE 的 `appUpdating` 只发 `{}`，`appUpdated` 不带 saveDir。
- 前端拿到的只是**相对文件名列表** + 拼接好的 `baseUrl/deployKey` 访问地址。
- 这是用户硬性要求：**应用与用户之间永远不出现服务端绝对路径/敏感路径**。

---

## 七、Vue 项目质量加固

### 背景与用户偏好

生成式模型常犯的错：**引用没生成的文件、漏写关键文件、package.json 非法** → 生成的 Vue 工程 `npm run build` 直接失败或运行白屏。用户明确选择"**仅黄金兜底**"方案（最省时）：
- ✅ 生成完成后做**确定性文件兜底**。
- ❌ 不做"模型自修循环"（不把校验问题喂回模型多轮重生成）。
- ❌ 生成期不跑真实 `npm build`（真实构建由部署链路把关）。

### 7.1 `VueProjectScaffolder` — 黄金兜底

`scaffold()` 对 6 个关键文件逐一处理（`package.json / vite.config.js / index.html / src/main.js / src/App.vue / src/router/index.js`）：
- **只补缺失、不覆盖**模型已写的合法文件（幂等）。
- `package.json` 缺失/非法 → 整体写**黄金模板**；合法 → **深度合并**（保留模型声明的其它依赖，只强制 scripts 与核心依赖存在，major 版本不符时覆盖）。
- 确定性清理 `src/router.js` 与 `src/router/index.js` 并存的"双写法"冲突。

### 7.2 `VueImportRepairer` — 补桩

扫描源码里的相对导入（`./`、`../`、`@/`），对指向**不存在文件**的导入：
- `.vue` 缺失 → 生成**可见占位 SFC**（渲染"待完善模块"文案，不是空白页）。
- `.js/.ts` 缺失 → 生成 **noop Proxy 桩**（命名导出 + default 都是同一个永不抛错的 Proxy 函数），避免 rollup "X is not exported" 报错和运行期 undefined 崩溃。

幂等：已存在不覆盖；先探测 vite 能否按扩展名/目录 index 解析到现有文件，能就不补桩（避免补出的 router.js 遮蔽真实 router/index.js）。

### 7.3 调用时机

`CodeGenFacade.hardenVueProject` 在**生成完成时**调用（受 `code.vue.scaffold-on-generate` 开关控制）；`DeployService.buildVueProject` 在**部署时**再调一次（双保险）。两处都幂等。

---

## 八、业务服务与权限体系

### 8.1 `UserServiceImpl` — 用户

- 注册：校验 → 查重 → MD5+盐加密 → 保存。
- 登录：查库 → 比对 → Session 存完整 User → 返回脱敏 VO。
- `getLoginUser`：每次**重新查库刷新**（防注销后仍可用）。

### 8.2 `AppServiceImpl` — 应用 CRUD

- `createApp`：initPrompt 必填、codeGenType 枚举校验、**显式设置 createTime/updateTime**（保证按活跃时间排序有值）。
- `deleteApp`：`@Transactional`，逻辑删除 app + **级联删除对话历史**。
- `getAppCode`：数据权限校验（本人/管理员/已部署公开可看）→ 递归读盘（跳过 node_modules/dist/.git）→ 返回文件清单。
- `editAppCodeText`：全局字符串替换写回（可视化编辑文字）。
- `editAppCodeStyle`：`HtmlStyleEditor.applyStyle` 改内联样式写回（可视化编辑样式）。
- `getMyAppPage`：`where userId=当前用户` + name like → updateTime desc 排序（最近活跃置顶）。
- `getFeaturedAppPage`：`where priority > 0`（应用广场 = 管理员手动置顶）。

### 8.3 `ChatHistoryServiceImpl` — 对话历史

- **游标分页**（keyset pagination，`getChatHistoryByCursor`）：
  - 用 `id < cursorId` 作为游标，只取更早的消息。
  - `orderBy(createTime desc).orderBy(id desc)` + `limit(size + 1)` 多取一条判断 `hasMore`。
  - 返回 `ChatCursorVO{records, hasMore}`。
  - 相比 offset 分页：**深翻页不慢、无跳页、新消息插入不干扰**。
  - 配合 `idx_appId_createTime` 联合索引。
- **`getMyConversations`**：会话列表，Java 侧按 appId 分组取最新摘要 + 计数。
- ⚠️ 工程坑：`buildChatContext` 用**字符串列名 `new QueryColumn(...)`** 而非 lambda 方法引用 —— 因为跑在 AI 流式回调线程上，TCCL（线程上下文类加载器）读不到实体类会 `ClassNotFoundException`。这是真实踩过的坑，**面试讲出来很加分**。

### 8.4 权限体系（双层）

**第一层：注解 + AOP 切面**（`@AuthCheck` + `AuthInterceptor`）
- `@AuthCheck`：`role`（默认空=只要登录）、`mustLogin`。
- `AuthInterceptor` 是 **AOP 切面**（`@Aspect` + `@Around("@annotation(authCheck)")`），不是 Spring MVC 的 HandlerInterceptor。从 `RequestContextHolder` 拿 request。
- 对未标注 `@AuthCheck` 的接口不拦截（如 /health、/register、/login）。

**第二层：Service 层数据级权限**（不依赖注解）
- `getOwnedApp`：校验 app 属于当前用户。
- `checkCodeViewPermission`：本人 / 管理员 / **已部署公开**。
- `checkCodeEditPermission`：仅本人 / 管理员。
- `ChatHistoryServiceImpl.checkAppPermission`：admin 或 owner。

> 面试回答："注解只做登录+角色校验，真正的**归属校验在 Service 层**，两层配合形成数据级权限。"

---

## 九、通用设施（返回格式/异常/序列化/CORS）

### 9.1 `BaseResponse<T>` + `ResultUtils`

- 统一返回：`{code, data, message}`，**code=0 表示成功**。
- 错误码（`ErrorCode`）：40000 参数、40100 未登录、40101 无权限、40400 不存在、40300 禁止、50000 系统、50001 操作失败。

### 9.2 `GlobalExceptionHandler`

- `@RestControllerAdvice`。
- `BusinessException` → 返回自定义 code + message。
- `RuntimeException` → 固定 `50000 + "系统错误"`，**不泄露堆栈**。
- ⚠️ 没有兜底 `Exception.class`（只处理 RuntimeException）。

### 9.3 `JacksonConfig` — 雪花 ID 转字符串（重要）

```java
// Long / long 全局序列化为字符串
```
为什么？**雪花 ID 是 64 位，超出 JS Number 安全整数范围（2^53）**，直接返回数字会在前端 `JSON.parse` 时丢精度。转字符串后前端当作字符串处理，完美规避。这是前后端协作的经典问题。

### 9.4 `CorsConfig`

- `allowCredentials(true)`（允许携带 JSESSIONID Cookie）+ `allowedOriginPatterns("*")`（用 patterns 而非 `*`，否则与 credentials 冲突）。
- ⚠️ 安全权衡：`allowCredentials(true) + allowedOriginPatterns("*")` 等价于"任何来源携带凭据"，生产环境是风险点。开发期够用。

---

## 十、数据库设计

### 10.1 三张表

**`app` 表**：
| 字段 | 说明 |
|---|---|
| id | 雪花 ID |
| appName | 应用名 |
| cover | 封面 |
| initPrompt | 初始化提示词（基础指令） |
| codeGenType | html / multi_file / vue |
| deployKey | 部署标识（`UNIQUE KEY uk_deployKey`） |
| deployedTime | 部署时间 |
| priority | 优先级（>0 = 精选置顶） |
| userId | 创建者 |
| editTime / createTime / updateTime | 时间戳 |
| isDelete | 逻辑删除 |

索引：`uk_deployKey`、`idx_appName`、`idx_userId`。

**`chat_history` 表**：
| 字段 | 说明 |
|---|---|
| id | 雪花 ID |
| message | 消息内容（text） |
| messageType | user / ai / error |
| appId / userId | 归属 |
| createTime / updateTime / isDelete | 时间戳 + 逻辑删除 |

索引：`idx_appId`、`idx_createTime`、`idx_appId_createTime`（**游标查询核心索引**）。

**`user` 表**（⚠️ `sql/create_table.sql` 里**没有**这个表的 DDL！实体 `@Table("user")` 需要，这是一个 schema 脚本缺口）：
id / userAccount / userPassword / userName / userAvatar / userProfile / userRole / editTime / createTime / updateTime / isDelete。

### 10.2 设计要点

- **主键**：雪花 ID（`@Id(keyType = KeyType.Generator, value = KeyGenerators.snowFlakeId)`），全局唯一、无需自增同步、时间有序。
- **逻辑删除**：`@Column(value="isDelete", isLogicDelete=true)`，MyBatis-Flex 自动在 WHERE 拼 `isDelete=0`，`removeById` 实为 UPDATE。
- **游标索引**：`idx_appId_createTime` 支撑 keyset 分页。

---

## 十一、前端详解

### 11.1 路由（10 个）

| 路径 | 页面 | 说明 |
|---|---|---|
| `/` | HomeView | 首页（输入需求 + 应用广场） |
| `/generate` | GenerateView | 生成页（流式 + 实时预览） |
| `/result` | ResultView | 结果页 |
| `/chat/:appId` | ChatView | **对话页（最复杂，1721 行）** |
| `/project/:appId` | VueProjectView | Vue 工程生成页（终端风格） |
| `/conversations` | MyConversationsView | 我的对话列表 |
| `/admin/chat` | ChatAdminView | 对话管理（admin） |
| `/admin/apps` | AppAdminView | 应用管理（admin） |
| `/login` / `/register` | 登录 / 注册 | |

⚠️ 前端**没有全局路由守卫**（`beforeEach`），登录校验是页面级分散做的（ChatView 未登录跳 /login 等）。

### 11.2 Pinia store

- `user.ts`：登录态，持久化到 localStorage（key 带版本号），`isLoggedIn` computed 派生。后端走 session+Cookie，前端**没有真正的 token**。
- `generation.ts`：生成流程跨页共享状态（requirement / steps / currentStep / streamingCode / app / generating...），`start()` 是核心流程 `createApp → generateAppStream(SSE) → getAppDetail`。

### 11.3 request.ts 封装

- axios 实例：`baseURL: '/api'`、`withCredentials: true`（携带 Cookie）、30s 超时。
- 响应拦截器：`code === 0` 解包返回 `data`；失败统一 `message.error` 并 reject。

### 11.4 可视化编辑（亮点）

`src/utils/visualEdit.ts` —— **iframe 内注入编辑脚本 + postMessage 跨窗口通信**：
- 注入脚本：`mouseover` 悬浮高亮（outline）→ `click` 选中元素（画天蓝边框）→ 采集元素信息。
- 消息协议：父→iframe `ENABLE/DISABLE/APPLY_TEXT/APPLY_STYLE/RESTORE_STYLE`；iframe→父 `SELECT`。
- `APPLY_TEXT` 直接 `selected.textContent = d.text`（所见即所得）；`APPLY_STYLE` 逐属性 setProperty。
- `parseElementFromEvent` 校验 `e.source` 是自己的 iframe（**防伪造**）。
- `buildElementPrompt`：把选中元素的定位信息并入发给 AI 的提示词，约束"只做小幅度局部修改"。

**用户在 ChatView 的编辑能力**：直接改文字（后端全局替换写回）、改颜色（取色器）、改内边距/外边距（数字输入自动补 px）。保存流程：先前端所见即所得预览 → 调后端写盘 → 刷新代码 → 已部署自动重新部署；失败回滚。

### 11.5 炫酷 UI 特效

- `ScrambleText.vue`：字符解密动画（片假名乱码 → 解密成原文），黑客风。
- `LoopScramble.vue`：多条短语循环解密。
- `AsciiHorse.vue`：ASCII 奔跑小马（Muybridge 奔马摄影，4 帧），⚠️ 定义了但**没被任何页面引用**（预留组件）。
- `AppCover.vue`：应用封面，不用图片，色相按应用名 hash 稳定生成 + 打字机效果 + LIVE 徽章。

---

## 十二、部署与运维

### 12.1 端口分配

| 组件 | 端口 | 说明 |
|---|---|---|
| nginx | 80 | 托管部署的应用静态站 `http://localhost/apps/{deployKey}/` |
| Spring Boot | 8224（context-path `/api`） | 后端 |
| Vite dev | 5173 | 前端开发 |

### 12.2 前后端联调

- **开发**：Vite 代理 `/api` → `http://localhost:8224`。
- **生产**：前端 `npm run build` 产出 dist 由 nginx 托管；后端部署 Vue 时注入 `vite.deploy.config.js`（`base:'./'` + 完整版 vue），保证子目录部署资源不 404。

### 12.3 nginx 职责

只做**静态 Web 服务器**（不是反向代理/负载均衡）。默认 conf 即可，部署产物拷贝到 `{web-root}/{deployKey}/`。

### 12.4 关键配置（application.yml）

- `spring.datasource`：MySQL。
- `server.port=8224`、`server.servlet.context-path=/api`。
- `code.deploy.source-root`：生成代码根目录（默认 `${user.dir}/tmp/code_output`）。
- `code.deploy.web-root`：nginx 站点根。
- `code.deploy.base-url`：公网访问前缀。
- `code.vue.max-tokens=200000`、`code.vue.max-files=100`、`code.vue.scaffold-on-generate=true`。
- `chat.memory.redis.ttl-hours=1`。
- `langchain4j.open-ai.chat-model`：DeepSeek 的 base-url / api-key / model-name（在 `application-local.yml`，被 .gitignore 忽略）。

---

## 十三、项目亮点（面试加分区）

按重要性排序，**背熟这 10 条**：

1. **Vue 项目 agent 生成模式**：AI 通过 `writeFile` 工具调用逐个生成完整 Vue 工程（几十个文件），而不是一次性输出。使用 langchain4j `@Tool` 机制 + 每次临时构建 AiServices 注入工具实例。这是"AI 应用框架"思维的体现。

2. **确定性加固代替模型自修循环**：针对"模型生成 Vue 工程跑不起来"的痛点，采用 `VueProjectScaffolder`（黄金兜底补齐关键文件）+ `VueImportRepairer`（缺失引用补桩）的确定性方案，而不是把问题喂回模型多轮重生成。**省时、可控、幂等**。这是非常实用的工程取舍。

3. **双预算控制（VueProjectTokenBudget）**：在模型 `max_tokens` 之外，工具层再做**文件内容累计预算**（文件数上限 + 累计字符数上限），拒绝原因作为工具返回值回传引导模型停止。防止模型生成失控的超大工程。

4. **SSE 流式 + fetch ReadableStream 手动解析**：因为 EventSource 不支持 POST，手写了 SSE 协议解析器，处理了 `data:` 不能 trim（空格是代码）、`complete` 与后续事件时序、心跳保活等细节。深度体现协议级理解。

5. **三级降级解析器（CodeParser）**：JSON → Markdown 代码块 → 裸 HTML 逐级降级，容忍模型的各种"不守规矩"输出；手工括号配对抠 JSON、文件名三级命名优先级。健壮性极强。

6. **安全净化双保险（CodeSanitizer）**：prompt 约束 + 代码级净化（移除外部链接/跳转），保证应用自包含。**防 nav 劫持、防跳转外部平台**。

7. **双存储对话记忆**：Redis 做 AI 上下文短缓存（TTL 续期）+ MySQL 做主存储（可从库重建），结合 langchain4j `ChatMemoryStore` 接口。兼顾性能与持久。

8. **雪花 ID → 字符串序列化**：全局 Jackson 配置，解决 JS Number 精度丢失。前后端协作的经典坑。

9. **绝对路径零泄漏**：`@JsonIgnore` 挡掉 saveDir、SSE 不返回路径、前端只给相对路径。安全设计意识。

10. **DeepSeek 禁用思考优化**：把首 token 延迟从 ~95s 降到 ~1.3s（总耗时 ~10s）。实测驱动的性能优化，非常加分。

**附加工程细节**（讲到会显得很资深）：
- AOP 注解鉴权（@AuthCheck）而非 HandlerInterceptor。
- TCCL 问题修复：AI 流式回调线程里用字符串列名查询避免 ClassNotFoundException。
- 登录"账号或密码错误"统一提示防枚举账号。
- 游标分页 vs offset 分页。
- CORS `allowedOriginPatterns` 而非 `*`（与 credentials 兼容）。
- Windows npm 输出 GBK 乱码处理（`chcp 65001`）。

---

## 十四、面试 Q&A 大全

### 14.1 项目介绍类

**Q1：介绍一下你的项目。**
> 我做了一个 AI 零代码应用平台。用户输入一句需求（比如"做一个带购物车的电商落地页"），后端调用 DeepSeek 大模型，通过 langchain4j 生成代码，支持三种模式：纯 HTML 单页、多文件页面、以及完整可运行的 Vue 工程。生成的代码可以流式展示、实时预览、继续对话修改、可视化点击编辑，最后一键部署到 nginx 公网访问。前后端分离：Spring Boot 4 + MyBatis-Flex 后端，Vue3 + TS + Vite 前端，SSE 做流式通信。我负责了核心的代码生成管线（解析/保存/净化/加固）、AI 服务装配、权限体系、以及流式通信链路。

**Q2：项目的核心难点是什么？你怎么解决的？**
> 主要有四个：(1) 模型输出不稳定——设计了三级降级的解析器（JSON→Markdown→裸HTML），容忍各种格式；(2) 生成完整 Vue 工程容易缺文件——采用"确定性加固"：Scaffolder 补齐关键文件 + ImportRepairer 补桩；(3) 大模型首 token 慢——禁用 DeepSeek 的思考过程，首 token 从 95s 降到 1.3s；(4) 流式通信——SSE + 前端手动解析 ReadableStream。

### 14.2 架构与设计类

**Q3：整体架构是怎样的？分层怎么分的？**
> 前端 Vue3，后端经典分层：Controller → Service → CodeGenFacade 门面 → AI 层（langchain4j AiServices）→ 核心管线（Parser/Saver/Sanitizer）→ Mapper。用 Facade 把"生成代码"这个复杂流程（调用 AI、解析、净化、落盘、加固、部署）封装成单一入口，Controller 只负责接收请求。

**Q4：为什么用门面（Facade）模式？**
> 因为三种生成模式（HTML/多文件/Vue）流程差异很大，但对外暴露的入口一致（generate/generateStream）。门面把"按 app.codeGenType 分派 + 各自流程编排"集中起来，Controller 不感知内部复杂度；而且可以统一做参数校验、结果组装、路径防泄漏处理。

**Q5：前端为什么没有全局路由守卫？**
> 页面级分散校验：ChatView 进入时未登录跳 /login，管理员页用 computed 判断角色。是简化方案——缺点是每个敏感页面要自己写校验，容易出现遗漏。如果重构，应该加一个全局 beforeEach 统一做登录态和角色校验。**（诚实承认缺点 + 给出改进方向，很加分）**

### 14.3 技术选型对比类

**Q6：为什么用 Session 不用 JWT？**
> 单体应用、无多实例部署需求，Session 最简单且支持实时失效（账号删除后下一请求即拒绝，因为每次 getLoginUser 都重新查库）。JWT 的无状态优势在单体里体现不出来，反而引入密钥管理、失效处理复杂度。缺点是内存 Session 无法横向扩展，如果要上多实例需要引入 spring-session 做会话共享。

**Q7：为什么用 MyBatis-Flex 不用 MyBatis-Plus？**
> 主要因为 Spring Boot 4 的兼容性：MyBatis-Flex 有专门的 `mybatis-flex-spring-boot4-starter`。另外它更轻量、支持雪花 ID、逻辑删除、QueryWrapper lambda 查询，对单体项目完全够用。

**Q8：为什么 langchain4j 不用 spring-boot-starter？**
> 项目用了 Spring Boot 4，而 langchain4j 官方 starter 基于 Boot 3.5 编译，依赖的类在 Boot 4 被移除，启动就 NoClassDefFoundError。所以改用纯核心包 + 高层模块，手动构建 Bean。这也是 AiCodeGeneratorServiceFactory 需要手动装配 AiServices 的原因。

**Q9：为什么 SSE 不用原生 EventSource？**
> EventSource 只支持 GET，而我们的生成接口需要 POST 带请求体。所以用 fetch 的 ReadableStream 手动解析 SSE 协议。这反而让我深入理解了 SSE 的帧格式。

**Q10：为什么用 Redis 存对话记忆，不直接全放 MySQL？**
> 双存储：Redis 是 AI 上下文的短缓存（TTL 1 小时、每次写入续期），保证 AI 读取快；MySQL 是持久主存储，历史记录和管理页读它。Redis 缓存过期丢的只是缓存，可以从 MySQL 重建最近 10 条。这样兼顾性能与数据安全。

### 14.4 核心机制深挖类

**Q11：AI 是怎么生成完整 Vue 工程的？**
> 不是一次性输出代码，而是把 AI 包装成 agent，暴露 `writeFile(path, content)` 工具。系统提示词要求模型：先输出生成计划，然后逐个调用 writeFile 写文件（package.json、vite.config.js、src/main.js、组件文件等），写完调用 finishProject 提交描述。每个 writeFile 会做路径安全校验和 token 预算校验，然后写盘并推 SSE file 事件。langchain4j 的 `@Tool` 注解自动把 Java 方法暴露给模型调用，我用 `AiServices.builder().tools(tool)` 注入。

**Q12：生成的 Vue 工程怎么保证能跑起来？**
> 确定性加固：VueProjectScaffolder 补齐缺失的关键文件（package.json 用黄金模板 + 深度合并依赖，router 双写法冲突清理）；VueImportRepairer 给缺失的 import 补桩（.vue 补可见占位组件，.js 补 noop Proxy）。两者幂等，生成时和部署时各跑一遍。取舍是不做模型自修循环——省时、可控，接受"功能可能缺失但项目能构建能跑不白屏"的降级。

**Q13：模型生成代码怎么解析成文件？**
> CodeParser 三级降级：(1) 优先解析严格 JSON（提示词强制要求，手工括号配对容忍前后解释文字）；(2) Markdown 代码块，文件名按"文件名标签 > 语言标签 > 出现顺序"决定；(3) 裸 HTML 整体作为 index.html。解析后统一过 CodeSanitizer 净化再落盘。

**Q14：对话是怎么"改代码"的？**
> 每个应用有一个 initPrompt（基础指令），对话时把用户消息和当前代码上下文（buildCurrentCodeContext，最多 2 万字符）一起喂给 AI 生成新的完整代码，替换后自动重新部署。前端通过 appUpdating/codeChunk/progress/appUpdated 事件看到整个过程。

**Q15：可视化编辑是怎么实现的？**
> iframe 内注入编辑脚本：悬浮高亮、点击选中、采集元素信息；通过 postMessage 与父页面通信。改文字直接 textContent 替换，改样式逐属性 setProperty。保存时调用后端写盘接口（文字走全局字符串替换，样式走 HtmlStyleEditor 解析内联 style 合并写回），已部署的应用自动重新部署。只支持 srcdoc 模式（跨域已部署的 iframe 无法注入脚本）。

**Q16：雪花 ID 为什么要转字符串？**
> 雪花 ID 是 64 位 Long，超过 JS Number 的 2^53 安全整数上限，直接返回会导致前端 JSON.parse 丢精度（最后几位变 0）。JacksonConfig 全局把 Long 序列化为字符串解决。这是前后端交互的经典问题。

### 14.5 安全类

**Q17：权限控制怎么做的？**
> 双层：(1) 注解 + AOP 切面——@AuthCheck(role="admin") 标注需要登录/角色的接口，切面从 RequestContextHolder 拿 request，调 getLoginUser 校验，未登录抛 40100，角色不符抛 40101；(2) Service 层数据级权限——getOwnedApp 校验归属、checkCodeEditPermission 仅本人/管理员可改、checkCodeViewPermission 本人/管理员/已部署公开可看。注解管"能不能进"，Service 管"能不能碰这条数据"。

**Q18：怎么防止路径泄漏？**
> CodeGenResult.saveDir 加 @JsonIgnore，SSE 事件不返回路径，前端只拿相对文件名和拼接好的公网 URL。服务端绝对路径只在日志和 Java 内部对象里。这是我很在意的安全细节。

**Q19：登录提示为什么统一"账号或密码错误"？**
> 防止通过响应差异枚举已注册账号（user enumeration）。

**Q20：项目的安全隐患有哪些？（诚实回答）**
> (1) 密码是 MD5 + 固定盐，应该换 bcrypt/Argon2；(2) CORS 是 allowCredentials(true) + allowedOriginPatterns("*")，任何来源都能带凭据，生产要收紧白名单；(3) user 表的建表 DDL 缺失；(4) 全局异常处理没兜底 checked Exception。**主动暴露这些，体现真实的工程反思。**

### 14.6 性能与并发类

**Q21：并发部署/生成有没有考虑？**
> DeployService 对 Vue 构建用了 `synchronized(vueBuildLocks.computeIfAbsent(appId))` 锁，同一应用避免并发 npm build 互相踩。VueProjectTokenBudget.tryWrite 是 synchronized 的。整体上因为是单体 + 单人开发的规模，做了基本防护但没上消息队列/分布式锁。

### 14.7 记忆类/送分题

**Q22：你遇到最坑的问题是什么？**
> 两个：(1) AI 流式回调跑在 langchain4j 的线程上，线程上下文类加载器（TCCL）读不到 Spring 的实体类，MyBatis-Flex 用 lambda 方法引用会 ClassNotFoundException —— 改成字符串列名 `new QueryColumn("appId")` 解决。(2) SSE 的 `data:` 不能 trimStart，否则吃掉代码开头的空格（比如 `<!DOCTYPE html>` 前面）。都是不看源码根本发现不了的坑。

### 14.8 改进方向类

**Q23：如果继续做，你想怎么改进？**
> (1) 密码加密升级为 bcrypt；(2) 引入 spring-session + Redis 做分布式会话；(3) 用 Kafka/RabbitMQ 做生成任务异步化 + 结果通知，替代纯 SSE；(4) 前端补全局路由守卫；(5) 补 user 表 DDL 和集成测试；(6) 增加上下文窗口管理（长对话时摘要压缩）；(7) 模型生成增加流式结构化输出（function calling 强制 JSON schema）替代字符串解析。

---

## 十五、快速启动指南

### 15.1 启动前提

1. MySQL：建库 `yang_ai_code_mother`，执行 `sql/create_table.sql`（app + chat_history；**user 表需自行补建**）。
2. Redis：本机 `127.0.0.1:6379`，密码按 `application-local.yml` 配置。
3. DeepSeek API key：配置在 `application-local.yml`（被 .gitignore 忽略，需自己填）。
4. nginx：本机安装（部署功能需要），按 `nginx/README.md` 说明用默认 conf 即可。

### 15.2 启动后端

```bash
./mvnw spring-boot:run
```
监听 8224，API 前缀 `/api`，接口文档 `http://localhost:8224/api/doc.html`。

### 15.3 启动前端

```bash
cd yang-ai-code-monther-frontend
npm install
npm run dev
```
Vite 5173，`/api` 代理到 8224。

### 15.4 部署生成的应用

后端生成代码落在 `tmp/code_output/`；部署后产物在 `{web-root}/{deployKey}/`，公网访问 `http://localhost/apps/{deployKey}/`。

---

## 附：关键文件速查表

| 文件 | 作用 |
|---|---|
| `core/CodeGenFacade.java` | **核心编排器**：三种生成模式分派 + 解析落盘 + Vue 加固 |
| `ai/ChatModelConfig.java` | 手动构建 3 个模型 Bean（含禁用 thinking） |
| `ai/AiCodeGeneratorServiceFactory.java` | AiServices 装配（代码生成 + 对话） |
| `ai/memory/RedisChatMemoryStore.java` | Redis 对话记忆（TTL 续期 + MySQL 重建） |
| `ai/tools/VueProjectTool.java` | writeFile/finishProject 工具（agent 写文件） |
| `ai/tools/VueProjectTokenBudget.java` | 文件数/字符双预算 |
| `core/parser/CodeParser.java` | 三级降级解析 |
| `core/saver/CodeSaver.java` | 落盘 + 代码上下文构建 |
| `core/CodeSanitizer.java` | 外部链接净化 |
| `service/VueProjectScaffolder.java` | 黄金兜底关键文件 |
| `service/VueImportRepairer.java` | 缺失 import 补桩 |
| `service/DeployService.java` | 部署到 nginx（含 Vue 构建） |
| `controller/AppController.java` | 生成/流式/下载/部署等所有应用接口 |
| `annotation/AuthCheck.java` + `aop/AuthInterceptor.java` | 注解鉴权 |
| `config/JacksonConfig.java` | 雪花 ID 转字符串 |
| 前端 `src/api/generation.ts` | SSE 手动解析（fetch ReadableStream） |
| 前端 `src/utils/visualEdit.ts` | 可视化编辑（postMessage） |
| 前端 `src/stores/generation.ts` | 生成流程状态机 |
| `src/main/resources/prompt/` | 三个系统提示词（html/multi/vue） |

---

*文档结束。建议按顺序：先背[项目概述](#一项目概述30-秒电梯陈述) → 再背[亮点](#十三项目亮点面试加分区) → 把[核心流程](#四核心业务流程详解)自己画一遍 → 最后逐个过[面试 Q&A](#十四面试-qa-大全)。*
