// @require scripts/lib/mustache.js

var cdnBase = api.dev ?
  "http://dev.ezkeep.com:9000" : //d1scct5mnc9d9m.cloudfront.net
  "//djty7jcqog9qu.cloudfront.net";

var render = function() {
  'use strict';
  return function(path, params, partials, callback) {  // partials and callback are both optional
    if (!callback) {
      if (partials) {
        if (typeof partials === 'function') {
          callback = partials, partials = null;
        }
      } else if (params && typeof params === 'function') {
        callback = params, params = null;
      }
    }
    var paths = [path];
    if (partials) {
      var partialPaths = {}, basePath = path.replace(/[^\/]*$/, "");
      for (var name in partials) {
        paths.push(partialPaths[name] = basePath + partials[name]);
      }
    }
    paths = paths.filter(notCached);
    if (callback) { // async
      if (paths.length) {
        api.require(paths.map(toJsPath), function() {
          callback(mustacheRender());
        });
      } else {
        callback(mustacheRender());
      }
    } else { // sync
      if (paths.length) {
        log('[render] not yet cached:', paths);
        return '';
      }
      return mustacheRender();
    }

    function mustacheRender() {
      var partialsHtml = {};
      if (partials) {
        for (var name in partials) {
          partialsHtml[name] = render.cache[partialPaths[name]];
        }
      }
      return Mustache.render(
        render.cache[path],
        $.extend({cdnBase: cdnBase}, params),
        partialsHtml);
    }
  };

  function cached(path) {
    return !!render.cache[path];
  }
  function notCached(path) {
    return !render.cache[path];
  }
  function toJsPath(path) {
    return "scripts/" + path + ".js";
  }
}();

render.cache = {};
