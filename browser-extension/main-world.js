// 在页面主世界运行，把页面里的 uid 信息写入 DOM，供隔离世界里的主脚本读取
(function () {
  'use strict';
  try {
    if (window.$CONFIG && window.$CONFIG.oid) {
      document.documentElement.dataset.wbxOid = String(window.$CONFIG.oid);
    }
  } catch (e) { /* ignore */ }
  try {
    var s = window.__INITIAL_STATE__;
    if (s) {
      var uid = (s.loginInfo && s.loginInfo.uid) || (s.userInfo && s.userInfo.idstr);
      if (uid) document.documentElement.dataset.wbxUid = String(uid);
    }
  } catch (e) { /* ignore */ }
})();