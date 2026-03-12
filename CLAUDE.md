# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is a technical blog archive containing 83+ articles exported from 掘金 (juejin.cn). Articles are written in Chinese, targeting mid-to-senior level Java developers.

## Structure

- `articles/` - Markdown files with naming convention `YYYY-MM-DD_Title.md`
- `INDEX.md` - Article index sorted by popularity (reads > likes > saves > date)

## Article Format

Each article has YAML frontmatter:
```yaml
---
title: "Article Title"
date: YYYY-MM-DD
url: https://juejin.cn/post/xxx
views: number
likes: number
collects: number
source: nuxt|html
---
```

## Content Themes

Primary topics (by frequency):
- **CompletableFuture & async programming** - extensive coverage including source code analysis, pitfalls, best practices
- **Guava library** - immutable collections, utilities, Forwarding classes
- **Concurrent programming** - thread pools, locks, COW, ConcurrentHashMap
- **Functional programming** - Vavr/Javaslang, monads, lazy evaluation
- **Java fundamentals** - generics, collections, reference types, JVM internals
- **Design patterns & code quality** - Builder pattern, fluent interfaces, refactoring

## Writing New Articles

When writing technical blog articles for this repository:
- Target audience: mid-to-senior Java developers
- Include runnable code examples with `main` method test cases
- Follow existing article structure and frontmatter format
- Use the `/blog-writer` skill for general technical blogs
- Use the `/source-code-reader` skill for source code analysis articles
