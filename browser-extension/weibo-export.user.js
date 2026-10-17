// ==UserScript==
// @name         微博备份导出（本人微博 → JSON / 可打印 HTML / PDF）
// @name:en      Weibo Export (own posts -> JSON / printable HTML / PDF)
// @namespace    qingyu.weibo-export
// @version      0.2.12
// @description  结合 Weibo-archiver 与 WeiBack 思路：抓取本人全部微博（since_id 全量翻页、长文、评论、收藏、关注），IndexedDB 本地存储支持断点续传；导出 JSON 与可打印 HTML（浏览器 Ctrl+P 存 PDF），可选图片内嵌做离线导出。
// @description:en Fetch your own Weibo posts (since_id paging, long-text, comments, favorites, followings), store in IndexedDB with resume; export JSON & printable HTML (Ctrl+P to PDF), optional embedded images for offline use.
// @author       qingyu
// @match        https://weibo.com/*
// @match        https://*.weibo.com/*
// @run-at       document-idle
// @noframes
// @license      MIT
// ==/UserScript==

(function () {
  'use strict';

  // ==================== 常量 ====================
  const API_BASE = 'https://weibo.com/ajax';
  const LS_OPTIONS = 'wbx-options-v1';
  const DB_NAME = 'weibo-export';
  const DB_VERSION = 1;
  const SCRIPT_VERSION = '0.2.12';
  const doc = typeof document !== 'undefined' ? document : null;
  const win = typeof window !== 'undefined' ? window : null;

  const DEFAULT_OPTIONS = {
    uid: '',               // 数字 uid，留空时自动从当前页面检测
    delay: 800,            // 每页请求间隔（毫秒），防风控
    isFetchAll: true,      // true=全部微博；false=指定时间范围
    startAt: '',           // yyyy-MM-dd
    endAt: '',             // yyyy-MM-dd
    hasRepost: true,       // 包含转发微博
    longText: true,        // 展开长微博全文
    hasComment: false,     // 包含一级评论
    commentCount: 5,       // 每条评论数上限
    hasFavorites: false,   // 导出收藏
    hasFollowings: false,  // 导出关注列表
    picLevel: 3,           // 1=小图 2=中图 3=大图/原图
    picMode: 'link',       // 'link'=引用图片链接（文件小） 'embed'=下载图片内嵌（离线可用，文件大）
    sort: 'asc',           // 'asc'=旧→新 'desc'=新→旧
    restore: true,         // 断点续传
    embedMaxMB: 20,        // embed 模式下单图大小上限
    embedAvatar: true,     // 头像下载内嵌（图床防盗链，内嵌才能离线/PDF 显示）
    avatarMaxSize: 160,    // 头像内嵌前按最大边长缩放（原始头像 720px，显示只需 40px）
  };

  // ==================== 状态 ====================
  const state = {
    running: false,
    paused: false,
    stopReq: false,
    sinceId: '',
    page: 1,
    total: 0,
    fetched: 0,
    favCount: 0,
    followCount: 0,
    user: null,
    phaseFlags: {},
    stopReason: '',
  };

  // ==================== 基础工具 ====================
  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

  function esc(s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  function pad(n) {
    return String(n).padStart(2, '0');
  }

  function parseDate(input) {
    const d = new Date(input);
    return isNaN(d.getTime()) ? null : d;
  }

  function formatDate(input, fmt) {
    const d = parseDate(input);
    if (!d) return String(input || '');
    const f = fmt || 'YYYY-MM-DD HH:mm';
    return f
      .replace('YYYY', d.getFullYear())
      .replace('MM', pad(d.getMonth() + 1))
      .replace('DD', pad(d.getDate()))
      .replace('HH', pad(d.getHours()))
      .replace('mm', pad(d.getMinutes()))
      .replace('ss', pad(d.getSeconds()));
  }

  function parseXmlSource(xml) {
    if (!xml) return '';
    return xml
      .replace(/<[^>]*>/g, '')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&amp;/g, '&')
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/&#(\d+);/g, (_, dec) => String.fromCharCode(dec))
      .replace(/&#x([0-9a-f]+);/gi, (_, hex) => String.fromCharCode(parseInt(hex, 16)))
      .replace(/\s+/g, ' ')
      .trim();
  }

  function getOptions() {
    let saved = {};
    try {
      if (typeof localStorage !== 'undefined') {
        saved = JSON.parse(localStorage.getItem(LS_OPTIONS) || '{}');
      }
    } catch (e) { /* ignore */ }
    return Object.assign({}, DEFAULT_OPTIONS, saved);
  }

  function saveOptions() {
    if (typeof localStorage === 'undefined') return;
    try {
      const el = $wbx && $wbx.uidInput ? $wbx.uidInput.value : '';
      if (el) opts.uid = el.trim();
      localStorage.setItem(LS_OPTIONS, JSON.stringify(opts));
    } catch (e) { /* ignore */ }
  }

  let opts = getOptions();
  let $wbx = null; // UI 元素缓存
  // ==================== IndexedDB（断点续传 + 随时重导出） ====================
  let _dbPromise = null;

  function openDB() {
    if (_dbPromise) return _dbPromise;
    _dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, DB_VERSION);
      req.onupgradeneeded = (e) => {
        const db = e.target.result;
        if (!db.objectStoreNames.contains('posts')) db.createObjectStore('posts', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('favorites')) db.createObjectStore('favorites', { keyPath: 'id' });
        if (!db.objectStoreNames.contains('followings')) db.createObjectStore('followings', { keyPath: 'uid' });
        if (!db.objectStoreNames.contains('meta')) db.createObjectStore('meta', { keyPath: 'key' });
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return _dbPromise;
  }

  function dbRequest(mode, store, fn) {
    return openDB().then((db) => new Promise((resolve, reject) => {
      const tx = db.transaction(store, mode);
      const s = tx.objectStore(store);
      const result = fn(s);
      tx.oncomplete = () => resolve(result && result.result !== undefined ? result.result : undefined);
      tx.onerror = () => reject(tx.error);
      tx.onabort = () => reject(tx.error || new Error('aborted'));
    }));
  }

  function dbPutAll(store, items) {
    if (!items || !items.length) return Promise.resolve();
    return dbRequest('readwrite', store, (s) => items.forEach((it) => s.put(it)));
  }

  function dbGetAll(store) {
    return dbRequest('readonly', store, (s) => s.getAll());
  }

  function dbCount(store) {
    return dbRequest('readonly', store, (s) => s.count());
  }

  function dbClear(store) {
    return dbRequest('readwrite', store, (s) => s.clear());
  }

  function metaSet(key, val) {
    return dbRequest('readwrite', 'meta', (s) => s.put({ key, val }));
  }

  function metaGet(key) {
    return dbRequest('readonly', 'meta', (s) => s.get(key)).then((r) => (r ? r.val : undefined));
  }

  // ==================== API 请求（带重试退避与风控识别） ====================
  class WeiboApiError extends Error {}

  async function waitWhilePaused() {
    while (state.paused) {
      if (state.stopReq) throw new Error('已停止');
      await sleep(300);
    }
    if (state.stopReq) throw new Error('已停止');
  }

  function fetchWithTimeout(url, ms, init) {
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), ms);
    return fetch(url, Object.assign({}, init, { signal: ctrl.signal }))
      .finally(() => clearTimeout(timer));
  }

  // 微博部分接口要求携带与 cookie 同名的 CSRF 头，缺失时可能被拒
  function xsrfHeaders() {
    try {
      const m = (doc && doc.cookie ? doc.cookie : '').match(/(?:^|;\s*)XSRF-TOKEN=([^;]+)/);
      if (m) return { 'x-xsrf-token': decodeURIComponent(m[1]) };
    } catch (e) { /* ignore */ }
    return {};
  }

  async function apiGet(path, params, opt) {
    const cfg = opt || {};
    const retries = cfg.retries === undefined ? 4 : cfg.retries;
    const retryDelay = cfg.retryDelay === undefined ? 3000 : cfg.retryDelay;
    const qs = new URLSearchParams();
    for (const [k, v] of Object.entries(params || {})) {
      if (v !== undefined && v !== null && v !== '') qs.set(k, String(v));
    }
    const url = API_BASE + path + '?' + qs.toString();
    let attempt = 0;
    for (;;) {
      await waitWhilePaused();
      try {
        const res = await fetchWithTimeout(url, cfg.timeout || 20000, {
          credentials: 'include',
          headers: Object.assign({ 'X-Requested-With': 'XMLHttpRequest' }, xsrfHeaders()),
        });
        if (!res.ok) throw new WeiboApiError('HTTP ' + res.status + ' @ ' + path);
        // 先按文本读，便于识别「被重定向到登录页」这类非 JSON 响应
        const text = await res.text();
        let json;
        try {
          json = JSON.parse(text);
        } catch (e) {
          const head = text.replace(/\s+/g, ' ').slice(0, 120);
          throw new WeiboApiError('返回的不是 JSON（登录态失效或被风控页拦截）：' + head);
        }
        if (json && (json.ok === 1 || (json.ok === undefined && json.data))) return json;
        if (json && (json.ok === -100 || json.ok === -101 || json.ok === -3)) {
          throw new WeiboApiError('触发微博风控/验证码（ok=' + json.ok + '），请先到网页手动验证，再点“继续导出”');
        }
        throw new WeiboApiError('接口返回异常 ok=' + (json && json.ok) +
          (json && json.msg ? ' msg=' + json.msg : '') + ' @ ' + path);
      } catch (e) {
        attempt++;
        if (attempt > retries) throw e;
        const wait = Math.min(60000, retryDelay * Math.pow(2, attempt - 1));
        setStatus('请求失败，' + Math.round(wait / 1000) + 's 后重试（' + attempt + '/' + retries + '）：' + e.message);
        await sleep(wait + Math.random() * 1000);
      }
    }
  }
  // ==================== 解析（参照 Weibo-archiver 的 parseService） ====================
  function parseImages(raw) {
    const result = [];
    const seen = new Set();
    const mixItems = raw.mix_media_info && raw.mix_media_info.items;
    if (Array.isArray(mixItems)) {
      for (const item of mixItems) {
        if (item && item.type === 'pic' && item.data) {
          const key = item.data.pid || item.data.url || ('m' + result.length);
          if (!seen.has(key)) {
            seen.add(key);
            result.push(item.data);
          }
        }
      }
    }
    const picInfos = raw.pic_infos || {};
    const picIds = Array.isArray(raw.pic_ids) ? raw.pic_ids : Object.keys(picInfos);
    for (const pid of picIds) {
      if (picInfos[pid] && !seen.has(pid)) {
        seen.add(pid);
        result.push(picInfos[pid]);
      }
    }
    return result;
  }

  function pickImageUrl(info, level) {
    if (!info) return '';
    const orders = {
      1: ['bmiddle', 'thumbnail', 'large', 'mw690'],
      2: ['large', 'bmiddle', 'mw690', 'mw2000', 'thumbnail'],
      3: ['mw2000', 'largest', 'original', 'large', 'bmiddle'],
    };
    const order = orders[level] || orders[3];
    for (const k of order) {
      const u = info[k] && info[k].url;
      if (u) return u;
    }
    const t = info.thumbnail && info.thumbnail.url;
    if (t) return t.replace('/thumbnail/', '/large/');
    return '';
  }

  function parseLinkCard(raw) {
    const pi = raw.page_info;
    if (!pi) return null;
    let link = pi.page_url || '';
    try {
      const u = new URL(link);
      if (u.searchParams.get('url')) link = u.searchParams.get('url');
    } catch (e) { /* keep as-is */ }
    if (pi.media_info && pi.media_info.h5_url) link = pi.media_info.h5_url;
    return {
      link,
      title: pi.page_title || '',
      img: pi.page_pic || '',
      desc: pi.page_desc || pi.content1 || pi.content2 || '',
    };
  }

  function parseUserFromPost(raw) {
    const u = raw.user;
    if (!u) return { uid: '', name: '', avatar: '' };
    return {
      uid: String(u.idstr || u.id || ''),
      name: u.screen_name || '',
      avatar: u.avatar_hd || u.avatar_large || u.profile_image_url || '',
    };
  }

  function parsePost(raw) {
    const mblogid = raw.mblogid;
    if (!mblogid) return null;
    const user = parseUserFromPost(raw);
    let retweet = null;
    if (raw.retweeted_status && raw.retweeted_status.id) {
      retweet = parseRetweet(raw.retweeted_status);
    }
    return {
      id: String(raw.id || raw.mid || ''),
      mblogid,
      userId: user.uid,
      userName: user.name,
      avatar: user.avatar,
      text: raw.text_raw || raw.text || '',
      createdAt: raw.created_at || '',
      regionName: raw.region_name || '',
      source: parseXmlSource(raw.source),
      repostsCount: raw.reposts_count || 0,
      commentsCount: raw.comments_count || 0,
      likesCount: raw.attitudes_count || 0,
      isShowBulletin: String(raw.is_show_bulletin === undefined ? 2 : raw.is_show_bulletin),
      urlStruct: raw.url_struct || [],
      topicStruct: raw.topic_struct || [],
      imgs: parseImages(raw),
      card: parseLinkCard(raw),
      retweet,
      comments: [],
    };
  }

  function parseRetweet(raw) {
    if (!raw || !raw.mblogid) return null;
    const user = parseUserFromPost(raw);
    return {
      id: String(raw.id || raw.mid || ''),
      mblogid: raw.mblogid,
      userId: user.uid,
      userName: user.name,
      avatar: user.avatar,
      text: raw.text_raw || raw.text || '',
      createdAt: raw.created_at || '',
      regionName: raw.region_name || '',
      source: parseXmlSource(raw.source),
      repostsCount: raw.reposts_count || 0,
      commentsCount: raw.comments_count || 0,
      likesCount: raw.attitudes_count || 0,
      urlStruct: raw.url_struct || [],
      topicStruct: raw.topic_struct || [],
      imgs: parseImages(raw),
    };
  }

  function parseComment(raw) {
    if (!raw) return null;
    let img = '';
    const us = raw.url_struct || [];
    for (const item of us) {
      const infos = item.pic_infos || {};
      const first = Object.values(infos)[0];
      if (first && first.woriginal && first.woriginal.url) { img = first.woriginal.url; break; }
    }
    const user = parseUserFromPost(raw);
    return {
      id: String(raw.id || ''),
      text: raw.text_raw || raw.text || '',
      createdAt: raw.created_at || '',
      regionName: raw.region_name || raw.source || '',
      floor: raw.floor_number || 0,
      commentsCount: raw.total_number || 0,
      likesCount: raw.like_counts || 0,
      img,
      userName: user.name,
      avatar: user.avatar,
      urlStruct: us,
    };
  }

  function parseProfile(rawUser) {
    if (!rawUser) return null;
    return {
      uid: String(rawUser.id || rawUser.idstr || ''),
      name: rawUser.screen_name || '',
      avatar: rawUser.avatar_hd || rawUser.avatar_large || rawUser.profile_image_url || '',
      bio: rawUser.description || '',
      followers: rawUser.followers_count || 0,
      followings: rawUser.friends_count || 0,
      statusesCount: rawUser.statuses_count || 0,
    };
  }
  // ==================== 抓取（参照 Weibo-archiver 的 PostService） ====================
  let emoticonMap = new Map();

  async function ensureEmoticon() {
    if (emoticonMap.size) return;
    try {
      const json = await apiGet('/statuses/config', {}, { retries: 2, retryDelay: 2000 });
      const raw = (json.data && json.data.emoticon) || {};
      const map = new Map();
      for (const lang of Object.values(raw)) {
        for (const setName of Object.values(lang)) {
          if (!Array.isArray(setName)) continue;
          for (const e of setName) {
            if (e && e.phrase && e.url) map.set(e.phrase, e.url);
          }
        }
      }
      if (map.size) emoticonMap = map;
    } catch (e) { /* 失败则表情保持文字形式 */ }
  }

  async function fetchLongText(mblogid) {
    try {
      const json = await apiGet('/statuses/longtext', { id: mblogid }, { retries: 2, retryDelay: 2000 });
      return (json.data && json.data.longTextContent) || '';
    } catch (e) {
      return '';
    }
  }

  async function expandRawList(list, enabled) {
    if (!enabled) return;
    for (const item of list) {
      await waitWhilePaused();
      const targets = [];
      if (item && item.isLongText && item.mblogid) targets.push(item);
      if (item && item.retweeted_status && item.retweeted_status.isLongText && item.retweeted_status.mblogid) {
        targets.push(item.retweeted_status);
      }
      for (const t of targets) {
        const full = await fetchLongText(t.mblogid);
        if (full) t.text_raw = full;
        await sleep(Math.min(300, opts.delay));
      }
    }
  }

  async function fetchCommentsFor(post) {
    if (!opts.hasComment || opts.commentCount <= 0 || !post.commentsCount) return;
    await waitWhilePaused();
    try {
      const json = await apiGet('/statuses/buildComments', {
        is_reload: 1,
        id: post.id,
        is_show_bulletin: post.isShowBulletin,
        is_mix: 0,
        count: opts.commentCount,
        uid: opts.uid,
        flow: 0,
        fetch_level: 0,
        locale: 'zh_CN',
      }, { retries: 2, retryDelay: 2000 });
      const list = Array.isArray(json.data) ? json.data : [];
      post.comments = list.slice(0, opts.commentCount).map(parseComment).filter(Boolean);
    } catch (e) {
      post.comments = [];
    }
    await sleep(Math.min(400, opts.delay));
  }

  async function saveResume(uid) {
    await metaSet('resume-' + uid, {
      uid,
      sinceId: state.sinceId,
      page: state.page,
      fetched: state.fetched,
      total: state.total,
      phases: state.phaseFlags,
    });
  }

  async function loadResume(uid) {
    const r = await metaGet('resume-' + uid);
    if (r && r.uid === uid) {
      state.sinceId = r.sinceId || '';
      state.page = r.page || 1;
      state.fetched = r.fetched || 0;
      state.total = r.total || 0;
      state.phaseFlags = r.phases || {};
      return true;
    }
    return false;
  }

  async function fetchAllBySinceId(uid) {
    // 只用 page 翻页。若同时传 page 和 since_id，微博会把 since_id 当游标
    // （只返回比它更早的微博），又叠加 page 偏移，等于每页多跳一整页。
    // page 本身还有深度上限（实测约 13~14 页后返回空列表），因此到顶后
    // 依次改走「since_id 游标」和「时间窗口」两条兜底路径。
    let page = state.page || 1;
    let guard = 0;
    const seenIds = new Set();
    let stalePages = 0;
    let oldestDate = null;
    let oldestId = '';

    // 续传时先把已缓存的数据读进来：既能正确去重，也能从真正最旧的一条继续
    try {
      const existing = await dbGetAll('posts');
      for (const p of existing) {
        const id = String(p.id);
        seenIds.add(id);
        const d = parseDate(p.createdAt);
        if (d && (!oldestDate || d < oldestDate)) oldestDate = d;
        if (/^\d+$/.test(id) && (!oldestId || BigInt(id) < BigInt(oldestId))) oldestId = id;
      }
      if (seenIds.size) console.log('[weibo-export] 本地已有 ' + seenIds.size + ' 条缓存，继续抓取');
    } catch (e) { /* 读不到就按空缓存处理 */ }

    while (true) {
      if (++guard > 20000) { state.stopReason = '达到翻页次数上限'; break; }
      await waitWhilePaused();
      const json = await apiGet('/statuses/mymblog', {
        uid,
        page,
        feature: 0,
        hasori: 1,
        hasret: opts.hasRepost ? 1 : 0,
        haspic: 1,
        hastext: 1,
        hasvideo: 1,
        hasmuisc: 1,
      });
      const data = json.data || {};
      const list = Array.isArray(data.list) ? data.list : [];
      if (data.total) state.total = data.total;

      await expandRawList(list, opts.longText);
      const posts = list.map(parsePost).filter(Boolean);
      for (const p of posts) await fetchCommentsFor(p);

      const fresh = posts.filter((p) => !seenIds.has(p.id));
      fresh.forEach((p) => seenIds.add(p.id));

      await dbPutAll('posts', posts);
      state.fetched += fresh.length;
      state.page = page;
      await saveResume(uid);
      updateProgress();

      const tail = posts[posts.length - 1];
      if (tail) oldestDate = parseDate(tail.createdAt) || oldestDate;
      const pageMin = minIdOf(posts);
      if (pageMin && (!oldestId || BigInt(pageMin) < BigInt(oldestId))) oldestId = pageMin;
      console.log('[weibo-export] page=' + page + ' 本页=' + list.length + ' 新增=' + fresh.length +
        ' 累计=' + state.fetched + '/' + (state.total || '?') +
        ' 最旧=' + (tail ? tail.createdAt : '-'));

      if (!list.length) { state.stopReason = '第 ' + page + ' 页返回空列表'; break; }
      if (!fresh.length) {
        if (++stalePages >= 2) { state.stopReason = '第 ' + page + ' 页起无新数据'; break; }
      } else {
        stalePages = 0;
      }
      if (state.total && state.fetched >= state.total) { state.stopReason = '已达到接口总数'; break; }
      page++;
      await sleep(opts.delay);
    }

    // 兜底 1：把最旧一条的 id 当游标，page 固定为 1 继续往前翻
    if (state.total && state.fetched < state.total - 5 && oldestId) {
      setStatus('翻页到第 ' + page + ' 页被截断（' + state.fetched + '/' + state.total + '），改用游标继续…');
      await fetchByCursorBackfill(uid, seenIds, oldestId);
    }
    // 兜底 2：游标也取不到时，按时间窗口继续
    if (state.total && state.fetched < state.total - 5 && oldestDate) {
      setStatus('游标取到 ' + state.fetched + '/' + state.total + ' 条，改用时间窗口继续补齐…');
      await fetchByWindowBackfill(uid, seenIds, oldestDate);
      state.stopReason = (state.stopReason ? state.stopReason + ' → ' : '') + '时间窗口补齐';
    }
  }

  // 以最旧一条微博的 id 为游标，page 固定 1 继续往前抓。
  // 深度翻页受 page 上限影响时，游标路径往往还能继续。
  async function fetchByCursorBackfill(uid, seen, startId) {
    // 从「已知最旧 id - 1」开始，避免把边界那条微博重复请求一遍
    let cursor = /^\d+$/.test(String(startId))
      ? (BigInt(String(startId)) - 1n).toString()
      : '';
    let guard = 0;
    let stale = 0;

    while (cursor) {
      if (++guard > 5000) { state.stopReason = '游标翻页次数上限'; break; }
      if (state.total && state.fetched >= state.total) break;
      await waitWhilePaused();
      const json = await apiGet('/statuses/mymblog', {
        uid,
        page: 1,
        feature: 0,
        since_id: cursor,
        hasori: 1,
        hasret: opts.hasRepost ? 1 : 0,
        haspic: 1,
        hastext: 1,
        hasvideo: 1,
        hasmuisc: 1,
      });
      const data = json.data || {};
      const list = Array.isArray(data.list) ? data.list : [];
      if (!list.length) { state.stopReason = '游标返回空列表'; break; }

      await expandRawList(list, opts.longText);
      const posts = list.map(parsePost).filter(Boolean);
      for (const p of posts) await fetchCommentsFor(p);

      const fresh = posts.filter((p) => !seen.has(p.id));
      fresh.forEach((p) => seen.add(p.id));
      await dbPutAll('posts', posts);
      state.fetched += fresh.length;

      const tail = posts[posts.length - 1];
      // 优先用服务端返回的 since_id 当游标（微博自己的“加载更早”游标）；
      // 只有它没有后退时才退回「本页最小 id − 1」。
      // 用最小 id 而不是末条：置顶/推广等异常排序时末条未必最旧；
      // 减 1 避免边界那条被重复返回后误判为「无新数据」。
      const serverCursor = data.since_id ? String(data.since_id) : '';
      const minId = minIdOf(posts);
      let next = '';
      if (/^\d+$/.test(serverCursor) && /^\d+$/.test(cursor) &&
          BigInt(serverCursor) < BigInt(cursor)) {
        next = serverCursor;
      } else if (minId) {
        next = (BigInt(minId) - 1n).toString();
      }
      updateProgress();
      console.log('[weibo-export] 游标 本页=' + list.length + ' 新增=' + fresh.length +
        ' 累计=' + state.fetched + '/' + (state.total || '?') +
        ' 最旧=' + (tail ? tail.createdAt : '-'));

      if (!fresh.length) {
        if (++stale >= 2) { state.stopReason = '游标未再返回新数据'; break; }
      } else {
        stale = 0;
      }
      if (!next || next === cursor) { state.stopReason = '游标未前进'; break; }
      cursor = next;
      await sleep(opts.delay);
    }
  }

  // 取一批微博里最小的数字 id（BigInt 比较，避免 16 位 id 精度丢失）
  function minIdOf(posts) {
    let min = null;
    for (const p of posts || []) {
      const id = String((p && p.id) || '');
      if (!/^\d+$/.test(id)) continue;
      if (min === null || BigInt(id) < BigInt(min)) min = id;
    }
    return min;
  }

  // 从 beforeDate 往前（更早）按时间窗口抓取，作为翻页失效时的兜底。
  // 每次以一年为窗口，取尽后整体前移，直到覆盖到微博上线时间。
  async function fetchByWindowBackfill(uid, seen, beforeDate) {
    const YEAR = 365 * 24 * 3600;
    const floor = Math.floor(new Date(2009, 7, 1).getTime() / 1000);
    let endtime = Math.floor(beforeDate.getTime() / 1000) - 1;
    let page = 1;
    let lastDate = null;
    let guard = 0;

    while (endtime > floor) {
      if (++guard > 3000) break;
      if (state.total && state.fetched >= state.total) break;
      await waitWhilePaused();
      const starttime = Math.max(floor, endtime - YEAR);
      // hasmuisc 是微博接口的历史拼写；同时带上 hasmusic，兼容两种实现
      const json = await apiGet('/statuses/searchProfile', {
        uid,
        page,
        starttime,
        endtime,
        feature: 0,
        hasori: 1,
        hasret: opts.hasRepost ? 1 : 0,
        haspic: 1,
        hastext: 1,
        hasvideo: 1,
        hasmuisc: 1,
        hasmusic: 1,
      });
      const data = json.data || {};
      const list = Array.isArray(data.list) ? data.list : [];

      if (!list.length) {
        // 该窗口取尽：以本窗口最后一条的日期为新起点，否则整窗前移
        const d = lastDate && parseDate(lastDate);
        endtime = d ? Math.floor(d.getTime() / 1000) - 1 : starttime - 1;
        page = 1;
        lastDate = null;
        continue;
      }

      await expandRawList(list, opts.longText);
      const posts = list.map(parsePost).filter(Boolean);
      for (const p of posts) await fetchCommentsFor(p);

      const fresh = posts.filter((p) => !seen.has(p.id));
      fresh.forEach((p) => seen.add(p.id));
      await dbPutAll('posts', posts);
      state.fetched += fresh.length;

      const tail = posts[posts.length - 1];
      lastDate = tail ? tail.createdAt : lastDate;
      updateProgress();
      console.log('[weibo-export] 窗口补齐 page=' + page + ' 本页=' + list.length +
        ' 新增=' + fresh.length + ' 累计=' + state.fetched + '/' + (state.total || '?'));

      // 本页全是旧数据：该窗口已取尽，前移
      if (!fresh.length) {
        const d = lastDate && parseDate(lastDate);
        endtime = d ? Math.floor(d.getTime() / 1000) - 1 : starttime - 1;
        page = 1;
        lastDate = null;
        continue;
      }
      page++;
      await sleep(opts.delay);
    }
  }
  async function fetchByDate(uid) {
    const start = new Date(opts.startAt);
    const end = new Date(opts.endAt);
    if (isNaN(start.getTime()) || isNaN(end.getTime())) throw new Error('请填写正确的开始/结束日期（yyyy-MM-dd）');
    start.setHours(0, 0, 0, 0);
    end.setHours(23, 59, 0, 0);
    let starttime = start.getTime() / 1000;
    let endtime = end.getTime() / 1000;
    let page = 1;
    let lastDate = null;
    let windowGuard = 0;

    while (true) {
      if (++windowGuard > 1000) break;
      await waitWhilePaused();
      const json = await apiGet('/statuses/searchProfile', {
        uid,
        page,
        starttime,
        endtime,
        hasori: 1,
        hasret: opts.hasRepost ? 1 : 0,
        haspic: 1,
        hastext: 1,
        hasvideo: 1,
        hasmuisc: 1,
      });
      const data = json.data || {};
      const list = Array.isArray(data.list) ? data.list : [];
      if (data.total && !state.total) state.total = data.total;

      await expandRawList(list, opts.longText);
      const posts = list.map(parsePost).filter(Boolean);
      for (const p of posts) await fetchCommentsFor(p);
      await dbPutAll('posts', posts);
      state.fetched += posts.length;
      if (posts.length) lastDate = posts[posts.length - 1].createdAt;
      updateProgress();

      if (posts.length) {
        page++;
        await sleep(opts.delay);
      } else if (lastDate && state.total && state.fetched < state.total) {
        // 该窗口已到尽头但还没抓完，把时间窗口往前滑一段继续
        const d = parseDate(lastDate);
        if (!d) break;
        d.setHours(23, 59, 0, 0);
        endtime = d.getTime() / 1000 - 1;
        page = 1;
        lastDate = null;
        await sleep(opts.delay);
      } else {
        break;
      }
    }
  }

  async function fetchFavorites(uid) {
    let page = 1;
    let guard = 0;
    while (true) {
      if (++guard > 50000) break;
      await waitWhilePaused();
      const json = await apiGet('/favorites/all_fav', { uid, page, with_total: true });
      const list = json.data && json.data.status;
      if (!Array.isArray(list) || !list.length) break;
      await expandRawList(list, opts.longText);
      const favs = list.map(parsePost).filter(Boolean).map((p) => Object.assign({}, p, { favBy: uid }));
      await dbPutAll('favorites', favs);
      state.favCount += favs.length;
      updateProgress();
      page++;
      await sleep(opts.delay);
    }
  }

  async function fetchFollowings(uid) {
    let page = 0;
    let guard = 0;
    while (true) {
      if (++guard > 5000) break;
      await waitWhilePaused();
      const json = await apiGet('/friendships/friends', { uid, page });
      const users = (json.data && json.data.users) || [];
      if (!users.length) break;
      const parsed = users
        .map((u) => ({
          uid: String(u.idstr || u.id || ''),
          name: u.screen_name || '',
          avatar: u.profile_image_url || u.avatar_large || '',
          bio: u.description || '',
          remark: u.remark || '',
          location: u.location || '',
          followers: u.followers_count || 0,
          followings: u.friends_count || 0,
          createdAt: u.created_at || '',
        }))
        .filter((u) => u.uid);
      await dbPutAll('followings', parsed);
      state.followCount += parsed.length;
      updateProgress();
      page++;
      await sleep(opts.delay);
      if (!(json.data && json.data.next_cursor)) break;
    }
  }
  // ==================== 文本与 HTML 渲染（参照 WeiBack 的可打印排版） ====================
  function transText(text, ctx, emojiMap) {
    let t = esc(text || '');
    const urlStruct = (ctx && ctx.urlStruct) || [];
    const linkMap = new Map();
    for (const u of urlStruct) {
      if (u && u.short_url) linkMap.set(u.short_url, u);
    }
    t = t.replace(/https?:\/\/t\.cn\/[^\s<>"']+/g, (m) => {
      const info = linkMap.get(m);
      const url = (info && info.long_url) || m;
      return '<a href="' + esc(url) + '" target="_blank" rel="noopener">' + esc((info && info.url_title) || m) + '</a>';
    });
    t = t.replace(/@([\u4e00-\u9fa5A-Za-z0-9_\-·]+)/g, (m, name) => {
      return '<a href="https://weibo.com/n/' + encodeURIComponent(name) + '" target="_blank" rel="noopener">' + m + '</a>';
    });
    t = t.replace(/#([^#\n]+)#/g, (m) => {
      return '<a href="https://s.weibo.com/weibo?q=' + encodeURIComponent(m) + '" target="_blank" rel="noopener">' + m + '</a>';
    });
    const map = emojiMap || emoticonMap;
    if (map && map.size) {
      t = t.replace(/\[([^\[\]\n]{1,24})\]/g, (m, name) => {
        const url = map.get(m);
        return url ? '<img class="emoji" alt="' + m + '" title="' + m + '" src="' + esc(url) + '" />' : m;
      });
    }
    return t.replace(/\n/g, '<br>');
  }

  async function resolveImg(url, exportOpts) {
    if (exportOpts.picMode !== 'embed' || !win) return url;
    const cache = exportOpts.embedCache;
    if (cache.has(url)) return cache.get(url);
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), exportOpts.imgTimeout || 15000);
    try {
      const res = await fetch(url, { credentials: 'omit', signal: ctrl.signal });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const blob = await res.blob();
      if (blob.size > exportOpts.embedMaxMB * 1024 * 1024) throw new Error('too large');
      const dataUrl = await avatarDataUrl(blob, exportOpts.avatarMaxSize || 160);
      cache.set(url, dataUrl);
      return dataUrl;
    } catch (e) {
      return url; // 失败/超时回退为链接
    } finally {
      clearTimeout(timer);
    }
  }

  // 头像：图床防盗链（需 weibo.com 来源），一律下载内嵌，保证离线/PDF 也能显示
  async function resolveAvatar(url, exportOpts) {
    if (!url) return '';
    const cache = exportOpts.avatarCache || (exportOpts.avatarCache = new Map());
    if (cache.has(url)) return cache.get(url);
    if (!exportOpts.embedAvatar || !win) return url;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), exportOpts.imgTimeout || 15000);
    try {
      const res = await fetch(url, { credentials: 'omit', signal: ctrl.signal });
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const blob = await res.blob();
      if (blob.size > 2 * 1024 * 1024) throw new Error('too large');
      const dataUrl = await avatarDataUrl(blob, exportOpts.avatarMaxSize || 160);
      cache.set(url, dataUrl);
      return dataUrl;
    } catch (e) {
      return url; // 失败回退为原链接
    } finally {
      clearTimeout(timer);
    }
  }

  // 头像压缩：下载后按最大边长缩放（大图转 JPEG q85，白底），避免撑爆 HTML/PDF
  async function avatarDataUrl(blob, maxSide) {
    try {
      const url = URL.createObjectURL(blob);
      try {
        const img = await new Promise((resolve, reject) => {
          const im = new Image();
          im.onload = () => resolve(im);
          im.onerror = () => reject(new Error('img load error'));
          im.src = url;
        });
        const scale = Math.min(1, maxSide / Math.max(img.naturalWidth, img.naturalHeight));
        if (scale >= 1) return blobToDataUrl(blob);
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(img.naturalWidth * scale));
        canvas.height = Math.max(1, Math.round(img.naturalHeight * scale));
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#fff';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        return canvas.toDataURL('image/jpeg', 0.85);
      } finally {
        URL.revokeObjectURL(url);
      }
    } catch (e) {
      return blobToDataUrl(blob); // 压缩失败回退原图
    }
  }

  function blobToDataUrl(blob) {
    return new Promise((resolve, reject) => {
      const fr = new FileReader();
      fr.onload = () => resolve(fr.result);
      fr.onerror = () => reject(fr.error);
      fr.readAsDataURL(blob);
    });
  }

  async function avatarImg(url, cls, exportOpts) {
    if (!url) return '';
    const src = await resolveAvatar(url, exportOpts);
    return '<img' + (cls ? ' class="' + cls + '"' : '') + ' src="' + esc(src) + '" alt="" referrerpolicy="no-referrer" />';
  }

  // 并发执行（embed 模式下载图片提速；失败不影响整体）
  async function mapLimit(items, limit, fn, onProgress) {
    const results = new Array(items.length);
    let idx = 0;
    let done = 0;
    async function worker() {
      while (true) {
        const i = idx++;
        if (i >= items.length) return;
        try {
          results[i] = await fn(items[i], i);
        } catch (e) {
          results[i] = '';
        }
        done++;
        if (onProgress) onProgress(done);
      }
    }
    const workers = [];
    for (let k = 0; k < Math.min(limit, items.length); k++) workers.push(worker());
    await Promise.all(workers);
    return results;
  }

  async function renderComment(c, exportOpts) {
    let head = '<div class="c-head">';
    head += await avatarImg(c.avatar, 'c-avatar', exportOpts);
    head += '<span class="c-name">' + esc(c.userName || '') + '</span>';
    head += '<span class="c-meta">' + formatDate(c.createdAt) + (c.floor ? ' ' + c.floor + ' 楼' : '') + '</span></div>';
    let body = '<div class="c-text">' + transText(c.text, c) + '</div>';
    if (c.img) body += '<div class="c-img"><img src="' + esc(c.img) + '" referrerpolicy="no-referrer" /></div>';
    return '<div class="comment">' + head + body + '</div>';
  }

  async function renderRetweet(r, exportOpts) {
    let html = '<div class="retweet">';
    html += '<div class="c-head">' +
      (await avatarImg(r.avatar, 'c-avatar', exportOpts)) +
      '<span class="name">' + esc(r.userName || '') + '</span>' +
      '<span class="c-meta">' + formatDate(r.createdAt) + '</span></div>';
    if (r.text) html += '<div class="text">' + transText(r.text, r) + '</div>';
    if (r.imgs && r.imgs.length) {
      const cells = [];
      for (const info of r.imgs) {
        const url = pickImageUrl(info, exportOpts.picLevel);
        if (!url) continue;
        const src = await resolveImg(url, exportOpts);
        cells.push('<div class="pic"><img src="' + esc(src) + '" alt="" loading="lazy" referrerpolicy="no-referrer" /></div>');
      }
      if (cells.length) html += '<div class="imgs">' + cells.join('') + '</div>';
    }
    html += '</div>';
    return html;
  }

  async function renderPost(post, exportOpts) {
    const parts = [];
    parts.push('<article class="card">');
    parts.push('<header class="card-head">');
    parts.push(await avatarImg(post.avatar, 'avatar', exportOpts));
    parts.push('<div class="who"><span class="name">' + esc(post.userName || '') + '</span>');
    let meta = formatDate(post.createdAt);
    if (post.regionName) meta += ' · ' + esc(post.regionName);
    if (post.source) meta += ' · ' + esc(post.source);
    parts.push('<span class="meta">' + meta + '</span></div>');
    if (post.userId && post.mblogid) {
      parts.push('<a class="origin" href="https://weibo.com/' + encodeURIComponent(post.userId) + '/' + post.mblogid + '" target="_blank" rel="noopener">原帖 ↗</a>');
    }
    parts.push('</header>');
    if (post.text) parts.push('<div class="text">' + transText(post.text, post) + '</div>');
    if (post.card && post.card.link) {
      parts.push('<a class="linkcard" href="' + esc(post.card.link) + '" target="_blank" rel="noopener">' +
        (post.card.img ? '<img src="' + esc(post.card.img) + '" referrerpolicy="no-referrer" />' : '') +
        '<span><b>' + esc(post.card.title || '') + '</b><small>' + esc(post.card.desc || '') + '</small></span></a>');
    }
    if (post.imgs && post.imgs.length) {
      const cells = [];
      for (const info of post.imgs) {
        const url = pickImageUrl(info, exportOpts.picLevel);
        if (!url) continue;
        const src = await resolveImg(url, exportOpts);
        cells.push('<div class="pic"><img src="' + esc(src) + '" alt="" loading="lazy" referrerpolicy="no-referrer" /></div>');
      }
      if (cells.length) parts.push('<div class="imgs">' + cells.join('') + '</div>');
    }
    if (post.retweet) parts.push(await renderRetweet(post.retweet, exportOpts));
    if (post.comments && post.comments.length) {
      const commentHtml = (await Promise.all(post.comments.map((c) => renderComment(c, exportOpts)))).join('');
      parts.push('<div class="comments">' + commentHtml + '</div>');
    }
    parts.push('<footer class="stats">' + post.repostsCount + ' 转发 · ' + post.commentsCount + ' 评论 · ' + post.likesCount + ' 点赞</footer>');
    parts.push('</article>');
    return parts.join('');
  }
  const EXPORT_CSS = '' +
    ':root{color-scheme:light}*{box-sizing:border-box}' +
    'body{margin:0;background:#fff;color:#222;font:14px/1.7 -apple-system,"PingFang SC","Microsoft YaHei","Helvetica Neue",sans-serif}' +
    '.cover{text-align:center;padding:40px 20px 30px;border-bottom:1px solid #eee;margin-bottom:24px}' +
    '.cover h1{font-size:30px;margin:0 0 16px;color:#e6162d}' +
    '.cover-user{display:flex;align-items:center;justify-content:center;gap:10px;margin-bottom:8px}' +
    '.cover-user img{width:56px;height:56px;border-radius:50%}' +
    '.cover-name{font-size:20px;font-weight:700}.cover-uid{color:#999}' +
    '.cover-stat{color:#666;margin:4px 0}' +
    '.cover-note{margin-top:18px;font-size:12px;color:#999;background:#fafafa;border:1px dashed #ddd;border-radius:8px;padding:8px 12px;display:inline-block}' +
    '.card{max-width:760px;margin:0 auto 18px;padding:16px 18px;border:1px solid #e8e8e8;border-radius:12px;page-break-inside:avoid;break-inside:avoid}' +
    '.card-head{display:flex;align-items:flex-start;gap:10px;margin-bottom:8px}' +
    '.avatar{width:40px;height:40px;border-radius:50%;flex:none}' +
    '.who{flex:1;min-width:0}.name{font-weight:700;display:block}' +
    '.meta{color:#999;font-size:12px}' +
    '.origin{color:#e6162d;text-decoration:none;font-size:12px;flex:none}' +
    '.text{word-break:break-word}.text a{color:#e6162d;text-decoration:none}.text a:hover{text-decoration:underline}' +
    '.emoji{height:1.2em;vertical-align:-0.25em}' +
    '.imgs{display:flex;flex-wrap:wrap;gap:8px;margin-top:10px}' +
    '.pic{flex:1 1 160px;max-width:240px}' +
    '.pic img{width:100%;border-radius:8px;display:block}' +
    '.linkcard{display:flex;gap:10px;align-items:center;margin-top:10px;border:1px solid #eee;border-radius:8px;padding:8px 10px;text-decoration:none;color:inherit}' +
    '.linkcard img{width:56px;height:56px;object-fit:cover;border-radius:6px}' +
    '.linkcard b{display:block;font-size:13px}.linkcard small{color:#999;font-size:12px;word-break:break-all}' +
    '.retweet{margin-top:10px;border-left:3px solid #eee;padding:8px 12px;background:#fafafa;border-radius:8px}' +
    '.retweet .name{font-size:13px}.retweet .text{font-size:13px;color:#444}' +
    '.comments{margin-top:10px;border-top:1px dashed #eee;padding-top:8px}' +
    '.comment{padding:6px 0;border-bottom:1px solid #f4f4f4;font-size:13px}' +
    '.comment:last-child{border-bottom:none}' +
    '.c-head{display:flex;align-items:center;gap:6px;margin-bottom:2px}' +
    '.c-avatar{width:20px;height:20px;border-radius:50%}.c-name{font-weight:600;font-size:12px;color:#e6162d}.c-meta{color:#bbb;font-size:11px}' +
    '.c-img img{max-width:160px;border-radius:6px;margin-top:4px}' +
    '.stats{margin-top:10px;color:#999;font-size:12px;border-top:1px solid #f4f4f4;padding-top:8px}' +
    '@media print{body{font-size:12px}.card{page-break-inside:avoid;box-shadow:none}.cover{page-break-after:avoid}a{color:inherit;text-decoration:none}}' +
    '@page{size:A4;margin:12mm}';

  async function buildHtmlParts(user, posts, favorites, followings, exportOpts) {
    if (!exportOpts.skipEmoticon) await ensureEmoticon();
    const sorted = (posts || []).slice().sort((a, b) => {
      const da = parseDate(a.createdAt);
      const db = parseDate(b.createdAt);
      const diff = (da ? da.getTime() : 0) - (db ? db.getTime() : 0);
      return exportOpts.sort === 'desc' ? -diff : diff;
    });
    const first = sorted.length ? formatDate(sorted[0].createdAt, 'YYYY-MM-DD') : '';
    const last = sorted.length ? formatDate(sorted[sorted.length - 1].createdAt, 'YYYY-MM-DD') : '';
    const label = exportOpts.partLabel ? '（' + exportOpts.partLabel + '）' : '';
    const parts = [];
    parts.push('<!DOCTYPE html><html lang="zh-CN"><head><meta charset="utf-8">' +
      '<meta name="viewport" content="width=device-width, initial-scale=1">' +
      '<title>微博存档' + label + (user && user.name ? ' · ' + esc(user.name) : '') + '</title>' +
      '<style>' + EXPORT_CSS + '</style></head><body>');
    parts.push('<section class="cover"><h1>微博存档' + label + '</h1>');
    if (user) {
      parts.push('<div class="cover-user">' +
        (await avatarImg(user.avatar, '', exportOpts)) +
        '<span class="cover-name">' + esc(user.name) + '</span>' +
        '<span class="cover-uid">uid: ' + esc(user.uid) + '</span></div>');
    }
    parts.push('<div class="cover-stat">共 ' + sorted.length + ' 条微博' + (first && last ? ' · ' + first + ' ~ ' + last : '') + '</div>');
    if (favorites && favorites.length) parts.push('<div class="cover-stat">收藏 ' + favorites.length + ' 条</div>');
    if (followings && followings.length) parts.push('<div class="cover-stat">关注 ' + followings.length + ' 人</div>');
    parts.push('<div class="cover-stat">导出时间：' + formatDate(new Date().toISOString()) + '</div>');
    parts.push('<div class="cover-note">本文件由“微博备份导出”油猴脚本生成。用浏览器（Chrome/Edge/Firefox）打开后按 Ctrl+P，选择“另存为 PDF”即可导出为 PDF。</div>');
    parts.push('</section>');
    const rendered = await mapLimit(sorted, 3, (post) => renderPost(post, exportOpts), (done) => {
      if (done % 10 === 0 || done === sorted.length) setStatus('生成 HTML：' + done + '/' + sorted.length);
    });
    // 逐条追加，避免 join 出超过浏览器字符串上限的超长字符串
    for (const r of rendered) parts.push(r);
    parts.push('</body></html>');
    return parts;
  }

  async function buildHtml(user, posts, favorites, followings, exportOpts) {
    const parts = await buildHtmlParts(user, posts, favorites, followings, exportOpts);
    return parts.join('');
  }
  // ==================== 导出 ====================
  function download(filename, blob) {
    if (!doc) return;
    const url = URL.createObjectURL(blob);
    const a = doc.createElement('a');
    a.href = url;
    a.download = filename;
    doc.body.appendChild(a);
    a.click();
    setTimeout(() => {
      URL.revokeObjectURL(url);
      if (a.parentNode) a.parentNode.removeChild(a);
    }, 3000);
  }

  function stamp() {
    return formatDate(new Date().toISOString(), 'YYYYMMDD-HHmm');
  }

  function uniqueImgs(posts, favorites) {
    const set = new Set();
    const collect = (list) => {
      for (const p of list || []) {
        for (const info of (p.imgs || [])) {
          const url = pickImageUrl(info, 3);
          if (url) set.add(url);
        }
        if (p.retweet) {
          for (const info of (p.retweet.imgs || [])) {
            const url = pickImageUrl(info, 3);
            if (url) set.add(url);
          }
        }
        for (const c of (p.comments || [])) if (c.img) set.add(c.img);
      }
    };
    collect(posts);
    collect(favorites);
    return Array.from(set);
  }

  async function exportJSON() {
    const user = state.user || (await metaGet('user')) || null;
    const posts = await dbGetAll('posts');
    const favorites = await dbGetAll('favorites');
    const followings = await dbGetAll('followings');
    const name = (user && user.name) || 'weibo';
    const data = { exportedAt: new Date().toISOString(), user, posts, favorites, followings };
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    download('weibo-' + name + '-' + stamp() + '.json', blob);
    setStatus('已导出 JSON：' + posts.length + ' 条微博');
  }

  async function exportHTML() {
    try {
      const user = state.user || (await metaGet('user')) || null;
      const posts = await dbGetAll('posts');
      const favorites = await dbGetAll('favorites');
      const followings = await dbGetAll('followings');
      if (!posts.length) {
        setStatus('本地缓存为空，请先点“开始导出”抓取微博');
        return;
      }
      // 内嵌模式图片多，自动按条数分文件，避免单个 HTML 超过浏览器字符串上限（Invalid string length）
      const chunkSize = opts.picMode === 'embed' ? 120 : 2000;
      const sorted = posts.slice().sort((a, b) => {
        const da = parseDate(a.createdAt);
        const db = parseDate(b.createdAt);
        const diff = (da ? da.getTime() : 0) - (db ? db.getTime() : 0);
        return opts.sort === 'desc' ? -diff : diff;
      });
      const groups = [];
      for (let i = 0; i < sorted.length; i += chunkSize) groups.push(sorted.slice(i, i + chunkSize));
      const name = (user && user.name) || 'weibo';
      for (let g = 0; g < groups.length; g++) {
        setStatus('生成 HTML 第 ' + (g + 1) + '/' + groups.length + ' 组（' + groups[g].length + ' 条）…');
        const exportOpts = {
          picLevel: opts.picLevel,
          picMode: opts.picMode,
          sort: opts.sort,
          embedMaxMB: opts.embedMaxMB,
          imgTimeout: 15000,
          embedAvatar: opts.embedAvatar !== false,
          embedCache: new Map(),
          avatarCache: new Map(),
          skipEmoticon: g > 0, // 表情配置只取一次
          partLabel: groups.length > 1 ? '第 ' + (g + 1) + '/' + groups.length + ' 部分' : '',
        };
        const parts = await buildHtmlParts(user, groups[g], g === 0 ? favorites : [], g === 0 ? followings : [], exportOpts);
        setStatus('正在打包第 ' + (g + 1) + ' 个文件…');
        const blob = new Blob(parts, { type: 'text/html;charset=utf-8' });
        const suffix = groups.length > 1 ? '-part-' + pad(g + 1) : '';
        download('weibo-' + name + '-' + stamp() + suffix + '.html', blob);
        if (groups.length > 1) await sleep(300);
      }
      setStatus('已导出 ' + groups.length + ' 个 HTML 文件（' + sorted.length + ' 条），打开后 Ctrl+P 可存 PDF');
    } catch (e) {
      setStatus('导出 HTML 失败：' + (e && e.message ? e.message : String(e)));
    }
  }

  async function exportImagesCSV() {
    const posts = await dbGetAll('posts');
    const favorites = await dbGetAll('favorites');
    const imgs = uniqueImgs(posts, favorites);
    const user = state.user || (await metaGet('user')) || null;
    const name = (user && user.name) || 'weibo';
    const blob = new Blob([imgs.join('\n')], { type: 'text/csv' });
    download('imgs-' + name + '-' + stamp() + '.csv', blob);
    setStatus('已导出图片链接 CSV：' + imgs.length + ' 张');
  }
  async function exportPDF() {
    // 同步打开空白窗口以保留用户手势，避免被弹窗拦截
    const w = win && win.open ? win.open('', '_blank') : null;
    if (!w) {
      setStatus('无法打开打印窗口：请点击地址栏弹窗拦截图标，允许 weibo.com 弹出窗口后重试');
      return;
    }
    setStatus('正在生成可打印页面…');
    try {
      const user = state.user || (await metaGet('user')) || null;
      const posts = await dbGetAll('posts');
      const favorites = await dbGetAll('favorites');
      const followings = await dbGetAll('followings');
      if (!posts.length) {
        setStatus('本地缓存为空，请先点“开始导出”抓取微博');
        w.close();
        return;
      }
      const exportOpts = {
        picLevel: opts.picLevel,
        picMode: opts.picMode,
        sort: opts.sort,
        embedMaxMB: opts.embedMaxMB,
        imgTimeout: 15000,
        embedCache: new Map(),
        skipEmoticon: false,
      };
      const parts = await buildHtmlParts(user, posts, favorites, followings, exportOpts);
      // 预估体积，过大时提示改用分文件导出，避免打印页面卡死
      let totalLen = 0;
      for (const p of parts) {
        totalLen += p.length;
        if (totalLen > 157286400) break;
      }
      if (totalLen > 157286400) {
        setStatus('打印内容过大（约 ' + Math.round(totalLen / 1048576) + 'MB），请改用“导出 HTML”（自动分文件）或“链接”图片模式');
        w.close();
        return;
      }
      w.document.open();
      for (const p of parts) w.document.write(p);
      w.document.close();
      w.focus();
      setStatus('打印窗口已打开：目标打印机选“另存为 PDF”，再点保存即可');
      await sleep(1500);
      try { w.print(); } catch (e) { /* 用户可手动 Ctrl+P */ }
    } catch (e) {
      setStatus('导出失败：' + (e && e.message ? e.message : String(e)));
      try { w.close(); } catch (e2) { /* ignore */ }
    }
  }
  // ==================== 主流程 ====================
  function detectUid() {
    try {
      if (win && win.$CONFIG && /^\d+$/.test(String(win.$CONFIG.oid))) return String(win.$CONFIG.oid);
    } catch (e) { /* ignore */ }
    const m = win && win.location ? win.location.pathname.match(/\/u\/(\d+)/) : null;
    if (m) return m[1];
    try {
      const ds = doc && doc.documentElement ? doc.documentElement.dataset : null;
      if (ds) {
        const cand = ds.wbxOid || ds.wbxUid || '';
        if (/^\d+$/.test(cand)) return cand;
      }
    } catch (e) { /* ignore */ }
    try {
      const s = win && win.__INITIAL_STATE__ ? win.__INITIAL_STATE__ : {};
      const cand = (s.loginInfo && s.loginInfo.uid) || (s.userInfo && s.userInfo.idstr) || '';
      if (/^\d+$/.test(String(cand))) return String(cand);
    } catch (e) { /* ignore */ }
    return '';
  }

  async function resolveUid(input) {
    const t = String(input || '').trim();
    if (/^\d+$/.test(t)) return t;
    // 填了昵称：先走搜索接口
    if (t) {
      try {
        const json = await apiGet('/side/search', { q: t }, { retries: 2, retryDelay: 2000, timeout: 10000 });
        const users = (json.data && json.data.users) || [];
        if (users.length && users[0].id) return String(users[0].id);
      } catch (e) { /* 继续兜底 */ }
    }
    // 没填 / 搜索失败：优先用当前登录账号自己的 uid（页面上的 $CONFIG.oid 可能是别人）
    try {
      const json = await apiGet('/profile/info', {}, { retries: 1, retryDelay: 2000 });
      const u = (json.data && json.data.user) || {};
      if (u.idstr || u.id) return String(u.idstr || u.id);
    } catch (e) { /* ignore */ }
    return detectUid();
  }

  async function run() {
    if (state.running) return;
    const uid = await resolveUid(opts.uid);
    if (!uid) {
      setStatus('无法确定 uid：请确认已登录 weibo.com，或手填数字 uid 后重试；也可点“自检”查看接口状态');
      return;
    }
    opts.uid = uid;
    saveOptions();
    state.running = true;
    state.stopReq = false;
    state.paused = false;
    state.stopReason = '';
    setRunningUI(true);
    try {
      let resumed = false;
      if (opts.restore) resumed = await loadResume(uid);
      if (!resumed) {
        // 全新抓取：重置进度并清空旧缓存，避免新旧数据混杂
        state.sinceId = '';
        state.page = 1;
        state.fetched = 0;
        state.total = 0;
        state.phaseFlags = {};
        await dbClear('posts');
        await dbClear('favorites');
        await dbClear('followings');
        await metaSet('resume-' + uid, null);
      }
      if (!state.user || state.user.uid !== uid) {
        try {
          const json = await apiGet('/profile/info', { uid });
          const profile = parseProfile(json.data && json.data.user);
          if (profile) {
            state.user = profile;
            await metaSet('user', profile);
          }
        } catch (e) { /* 拿不到资料也能继续 */ }
      }

      // 上次可能「完成」了但条数远少于总数（翻页被截断），这种情况要重跑
      const cachedCount = await dbCount('posts');
      if (state.total && cachedCount < state.total - 5) state.phaseFlags.weibo = false;

      if (!state.phaseFlags.weibo || cachedCount === 0) {
        state.phaseFlags.weibo = false;
        setStatus('正在抓取微博…');
        if (opts.isFetchAll) await fetchAllBySinceId(uid);
        else await fetchByDate(uid);
        state.phaseFlags.weibo = true;
        await saveResume(uid);
      }
      if (opts.hasFavorites && !state.phaseFlags.favorites) {
        setStatus('正在抓取收藏…');
        await fetchFavorites(uid);
        state.phaseFlags.favorites = true;
        await saveResume(uid);
      }
      if (opts.hasFollowings && !state.phaseFlags.followings) {
        setStatus('正在抓取关注列表…');
        await fetchFollowings(uid);
        state.phaseFlags.followings = true;
        await saveResume(uid);
      }
      const count = await dbCount('posts');
      const complete = !state.total || count >= state.total - 5;
      setStatus('抓取完成！共 ' + count + ' 条微博' +
        (!complete ? '（接口显示总数 ' + state.total + '）' : '') +
        (!complete && state.stopReason ? '｜结束原因：' + state.stopReason : '') +
        '，可点击“导出 JSON / 导出 HTML”');
    } catch (e) {
      if (e && e.message === '已停止') {
        setStatus('已停止，进度已保存，可稍后点“继续导出”');
      } else {
        setStatus('已中断：' + (e && e.message ? e.message : String(e)) + '（可稍后点“继续导出”断点续传）');
      }
    } finally {
      state.running = false;
      setRunningUI(false);
    }
  }

  function togglePause() {
    if (!state.running) return;
    state.paused = !state.paused;
    const btn = $wbx && $wbx.pauseBtn;
    if (btn) btn.textContent = state.paused ? '继续' : '暂停';
    if (state.paused) setStatus('已暂停，点击“继续”恢复抓取');
    else setStatus('继续抓取…');
  }

  function stop() {
    state.stopReq = true;
    state.paused = false;
    setStatus('正在停止…');
  }

  async function clearData() {
    if (state.running) return;
    if (!win || !win.confirm('确定清空本地缓存的微博数据吗？')) return;
    await dbClear('posts');
    await dbClear('favorites');
    await dbClear('followings');
    const uid = opts.uid;
    if (uid) await metaSet('resume-' + uid, null);
    state.fetched = 0;
    state.favCount = 0;
    state.followCount = 0;
    state.sinceId = '';
    state.page = 1;
    state.total = 0;
    state.phaseFlags = {};
    updateProgress();
    setStatus('已清空本地缓存');
  }

  // 自检：把 uid 来源、登录会话、首屏接口的真实返回打印到面板，便于定位失效原因
  async function selfCheck() {
    const out = [];
    const cfg = (win && win.$CONFIG) || {};
    const st = (win && win.__INITIAL_STATE__) || {};
    const ds = (doc && doc.documentElement && doc.documentElement.dataset) || {};
    out.push('环境：' + (win && win.location ? win.location.host : '?') + ' · v' + SCRIPT_VERSION);
    out.push('uid 来源：$CONFIG=' + (cfg.oid || '-') +
      ' | URL=' + (detectUid() || '-') +
      ' | dataset=' + (ds.wbxOid || ds.wbxUid || '-') +
      ' | loginInfo=' + ((st.loginInfo && st.loginInfo.uid) || '-'));
    try {
      const j = await apiGet('/profile/info', {}, { retries: 0 });
      const u = (j.data && j.data.user) || {};
      out.push('登录会话：ok uid=' + (u.idstr || u.id || '?') + ' 昵称=' + (u.screen_name || '?'));
    } catch (e) {
      out.push('登录会话：失败 — ' + e.message);
    }
    const uid = String(opts.uid || '').trim().replace(/\D/g, '') || detectUid();
    if (!uid) {
      out.push('微博接口：跳过（没有可用 uid）');
    } else {
      try {
        const j = await apiGet('/statuses/mymblog', { uid, page: 1, feature: 0 }, { retries: 0 });
        const d = j.data || {};
        const list = Array.isArray(d.list) ? d.list : [];
        out.push('mymblog：ok 本页=' + list.length + ' total=' + (d.total || '?') +
          ' since_id=' + (d.since_id || '-'));
        if (list[0]) out.push('首条字段：' + Object.keys(list[0]).slice(0, 12).join(', '));
      } catch (e) {
        out.push('mymblog：失败 — ' + e.message);
      }
      // 诊断两条兜底路径是否可用
      try {
        const now = Math.floor(Date.now() / 1000);
        const j = await apiGet('/statuses/searchProfile', {
          uid, page: 1, starttime: now - 30 * 86400, endtime: now,
          feature: 0, hasori: 1, hasret: 1, haspic: 1, hastext: 1, hasvideo: 1,
        }, { retries: 0 });
        const list = (j.data && j.data.list) || [];
        out.push('searchProfile（近 30 天）：ok 本页=' + list.length);
      } catch (e) {
        out.push('searchProfile：失败 — ' + e.message);
      }
      try {
        const cached = await dbGetAll('posts');
        if (!cached.length) {
          out.push('游标：跳过（本地暂无缓存，无法取最旧 id）');
        } else {
          let oldest = cached[0];
          for (const p of cached) {
            try {
              if (BigInt(p.id) < BigInt(oldest.id)) oldest = p;
            } catch (e) { /* id 非数字时忽略 */ }
          }
          const j = await apiGet('/statuses/mymblog', {
            uid, page: 1, feature: 0, since_id: String(oldest.id),
          }, { retries: 0 });
          const list = (j.data && j.data.list) || [];
          out.push('游标（since_id=' + String(oldest.id).slice(-6) + '…）：ok 本页=' + list.length);
        }
      } catch (e) {
        out.push('游标：失败 — ' + e.message);
      }

      // 覆盖度：按年份统计已缓存条数，一眼看出缺口在早年在近期
      try {
        const cached = await dbGetAll('posts');
        if (cached.length) {
          const byYear = {};
          let minD = null, maxD = null;
          for (const p of cached) {
            const d = parseDate(p.createdAt);
            if (!d) continue;
            const y = d.getFullYear();
            byYear[y] = (byYear[y] || 0) + 1;
            if (!minD || d < minD) minD = d;
            if (!maxD || d > maxD) maxD = d;
          }
          out.push('缓存 ' + cached.length + ' 条：' +
            (minD ? formatDate(minD, 'YYYY-MM-DD') : '?') + ' ~ ' +
            (maxD ? formatDate(maxD, 'YYYY-MM-DD') : '?'));
          out.push('按年：' + Object.keys(byYear).sort()
            .map((y) => y + ':' + byYear[y]).join(' '));
        }
      } catch (e) {
        out.push('覆盖度统计失败 — ' + e.message);
      }
    }
    setStatus(out.join('\n'));
    console.log('[weibo-export] 自检结果', out);
  }

  // ==================== 界面 ====================
  function setStatus(text) {
    if (!doc) return;
    const el = $wbx && $wbx.status;
    if (el) el.innerHTML = esc(text).replace(/\n/g, '<br>');
  }

  function updateProgress() {
    if (!doc || !$wbx) return;
    const total = state.total || 0;
    const done = state.fetched + state.favCount;
    const pct = total ? Math.min(100, Math.round((state.fetched / total) * 100)) : 0;
    if ($wbx.bar) $wbx.bar.style.width = pct + '%';
    if ($wbx.count) {
      $wbx.count.textContent = '微博 ' + state.fetched + (total ? '/' + total : '') +
        ' · 收藏 ' + state.favCount + ' · 关注 ' + state.followCount;
    }
    if ($wbx.done) $wbx.done.textContent = '已缓存 ' + done + ' 条';
  }

  function setRunningUI(running) {
    if (!doc || !$wbx) return;
    $wbx.startBtn.disabled = running;
    $wbx.pauseBtn.disabled = !running;
    $wbx.stopBtn.disabled = !running;
  }

  function collectOptionsFromUI() {
    if (!$wbx) return;
    opts.uid = $wbx.uidInput.value.trim();
    opts.delay = Math.max(200, parseInt($wbx.delayInput.value, 10) || 800);
    opts.isFetchAll = $wbx.rangeSelect.value === 'all';
    opts.startAt = $wbx.startInput.value;
    opts.endAt = $wbx.endInput.value;
    opts.hasRepost = $wbx.repostCb.checked;
    opts.longText = $wbx.longCb.checked;
    opts.hasComment = $wbx.commentCb.checked;
    opts.commentCount = parseInt($wbx.commentCountSelect.value, 10) || 5;
    opts.hasFavorites = $wbx.favCb.checked;
    opts.hasFollowings = $wbx.followCb.checked;
    opts.picLevel = parseInt($wbx.picLevelSelect.value, 10) || 3;
    opts.picMode = $wbx.picModeSelect.value;
    opts.sort = $wbx.sortSelect.value;
    opts.restore = $wbx.restoreCb.checked;
    opts.embedAvatar = $wbx.avatarCb ? $wbx.avatarCb.checked : opts.embedAvatar;
  }

  function renderPanel() {
    if (!doc || $wbx) return;
    const prevPanel = doc.getElementById('wbx-panel');
    if (prevPanel) prevPanel.remove(); // 多实例（扩展+油猴同时装）时避免新旧面板叠在一起
    const css = '' +
      '#wbx-panel{position:fixed;top:80px;right:20px;width:340px;max-height:82vh;overflow:auto;background:#fff;' +
      'border:1px solid #e0e0e0;border-radius:12px;box-shadow:0 6px 24px rgba(0,0,0,.16);z-index:2147483647;' +
      'font:13px/1.7 "PingFang SC","Microsoft YaHei",sans-serif;color:#222}' +
      '#wbx-panel .wbx-head{display:flex;align-items:center;gap:8px;padding:10px 14px;cursor:pointer;' +
      'border-bottom:1px solid #f0f0f0;font-weight:700;color:#e6162d}' +
      '#wbx-panel .wbx-body{padding:12px 14px}' +
      '#wbx-panel .wbx-row{margin:8px 0;display:flex;align-items:center;gap:8px;flex-wrap:wrap}' +
      '#wbx-panel label{display:flex;align-items:center;gap:4px;white-space:nowrap}' +
      '#wbx-panel input[type=text],#wbx-panel input[type=number],#wbx-panel input[type=date],#wbx-panel select{' +
      'border:1px solid #d0d0d0;border-radius:6px;padding:3px 6px;font-size:12px}' +
      '#wbx-panel input[type=text]{width:180px}' +
      '#wbx-panel input[type=number]{width:64px}' +
      '#wbx-panel .wbx-btn{border:0;border-radius:8px;padding:6px 12px;font-size:12px;cursor:pointer;color:#fff}' +
      '#wbx-panel .wbx-btn:disabled{opacity:.45;cursor:not-allowed}' +
      '#wbx-panel .wbx-btn.primary{background:#e6162d}' +
      '#wbx-panel .wbx-btn.gray{background:#888}' +
      '#wbx-panel .wbx-btn.blue{background:#3b82f6}' +
      '#wbx-panel .wbx-btn.green{background:#16a34a}' +
      '#wbx-panel .wbx-bar{height:8px;background:#f0f0f0;border-radius:4px;overflow:hidden;margin:8px 0}' +
      '#wbx-panel .wbx-bar>div{height:100%;width:0;background:#e6162d;transition:width .3s}' +
      '#wbx-panel .wbx-status{color:#666;font-size:12px;min-height:20px;word-break:break-all}' +
      '#wbx-panel .wbx-count{color:#999;font-size:11px}' +
      '#wbx-panel .wbx-hint{color:#aaa;font-size:11px;line-height:1.5;margin-top:8px;border-top:1px dashed #eee;padding-top:8px}';
    const style = doc.createElement('style');
    style.textContent = css;
    doc.head.appendChild(style);

    const panel = doc.createElement('div');
    panel.id = 'wbx-panel';
    const uidVal = opts.uid || detectUid();
    const rangeHtml = '<select id="wbx-range">' +
      '<option value="all"' + (opts.isFetchAll ? ' selected' : '') + '>全部微博</option>' +
      '<option value="range"' + (!opts.isFetchAll ? ' selected' : '') + '>时间范围</option></select>' +
      '<input type="date" id="wbx-start-date" value="' + esc(opts.startAt) + '" /> ~ ' +
      '<input type="date" id="wbx-end-date" value="' + esc(opts.endAt) + '" />';
    panel.innerHTML =
      '<div class="wbx-head"><span>📦 微博备份导出 v' + SCRIPT_VERSION + '</span><span style="margin-left:auto;color:#999;font-weight:400" id="wbx-min">收起 ▲</span></div>' +
      '<div class="wbx-body" id="wbx-body">' +
      '<div class="wbx-row"><label>uid：<input type="text" id="wbx-uid" value="' + esc(uidVal) + '" placeholder="数字id或昵称" /></label></div>' +
      '<div class="wbx-row"><label>范围：</label>' + rangeHtml + '</div>' +
      '<div class="wbx-row">' +
      '<label><input type="checkbox" id="wbx-repost"' + (opts.hasRepost ? ' checked' : '') + ' />含转发</label>' +
      '<label><input type="checkbox" id="wbx-long"' + (opts.longText ? ' checked' : '') + ' />长文</label>' +
      '<label><input type="checkbox" id="wbx-comment"' + (opts.hasComment ? ' checked' : '') + ' />评论</label>' +
      '<select id="wbx-comment-count"><option value="5"' + (opts.commentCount === 5 ? ' selected' : '') + '>5条</option>' +
      '<option value="10"' + (opts.commentCount === 10 ? ' selected' : '') + '>10条</option>' +
      '<option value="20"' + (opts.commentCount === 20 ? ' selected' : '') + '>20条</option></select>' +
      '</div>' +
      '<div class="wbx-row">' +
      '<label><input type="checkbox" id="wbx-fav"' + (opts.hasFavorites ? ' checked' : '') + ' />收藏</label>' +
      '<label><input type="checkbox" id="wbx-follow"' + (opts.hasFollowings ? ' checked' : '') + ' />关注列表</label>' +
      '<label><input type="checkbox" id="wbx-restore"' + (opts.restore ? ' checked' : '') + ' />断点续传</label>' +
      '</div>' +
      '<div class="wbx-row"><label>图质：<select id="wbx-piclevel">' +
      '<option value="1"' + (opts.picLevel === 1 ? ' selected' : '') + '>小</option>' +
      '<option value="2"' + (opts.picLevel === 2 ? ' selected' : '') + '>中</option>' +
      '<option value="3"' + (opts.picLevel === 3 ? ' selected' : '') + '>大/原图</option></select></label>' +
      '<label>图片：<select id="wbx-picmode">' +
      '<option value="link"' + (opts.picMode === 'link' ? ' selected' : '') + '>链接</option>' +
      '<option value="embed"' + (opts.picMode === 'embed' ? ' selected' : '') + '>内嵌离线</option></select></label>' +
      '<label>排序：<select id="wbx-sort">' +
      '<option value="asc"' + (opts.sort === 'asc' ? ' selected' : '') + '>旧→新</option>' +
      '<option value="desc"' + (opts.sort === 'desc' ? ' selected' : '') + '>新→旧</option></select></label>' +
      '<label><input type="checkbox" id="wbx-avatar"' + (opts.embedAvatar !== false ? ' checked' : '') + ' />头像内嵌</label>' +
      '</div>' +
      '<div class="wbx-row"><label>间隔：<input type="number" id="wbx-delay" value="' + opts.delay + '" min="200" />ms</label></div>' +
      '<div class="wbx-row">' +
      '<button class="wbx-btn primary" id="wbx-start">开始导出</button>' +
      '<button class="wbx-btn gray" id="wbx-pause" disabled>暂停</button>' +
      '<button class="wbx-btn gray" id="wbx-stop" disabled>停止</button>' +
      '</div>' +
      '<div class="wbx-row">' +
      '<button class="wbx-btn green" id="wbx-export-json">导出 JSON</button>' +
      '<button class="wbx-btn blue" id="wbx-export-html">导出 HTML</button>' +
      '<button class="wbx-btn blue" id="wbx-export-pdf">打印 PDF</button>' +
      '<button class="wbx-btn gray" id="wbx-export-imgs">图片CSV</button>' +
      '<button class="wbx-btn gray" id="wbx-clear">清空</button>' +
      '<button class="wbx-btn gray" id="wbx-check">自检</button>' +
      '</div>' +
      '<div class="wbx-bar"><div id="wbx-bar-inner"></div></div>' +
      '<div class="wbx-count" id="wbx-count"></div>' +
      '<div class="wbx-status" id="wbx-status"></div>' +
      '<div class="wbx-hint">数据实时存本地 IndexedDB，可随时重新导出；导出 HTML 后用浏览器打开，Ctrl+P 即可存为 PDF。修改抓取选项前建议先“清空”。</div>' +
      '</div>';
    doc.body.appendChild(panel);

    $wbx = {
      body: panel.querySelector('#wbx-body'),
      min: panel.querySelector('#wbx-min'),
      uidInput: panel.querySelector('#wbx-uid'),
      rangeSelect: panel.querySelector('#wbx-range'),
      startInput: panel.querySelector('#wbx-start-date'),
      endInput: panel.querySelector('#wbx-end-date'),
      repostCb: panel.querySelector('#wbx-repost'),
      longCb: panel.querySelector('#wbx-long'),
      commentCb: panel.querySelector('#wbx-comment'),
      commentCountSelect: panel.querySelector('#wbx-comment-count'),
      favCb: panel.querySelector('#wbx-fav'),
      followCb: panel.querySelector('#wbx-follow'),
      restoreCb: panel.querySelector('#wbx-restore'),
      picLevelSelect: panel.querySelector('#wbx-piclevel'),
      picModeSelect: panel.querySelector('#wbx-picmode'),
      sortSelect: panel.querySelector('#wbx-sort'),
      avatarCb: panel.querySelector('#wbx-avatar'),
      delayInput: panel.querySelector('#wbx-delay'),
      startBtn: panel.querySelector('#wbx-start'),
      pauseBtn: panel.querySelector('#wbx-pause'),
      stopBtn: panel.querySelector('#wbx-stop'),
      exportJsonBtn: panel.querySelector('#wbx-export-json'),
      exportHtmlBtn: panel.querySelector('#wbx-export-html'),
      exportPdfBtn: panel.querySelector('#wbx-export-pdf'),
      exportImgsBtn: panel.querySelector('#wbx-export-imgs'),
      clearBtn: panel.querySelector('#wbx-clear'),
      checkBtn: panel.querySelector('#wbx-check'),
      bar: panel.querySelector('#wbx-bar-inner'),
      count: panel.querySelector('#wbx-count'),
      status: panel.querySelector('#wbx-status'),
    };

    let hidden = false;
    $wbx.min.addEventListener('click', () => {
      hidden = !hidden;
      $wbx.body.style.display = hidden ? 'none' : '';
      $wbx.min.textContent = hidden ? '展开 ▼' : '收起 ▲';
    });

    $wbx.startBtn.addEventListener('click', () => {
      try {
        collectOptionsFromUI();
        saveOptions();
        console.log('[weibo-export] v' + SCRIPT_VERSION + ' 点击开始导出，uid=' + (opts.uid || '(空)'));
        setStatus('正在启动…');
        setRunningUI(true);
        run().catch((e) => {
          setRunningUI(false);
          setStatus('启动失败：' + (e && e.message ? e.message : String(e)));
        });
      } catch (e) {
        setRunningUI(false);
        setStatus('启动失败：' + (e && e.message ? e.message : String(e)));
      }
    });
    $wbx.pauseBtn.addEventListener('click', togglePause);
    $wbx.stopBtn.addEventListener('click', stop);
    $wbx.exportJsonBtn.addEventListener('click', () => { collectOptionsFromUI(); exportJSON(); });
    $wbx.exportHtmlBtn.addEventListener('click', () => { collectOptionsFromUI(); exportHTML(); });
    $wbx.exportPdfBtn.addEventListener('click', () => { collectOptionsFromUI(); exportPDF(); });
    $wbx.exportImgsBtn.addEventListener('click', () => { collectOptionsFromUI(); exportImagesCSV(); });
    $wbx.clearBtn.addEventListener('click', clearData);
    $wbx.checkBtn.addEventListener('click', () => { collectOptionsFromUI(); selfCheck(); });

    setStatus('就绪。填写/确认 uid 后点“开始导出”。');
  }

  // ==================== 启动 ====================
  if (doc) {
    renderPanel();
    console.log('[weibo-export] v' + SCRIPT_VERSION + ' 初始化完成，uid=' + (opts.uid || '(空)') + '，面板版本见标题');
    dbCount('posts').then((n) => {
      if (doc && $wbx && $wbx.count) {
        $wbx.count.textContent = '本地缓存：' + n + ' 条微博' +
          (n === 0 ? '（网址 www 前缀不同缓存不互通，请用之前导出的那个网址打开）' : '');
        state.fetched = n;
      }
    }).catch(() => {});
  }

  // ==================== Node 测试导出 ====================
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = {
      parsePost,
      parseRetweet,
      parseComment,
      parseImages,
      pickImageUrl,
      parseXmlSource,
      parseLinkCard,
      parseProfile,
      transText,
      renderComment,
      renderPost,
      buildHtml,
      buildHtmlParts,
      esc,
      formatDate,
      EXPORT_CSS,
    };
  }
})();