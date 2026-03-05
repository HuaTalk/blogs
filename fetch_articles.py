#!/usr/bin/env python3
"""
从稀土掘金批量抓取用户文章并保存为 Markdown 文件。
策略1: 从页面NUXT SSR数据提取mark_content（原始Markdown）
策略2: 从页面HTML中提取article-root并用html2text转Markdown
"""

import json
import os
import re
import ssl
import time
import urllib.request
import urllib.error
import html2text

# 跳过SSL验证（公司代理证书问题）
ssl._create_default_https_context = ssl._create_unverified_context

USER_ID = "2212689394274136"
OUTPUT_DIR = "/Users/linqh/juejin_articles"

# 掘金API
LIST_API = "https://api.juejin.cn/content_api/v1/article/query_list"
ARTICLE_URL = "https://juejin.cn/post/{}"

# html2text 配置
H2T = html2text.HTML2Text()
H2T.body_width = 0  # 不自动换行
H2T.unicode_snob = True
H2T.protect_links = True
H2T.wrap_links = False


def sanitize_filename(title):
    """清理文件名中的非法字符"""
    title = re.sub(r'[\\/:*?"<>|\n\r\t]', '_', title)
    title = title.strip('. ')
    if len(title) > 100:
        title = title[:100]
    return title


def fetch_json(url, data):
    """发送POST请求获取JSON"""
    req = urllib.request.Request(
        url,
        data=json.dumps(data).encode('utf-8'),
        headers={
            'Content-Type': 'application/json',
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read().decode('utf-8'))
    except urllib.error.URLError as e:
        print(f"  请求失败: {e}")
        return None


def fetch_page(url):
    """获取页面HTML"""
    req = urllib.request.Request(
        url,
        headers={
            'User-Agent': 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'
        }
    )
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.read().decode('utf-8')
    except urllib.error.URLError as e:
        print(f"  页面请求失败: {e}")
        return None


def get_article_list():
    """获取用户所有文章的ID和标题"""
    articles = []
    cursor = "0"
    page = 0

    while True:
        page += 1
        print(f"正在获取文章列表第 {page} 页 (cursor={cursor})...")

        data = {
            "user_id": USER_ID,
            "sort_type": 2,
            "cursor": cursor
        }
        result = fetch_json(LIST_API, data)

        if not result or result.get('err_no') != 0:
            print(f"  获取列表失败: {result}")
            break

        items = result.get('data', [])
        if not items:
            break

        for item in items:
            info = item.get('article_info', {})
            articles.append({
                'article_id': info.get('article_id'),
                'title': info.get('title', '无标题'),
                'ctime': info.get('ctime', '0'),
                'view_count': info.get('view_count', 0),
                'digg_count': info.get('digg_count', 0),
                'collect_count': info.get('collect_count', 0),
            })

        if not result.get('has_more', False):
            break

        cursor = result.get('cursor', str(len(articles)))
        time.sleep(0.3)

    print(f"共获取到 {len(articles)} 篇文章")
    return articles


def unescape_js_string(s):
    """反转义JS字符串中的转义序列"""
    s = re.sub(r'\\u([0-9a-fA-F]{4})', lambda m: chr(int(m.group(1), 16)), s)
    s = s.replace('\\n', '\n')
    s = s.replace('\\t', '\t')
    s = s.replace('\\"', '"')
    s = s.replace("\\'", "'")
    s = s.replace('\\\\', '\\')
    return s


def extract_from_nuxt(html):
    """策略1: 从NUXT SSR数据中提取mark_content原始Markdown"""
    nuxt_match = re.search(r'window\.__NUXT__.*?=\s*(.+?)</script>', html, re.DOTALL)
    if not nuxt_match:
        return None

    raw = nuxt_match.group(1)
    idx = raw.find('mark_content:"')
    if idx < 0:
        return None

    start = idx + len('mark_content:"')
    # 逐字符扫描找到未转义的结束引号
    end = start
    while end < len(raw):
        ch = raw[end]
        if ch == '\\':
            end += 2
            continue
        if ch == '"':
            break
        end += 1

    content = raw[start:end]
    content = unescape_js_string(content)
    if len(content) > 50:
        return content

    return None


def extract_from_html(html):
    """策略2: 从HTML中提取article-root内容并转为Markdown"""
    # 使用字符串查找代替正则，更健壮
    start_marker = 'id="article-root"'
    start_idx = html.find(start_marker)
    if start_idx < 0:
        return None

    # 找到标签的闭合 >
    gt_idx = html.find('>', start_idx)
    if gt_idx < 0:
        return None
    content_start = gt_idx + 1

    # 找到 </article> 结束标签
    end_idx = html.find('</article>', content_start)
    if end_idx < 0:
        return None

    article_html = html[content_start:end_idx]
    if len(article_html) < 50:
        return None

    md = H2T.handle(article_html)
    if len(md.strip()) > 50:
        return md.strip()

    return None


def get_article_content(article_id):
    """获取文章Markdown内容，优先NUXT原始MD，回退HTML转MD"""
    url = ARTICLE_URL.format(article_id)
    html = fetch_page(url)
    if not html:
        return None, "page_error"

    # 策略1: NUXT SSR数据
    content = extract_from_nuxt(html)
    if content:
        return content, "nuxt"

    # 策略2: HTML转Markdown
    content = extract_from_html(html)
    if content:
        return content, "html2md"

    return None, "no_content"


def save_article(index, total, article, content, source):
    """保存文章为Markdown文件"""
    title = article['title']
    ctime = time.strftime('%Y-%m-%d', time.localtime(int(article['ctime'])))
    filename = sanitize_filename(f"{ctime}_{title}") + ".md"
    filepath = os.path.join(OUTPUT_DIR, filename)

    header = f"""---
title: "{title}"
date: {ctime}
url: https://juejin.cn/post/{article['article_id']}
views: {article['view_count']}
likes: {article['digg_count']}
collects: {article['collect_count']}
source: {source}
---

"""
    with open(filepath, 'w', encoding='utf-8') as f:
        f.write(header + content)

    print(f"  [{index}/{total}] 已保存 ({source}): {filename}")
    return filepath


def main():
    print("=" * 60)
    print("掘金文章批量导出工具")
    print(f"用户ID: {USER_ID}")
    print(f"输出目录: {OUTPUT_DIR}")
    print("=" * 60)

    # 1. 获取文章列表
    articles = get_article_list()
    if not articles:
        print("未获取到任何文章，退出。")
        return

    # 2. 逐篇获取内容并保存
    print(f"\n开始逐篇获取文章内容...")
    success = 0
    nuxt_count = 0
    html_count = 0
    failed = []

    for i, article in enumerate(articles, 1):
        article_id = article['article_id']
        print(f"  [{i}/{len(articles)}] 获取: {article['title'][:50]}...")

        content, source = get_article_content(article_id)

        if content and len(content) > 50:
            save_article(i, len(articles), article, content, source)
            success += 1
            if source == "nuxt":
                nuxt_count += 1
            else:
                html_count += 1
        else:
            print(f"  [{i}/{len(articles)}] 获取内容失败 ({source}): {article['title']}")
            failed.append(article)

        # 每篇间隔1秒避免限流
        time.sleep(1)

    # 3. 生成目录索引文件
    print(f"\n生成目录索引...")
    index_path = os.path.join(OUTPUT_DIR, "INDEX.md")
    with open(index_path, 'w', encoding='utf-8') as f:
        f.write(f"# 掘金文章索引\n\n")
        f.write(f"共 {len(articles)} 篇文章，成功导出 {success} 篇 (NUXT: {nuxt_count}, HTML转MD: {html_count})\n\n")
        f.write(f"| # | 日期 | 标题 | 阅读 | 点赞 | 收藏 |\n")
        f.write(f"|---|------|------|------|------|------|\n")
        for i, a in enumerate(articles, 1):
            ctime = time.strftime('%Y-%m-%d', time.localtime(int(a['ctime'])))
            f.write(f"| {i} | {ctime} | [{a['title']}](https://juejin.cn/post/{a['article_id']}) | {a['view_count']} | {a['digg_count']} | {a['collect_count']} |\n")

    # 4. 打印结果
    print(f"\n{'=' * 60}")
    print(f"导出完成!")
    print(f"  成功: {success} 篇 (原始MD: {nuxt_count}, HTML转MD: {html_count})")
    print(f"  失败: {len(failed)} 篇")
    print(f"  目录: {OUTPUT_DIR}")
    print(f"  索引: {index_path}")
    if failed:
        print(f"\n失败的文章:")
        for a in failed:
            print(f"  - {a['title']} (ID: {a['article_id']})")
    print(f"{'=' * 60}")


if __name__ == '__main__':
    main()
