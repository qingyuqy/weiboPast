const assert = require('assert');
const M = require('../weibo-export.user.js');

const RAW_POST = {
  id: 1234567890123456,
  mid: '1234567890123456',
  mblogid: 'Nx0000001',
  created_at: 'Sat Aug 01 12:34:56 +0800 2026',
  region_name: '上海',
  source: '<a href="//weibo.com" rel="nofollow">iPhone客户端</a>',
  text_raw: '今天去看了海 #旅行# @朋友A 视频 https://t.cn/A12345 [doge] 第二行\n第三行',
  url_struct: [{ short_url: 'https://t.cn/A12345', long_url: 'https://example.com/sea', url_title: '海边视频' }],
  topic_struct: [{ topic_title: '旅行' }],
  isLongText: false,
  reposts_count: 12,
  comments_count: 34,
  attitudes_count: 56,
  is_show_bulletin: 2,
  user: { id: 9876543210, idstr: '9876543210', screen_name: '测试君', profile_image_url: 'https://avatar.example/x.jpg', avatar_large: 'https://avatar.example/l.jpg', avatar_hd: 'https://avatar.example/h.jpg' },
  pic_ids: ['001', '002'],
  pic_infos: {
    '001': { thumbnail: { url: 'https://wx1.sinaimg.cn/thumbnail/001.jpg' }, bmiddle: { url: 'https://wx1.sinaimg.cn/bmiddle/001.jpg' }, large: { url: 'https://wx1.sinaimg.cn/large/001.jpg' }, mw2000: { url: 'https://wx1.sinaimg.cn/mw2000/001.jpg' } },
    '002': { thumbnail: { url: 'https://wx1.sinaimg.cn/thumbnail/002.jpg' }, bmiddle: { url: 'https://wx1.sinaimg.cn/bmiddle/002.jpg' }, large: { url: 'https://wx1.sinaimg.cn/large/002.jpg' } },
  },
  page_info: { page_url: 'https://example.com/sea', page_title: '海边视频', page_pic: 'https://x/cover.jpg', page_desc: '一段视频' },
};

// ---- 基础工具 ----
assert.strictEqual(M.esc('<a b="c">&'), '&lt;a b=&quot;c&quot;&gt;&amp;');
assert.strictEqual(M.formatDate('2026-08-01 12:34:56'), '2026-08-01 12:34');
assert.strictEqual(M.parseXmlSource('<a href="//weibo.com" rel="nofollow">iPhone客户端</a>'), 'iPhone客户端');
console.log('[ok] esc / formatDate / parseXmlSource');

// ---- 图片解析与分级 ----
const imgs = M.parseImages(RAW_POST);
assert.strictEqual(imgs.length, 2, 'pic_ids 应解析出 2 张图');
assert.strictEqual(M.pickImageUrl(imgs[0], 3), 'https://wx1.sinaimg.cn/mw2000/001.jpg');
assert.strictEqual(M.pickImageUrl(imgs[1], 3), 'https://wx1.sinaimg.cn/large/002.jpg');
assert.strictEqual(M.pickImageUrl(imgs[1], 1), 'https://wx1.sinaimg.cn/bmiddle/002.jpg');
console.log('[ok] parseImages / pickImageUrl 分级');

// ---- 微博解析 ----
const post = M.parsePost(RAW_POST);
assert.ok(post, 'parsePost 应返回 post');
assert.strictEqual(post.id, '1234567890123456');
assert.strictEqual(post.userName, '测试君');
assert.strictEqual(post.source, 'iPhone客户端');
assert.strictEqual(post.regionName, '上海');
assert.strictEqual(post.repostsCount, 12);
assert.strictEqual(post.commentsCount, 34);
assert.strictEqual(post.likesCount, 56);
assert.ok(post.card && post.card.link === 'https://example.com/sea', '卡片链接应解析');
assert.strictEqual(post.retweet, null);
assert.strictEqual(post.comments.length, 0);
assert.ok(RAW_POST.text_raw.includes('https://t.cn/A12345'), '原文应保留短链');
console.log('[ok] parsePost');

// ---- 转发解析 ----
const rawRetweet = Object.assign({}, RAW_POST, {
  id: 999,
  mblogid: 'Rt0000001',
  user: { id: 111, idstr: '111', screen_name: '被转者', profile_image_url: 'https://a/x.jpg' },
});
const withRt = M.parsePost(Object.assign({}, RAW_POST, { retweeted_status: rawRetweet }));
assert.ok(withRt.retweet, '应解析出转发');
assert.strictEqual(withRt.retweet.userName, '被转者');
console.log('[ok] parsePost 转发');

// ---- 评论解析与渲染 ----
const rawComment = {
  id: 777,
  text_raw: '好图！@测试君 [赞]',
  created_at: 'Sat Aug 01 13:00:00 +0800 2026',
  floor_number: 3,
  like_counts: 9,
  total_number: 0,
  user: { id: 222, idstr: '222', screen_name: '评论者', profile_image_url: 'https://a/c.jpg' },
  url_struct: [],
};
const comment = M.parseComment(rawComment);
assert.strictEqual(comment.userName, '评论者');
assert.strictEqual(comment.floor, 3);
assert.strictEqual(comment.likesCount, 9);
console.log('[ok] parseComment');

// ---- 文本转换 ----
const emojiMap = new Map([['[doge]', 'https://emoji.example/doge.png']]);
const htmlText = M.transText(RAW_POST.text_raw, post, emojiMap);
assert.ok(htmlText.includes('https://example.com/sea'), '短链应替换为长链');
assert.ok(htmlText.includes('weibo.com/n/'), '@提及应转链接');
assert.ok(htmlText.includes('s.weibo.com/weibo?q='), '#话题#应转链接');
assert.ok(htmlText.includes('emoji.example/doge.png'), '表情应转图片');
assert.ok(htmlText.includes('<br>'), '换行应转 <br>');
assert.ok(htmlText.includes('今天去看了海'), '正文应保留');
console.log('[ok] transText 短链/@/话题/表情/换行');

// ---- HTML 整页生成（link 模式） ----
(async () => {
  const opts = { picMode: 'link', picLevel: 3, sort: 'asc', skipEmoticon: true, embedCache: new Map() };
  const user = { uid: '9876543210', name: '测试君', avatar: 'https://avatar.example/l.jpg' };
  const html = await M.buildHtml(user, [post], [], [], opts);
  assert.ok(html.startsWith('<!DOCTYPE html>'), '应输出完整 HTML');
  assert.ok(html.includes('微博存档'), '应有封面标题');
  assert.ok(html.includes('测试君'), '封面应含昵称');
  assert.ok(html.includes('共 1 条微博'), '封面应含数量');
  assert.ok(html.includes('今天去看了海'), '正文应在 HTML 中');
  assert.ok(html.includes('https://wx1.sinaimg.cn/mw2000/001.jpg'), 'HTML 应引用大图链接');
  assert.ok(html.includes('12 转发 · 34 评论 · 56 点赞'), '应有统计行');
  assert.ok(html.includes('@page{size:A4'), '应有打印样式');
  assert.ok(html.includes('</html>'), '应以 </html> 结尾');

  // 排序：旧→新
  const oldPost = M.parsePost(Object.assign({}, RAW_POST, { mblogid: 'Nx0000002', created_at: 'Sat Jul 01 10:00:00 +0800 2020' }));
  const htmlAsc = await M.buildHtml(user, [post, oldPost], [], [], opts);
  const idxNew = htmlAsc.indexOf('Nx0000001');
  const idxOld = htmlAsc.indexOf('Nx0000002');
  assert.ok(idxOld > 0 && idxNew > idxOld, 'asc 排序应旧帖在前');
  console.log('[ok] buildHtml 整页 / 排序');

  // renderPost 单独渲染
  const cardHtml = await M.renderPost(post, opts);
  assert.ok(cardHtml.includes('<article class="card">'));
  assert.ok(cardHtml.includes('原帖 ↗'));
  console.log('[ok] renderPost');

  // renderComment 异步渲染
  const commentHtml = await M.renderComment(comment, { embedAvatar: false });
  assert.ok(commentHtml.includes('评论者'), '评论渲染应含用户名');
  console.log('[ok] renderComment');

  // buildHtmlParts 与 buildHtml 一致性
  const partsArr = await M.buildHtmlParts(user, [post], [], [], opts);
  assert.ok(Array.isArray(partsArr) && partsArr.length >= 3, 'buildHtmlParts 应返回数组');
  assert.strictEqual(partsArr.join(''), html, 'parts 拼接应与 buildHtml 一致');
  console.log('[ok] buildHtmlParts');

  // 面板 id 唯一性：日期框与「开始导出」按钮曾共用 id="wbx-start"，
  // 导致 querySelector 拿到日期框、按钮点击监听器绑错元素（点开始无反应）
  const fs = require('fs');
  const path = require('path');
  const src = fs.readFileSync(path.join(__dirname, '..', 'weibo-export.user.js'), 'utf8');
  const ids = Array.from(src.matchAll(/id="(wbx-[a-z-]+)"/g), (m) => m[1]);
  const dup = ids.filter((v, i) => ids.indexOf(v) !== i);
  assert.deepStrictEqual(dup, [], '面板 id 不应重复，重复项：' + dup.join(', '));
  assert.ok(src.includes("startBtn: panel.querySelector('#wbx-start')"), '开始按钮应绑定到按钮元素');
  assert.ok(src.includes("startInput: panel.querySelector('#wbx-start-date')"), '开始日期应绑定到日期输入框');
  assert.ok(src.includes("endInput: panel.querySelector('#wbx-end-date')"), '结束日期应绑定到日期输入框');
  console.log('[ok] 面板 id 唯一 / 开始按钮绑定');

  // 翻页请求不能再同时带 page>1 和 since_id：微博会按游标+偏移双重生效，
  // 每页额外跳过一整页，表现为「只抓到一两百条就完成」
  const pageLoop = src.slice(
    src.indexOf('async function fetchAllBySinceId'),
    src.indexOf('async function fetchByCursorBackfill')
  );
  assert.ok(!/since_id\s*:/.test(pageLoop), 'page 翻页不应传 since_id');
  const cursorFn = src.slice(
    src.indexOf('async function fetchByCursorBackfill'),
    src.indexOf('async function fetchByWindowBackfill')
  );
  assert.ok(cursorFn.includes('since_id: cursor'), '游标兜底应使用 since_id');
  assert.ok(cursorFn.includes('page: 1'), '游标兜底必须把 page 固定为 1');
  assert.ok(src.includes('fetchByWindowBackfill'), '应有时间窗口兜底补齐逻辑');
  console.log('[ok] 翻页策略（page 翻页 / since_id 游标 / 时间窗口三级）');

  console.log('ALL TESTS PASSED');
})().catch((e) => { console.error('TEST FAILED:', e); process.exit(1); });