---
title: "Claude Code AI 对话技巧总结"
date: 2026-01-22
url: https://juejin.cn/post/7597989474991783974
views: 176
likes: 3
collects: 5
source: html2md
---

## Claude Code AI 对话技巧总结

> 基于 141 个 session、1342 条对话历史整理(数据已脱敏）

### 一、深度思考触发词

#### 1\. ultrathink（最常用）

触发 Claude 进行深度分析，适用于复杂问题。
    
    
    分析一下是否有bug,特别是在高并发场景下 ultrathink
    重新分析需求，这次描述是正确的 ultrathink
    基于当前分支进行分析，给我分阶段提交方案 ultrathink
    针对现有代码，写一下ut ultrathink
    

**适用场景：**

  * 并发问题分析
  * 复杂业务逻辑梳理
  * 代码重构设计
  * 需求拆分方案



#### 2\. think harder

比 ultrathink 更强调"再想想"，用于 Claude 第一次分析不到位时。
    
    
    分析一下可能是哪个原因过滤了？think harder
    找到基于浮动票价的票价规则代码 think harder
    分析train_transfer 线程池的所有使用场景 think harder
    

**适用场景：**

  * 对初次分析结果不满意
  * 需要更深入挖掘
  * 排查隐藏问题



#### 3\. think / think again

轻量级思考触发。
    
    
    查看每个席别的价格和policyId think
    think againg ultrathink  # 组合使用
    

* * *

### 二、执行控制指令

#### 1\. just do it（直接执行）

跳过确认，让 Claude 直接动手。
    
    
    ok, just do it
    渠道名为原渠道+'-NoBinding', just do it
    删除fail-fast，catchException just do it
    在concurrent包下的所有枚举类加上suppresswarning all just do it
    

**适用场景：**

  * 已确认方案，无需再讨论
  * 批量修改任务
  * 简单明确的操作



#### 2\. go on / retry

继续或重试操作。
    
    
    go on          # 继续上一步操作
    retry          # 重试上次失败的操作
    retry again    # 再次重试
    retry last prompt  # 重试上一个提示
    

#### 3\. yes / y

确认执行。
    
    
    yes
    y
    1. yes 2. 你帮我添加一下吧
    

* * *

### 三、常用 Slash 命令

命令| 用途| 使用频率  
---|---|---  
`/clear`| 清空当前对话| ⭐⭐⭐⭐⭐ (128次)  
`/exit`| 退出会话| ⭐⭐⭐⭐ (54次)  
`/compact`| 压缩对话上下文| ⭐⭐⭐⭐ (40次)  
`/resume`| 恢复上一个会话| ⭐⭐⭐ (37次)  
`/mcp`| MCP 工具管理| ⭐⭐ (17次)  
`/init`| 初始化| ⭐⭐ (8次)  
`/context`| 上下文管理| ⭐ (3次)  
`/cost`| 查看消耗| ⭐ (4次)  
  
#### compact 高级用法

可以带描述进行压缩：
    
    
    /compact 写了一篇博客 + 生成了可运行代码
    /compact 总结你的方案（需求，实现方案）
    /compact 总结一下之前的操作
    /compact "如果有多个线程提交到一个全局的ConcurrencyLimitExecutorByLock..."
    

* * *

### 四、任务类型模板

#### 1\. 代码分析类
    
    
    分析一下这个类的算法
    分析一下调用链路，重点标识出订单被过滤原因
    分析当前实现，找到对应代码，说明相关代码相关的业务
    分析release提交和上周提交的对比，有哪些提交可能影响xxx
    分析最近一周的提交，review一下代码
    

#### 2\. 需求分析类
    
    
    分析需求，确定代码位置
    分析需求，找到对应代码，分析现有代码逻辑
    分析历史需求实现，当前现状，盘点相关业务逻辑
    重新分析需求：XXX这个类有核心内容。分析现有实现，核心代码，改动点，影响
    

#### 3\. Bug 排查类
    
    
    分析异常原因
    分析编译失败原因
    分析错误，不是这个提交影响的
    这个问题12月12日开始出现的，分析git历史看看可能是哪些提交引起的
    

#### 4\. 文档输出类
    
    
    输出到md文件
    输出到/notes文档，md
    输出计划到/notes
    很好，原封不动输出到md文档
    整个分析过程输出到md文件
    总结成文档，然后compact
    

#### 5\. 单元测试类
    
    
    写单元测试
    补充一下unit tests
    针对现有代码，写一下ut ultrathink
    add tests for this branch, aim for code coverage
    给这个方法写单元测试，要求覆盖我提交的代码即可
    

#### 6\. 博客写作类
    
    
    写博客：源码解读 ConcurrentHashMap 弱一致性解读
    写博客，类似Martin Fowler的重构这本书的行文逻辑
    开头简要介绍主题和目标，结尾总结关键点. 目标是中高级开发者
    

* * *

### 五、高效对话模式

#### 1\. 渐进式分析
    
    
    1. 首先分析下这个类
    2. 分析一下调用链路
    3. 详细分析下xxx
    4. 总结一下相关代码
    

#### 2\. 引导式纠正
    
    
    分析思路错了，应该查看提交历史
    分析错误，本来票价规则就基于直连
    不是AvailCheck请求，再分析一下
    

#### 3\. 结构化需求描述
    
    
    设计文档需要：背景、现状、目标、方案、评估
    分析需求，确定代码位置，分析现有代码逻辑
    

#### 4\. 并行任务
    
    
    use 2 subagents, 分别修改 TransferParallel, MultiRouteParallel调用点 ultrathink
    我multi-clauding, 分析当前bugs、notes文件夹，总结整理成代办事项
    

* * *

### 六、上下文管理技巧

#### 1\. 会话保存与恢复
    
    
    /resume              # 恢复上一个会话
    /compact 总结xxx     # 带描述压缩
    简单总结一下之前的操作，不要输出文档
    

#### 2\. 知识沉淀
    
    
    总结代码风格并更新到当前项目的上下文md中，给你自己使用
    加入claude.md, 记住：修改的时候Test文件也要修改
    设计文档需要更新到当前项目中。redo。同时更新这个要求到claude.md中
    

#### 3\. 创建 Skills
    
    
    先做换乘业务的skills总结吧，包括核心业务流程、核心业务逻辑
    总结刚才的查询流程，总结到skills，核心能力为查询页面操作流程
    add claude code skill: 写单元测试
    

* * *

### 七、最佳实践

#### 1\. 复杂任务分解
    
    
    # 好的做法
    分析当前实现 → 输出设计文档 → 执行计划 → 补充单元测试
    
    # 不好的做法
    一次性要求完成所有事情
    

#### 2\. 明确输出要求
    
    
    # 好的做法
    输出到/notes下，格式为md
    总结一下这个分支新增的配置
    简单总结本次新增的特性，简单说明一下改动点的原理
    
    # 不好的做法
    分析一下（没有明确输出形式）
    

#### 3\. 引用具体代码/配置
    
    
    # 好的做法
    ShowEntityHiddenConfigs=*:*^TravelPackage... 分析一下这个配置
    分析下 @TransferLimitEntity 是如何实现禁售的
    [jira-XXXX-14794]feat: 票价规则算法 解释一下这个提交
    
    # 不好的做法
    分析一下那个配置
    

#### 4\. 使用 ultrathink 的时机

场景| 是否使用 ultrathink  
---|---  
简单代码查找| ❌  
并发/线程安全分析| ✅  
复杂业务逻辑梳理| ✅  
性能问题排查| ✅  
重构方案设计| ✅  
Bug 根因分析| ✅  
简单文档生成| ❌  
  
* * *

### 八、常用组合技巧

#### 技巧1：分析 + 深度思考 + 输出
    
    
    分析一下并发问题 ultrathink，输出到md文件
    

#### 技巧2：执行 + 跳过确认
    
    
    删除这个方法中所有verify相关校验方法，just do it
    

#### 技巧3：多轮迭代
    
    
    第一轮：分析现有实现
    第二轮：think harder（如果分析不到位）
    第三轮：按照要求做好计划 ultrathink
    第四轮：just do it
    

#### 技巧4：上下文复用
    
    
    /resume                     # 恢复之前的上下文
    根据当前页面，retry         # 复用之前的指令
    

* * *

### 总结

技巧类型| 关键词| 使用场景  
---|---|---  
深度思考| ultrathink, think harder| 复杂问题分析  
快速执行| just do it, yes| 确认方案后执行  
继续操作| go on, retry| 延续或重试  
上下文管理| /compact, /resume| 长对话管理  
输出控制| 输出到md, 输出到/notes| 结果持久化  
  
**核心原则：**

  1. 复杂问题用 ultrathink
  2. 明确任务用 just do it
  3. 长对话定期 /compact
  4. 重要结论输出到文件
  5. 常用知识沉淀到 CLAUDE.md