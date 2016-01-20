// Safari Background API

const hexRe = /^#[0-9a-f]{3}$/i;
const CRED = '#a00';
const CGREEN = '#0a0';
const CGRAY = '#666';
function log(a0) {
  'use strict';
  var args = arguments;
  if (hexRe.test(a0)) {
    args[0] = '%c' + args[1];
    args[1] = 'color:' + a0;
  }
  console.log.apply(console, args);
};
const l = function logTaggedTemplateString(strings, values) {
  'use strict';
  values = Array.prototype.slice.call(arguments, 1);
  if (hexRe.test(values[0])) {
    log.apply(null, [values[0], strings.join('')].concat(values.slice(1)));
  } else {
    log.apply(null, [strings.join('')].concat(values));
  }
};

var api = api || (function () {
  var isPackaged = false;//!!chrome.runtime.getManifest().update_url;
  var doLogging = !isPackaged;

  var urlWithoutPathRe = /^https?:\/\/[^\/]+/;
  var runningTabId = 0;

  var errors = [];
  errors.wrap = function (fn) {
    return function wrapped() {
      try {
        return fn.apply(this, arguments);
      } catch (e) {
        errors.push({error: e, params: {'arguments': Array.prototype.slice.call(arguments)}});
      }
    };
  };

  function getBrowserTabById(id) {
    var tabs = safari.application.browserWindows.reduce(function (acc, bw) {
      return acc.concat(bw.tabs);
    }, []);
    var tabsWithId = tabs.filter(function (t) {
      return t.id === id;
    });

    if (tabsWithId.length === 1) {
      return tabsWithId[0];
    } else {
      l`${CRED}[getBrowserTabById] duplicate tabs with id %s${id}`;
    }
  }

  var pages = {};
  function createPage(id, url, port) {
    var now = Date.now();
    var page = pages[id] = {url: url};
    Object.defineProperty(page, 'id', {value: id, enumerable: true});
    Object.defineProperty(page, '_created', {value: now});
    Object.defineProperty(page, '_port', {value: port, configurable: true});
    Object.defineProperty(page, '_handling', {value: {}, configurable: true});
    return page;
  }

  var portHandlers = {
    'api:handling': function (data, _, page) {
      var handlingKeys = data;
      for (var i = 0; i < handlingKeys.length; i++) {
        page._handling[handlingKeys[i]] = true;
      }
      var toEmit = page.toEmit;
      if (toEmit) {
        for (var i = 0; i < toEmit.length;) {
          var m = toEmit[i];
          var kind = m[0];
          var toEmitData = m[1];
          if (page._handling[m[0]]) {
            log(CGREEN, '[api:handling:emit] %s %s %o', page.id, kind, toEmitData != null ? toEmitData : '');
            page._port.postMessage(kind, toEmitData);
            toEmit.splice(i, 1);
          } else {
            i++;
          }
        }
        if (!toEmit.length) {
          delete page.toEmit;
        }
      }
    },
    'api:onConnect': function (data, respond, page) {
      l`${CGREEN}[api:onConnect] %s${page.id} %s${page.url} %O${data}`;
    },
    'api:DOMContentLoaded': function (data, respond, tab) {
      var page = pages[tab.id];
      var {injected} = data;
      if (page) {
        if (page.url === tab.url) {
          log('[api:onDOMContentLoaded] %s %s', tab.id, tab.url);
        } else {
          log(CRED, '[api:onDOMContentLoaded] %s url mismatch:\n%s\n%s', tab.id, tab.url, page.url);
        }
        injectContentScripts(page, injected);
        respond();
        api.tabs.on.loading.dispatch(pages[tab.id]);
      } else {
        log(CRED, '[api:onDOMContentLoaded] no page for', tab.id, tab.url);
      }
    },
    'api:require': function (data, respond, page) {
      var {paths, injected} = data;
      l`[api:require] %s${page.id} %s${page.url} %O${paths}`;
      injectWithDeps(page, data.paths, data.injected, respond);
    },
    'api:beforeunload': function (data, respond, page) {
      l`[api:beforeunload] %s${page.id} %s${page.url}`;
    },
    'api:unload': function (data, respond, page) {
      l`[api:unload] %s${page.id} %s${page.url}`;
    }
  };

  function injectWithDeps(page, paths, injected, callback) {
    var {scripts, styles} = deps(paths, injected);
    l`[injectWithDeps] %s${page.id} %s${page.url}, scripts: %O${scripts} styles: %O${styles}`;

    injectContent(page, scripts, styles, callback);
  }

  function injectContentScripts(page, injected) {
    var paths = meta.contentScripts.filter(function(cs) { return cs[1].test(page.url); }).map(function (cs) { return cs[0]; });
    if (paths.length) {
      var {scripts, styles} = deps(paths, injected);
      injectContent(page, scripts, styles);
    }
  }

  function injectContent(page, scriptPaths, stylePaths, callback) {
    var loadsLeft = (scriptPaths || []).length;// + (stylePaths || []).length;
    var scripts = [];
    var styles = [];
    scriptPaths.forEach(function (p) {
      loadContent(p, function (text) {
        scripts.push(text);
        done();
      })
    });

    stylePaths.forEach(function (p) {
      styles.push(p);
    });

    done();

    function done() {
      if (loadsLeft === 0) {
        page._port.postMessage('api:inject', { scripts, styles });
        if (callback) {
          callback({ scripts, styles });
        }
      } else {
        loadsLeft--;
      }
    }
  }

  function loadContent(path, callback) {
    var request = new XMLHttpRequest();
    request.open('GET', safari.extension.baseURI + path, true);

    request.onload = function() {
      var success = ((this.status === 0 && this.response.length > 0) || (this.status >= 200 && this.status < 400));
      if (success) {
        // Success!
        var data = this.response;
        callback(data);
      } else {
        // We reached our target server, but it returned an error
        l`${CRED}'[loadContent] request.onload: server returned error status %s${this.status} with response %s${this.response}`
      }
    };

    request.onerror = function (e) {
      l`${CRED}[loadContent] request.onerror: %O${e}`;
    };

    request.send();
  }

  function onTabMessage(tab, e) {
    var page = pages[tab.id];
    var kind = e.name, data = e.message.data, callbackId = e.message.callbackId;
    var handler = portHandlers[kind];
    if (handler) {
      log(CGREEN, '[onMessage] %s %s', page.id, kind, data != null ? (data.join ? data.join(' ') : data) : '');
      handler(data, respondToTab.bind(null, page, callbackId), page);
    } else {
      log(CRED, '[onMessage] %s %s %s %O %s', page.id, kind, 'ignored, page:', page, 'handler:', !!handler);
    }
  }

  function respondToTab(tab, callbackId, data) {
    if (tab._port) {
      tab._port.postMessage('api:respond', { callbackId, data });
    }
  }

  safari.application.addEventListener('open', function (e) {
    // is it a tab or a window?
    var target = e.target;
    var isWindow = !!target.tabs;
    if (isWindow) {
      log('[window.open]');
      initWindow(target);
    } else {
      log('[tab.open]');
      initTab(target);
    }
  }, true);

  safari.application.addEventListener('navigate', function (e) {
    var target = e.target;
    l`${CRED}[application.navigate] %s${target.id} %s${target.url} %O${e}`;
  }, true);

  safari.application.addEventListener('activate', function (e) {
    if (e.target instanceof SafariBrowserWindow) {
      onWindowActivate.apply(this, arguments);
    }
  }, true);

  safari.application.addEventListener('deactivate', function (e) {
    if (e.target instanceof SafariBrowserWindow) {
      onWindowDeactivate.apply(this, arguments);
    }
  }, true);

  function onWindowActivate(e) {
    var win = e.target;
    var tab = win.activeTab;
    log(CGRAY, '[tabs.windowActivate] %s %s', tab.id, tab.url);
    api.tabs.on.focus.dispatch(pages[tab.id]);
  }

  function onWindowDeactivate(e) {
    var win = e.target;
    var tab = win.activeTab;
    log(CGRAY, '[tabs.windowDeactivate] %s %s', tab.id, tab.url);
    api.tabs.on.blur.dispatch(pages[tab.id]);
  }

  function createPort(tab) {
    var port = {
      postMessage: function (kind, data) {
        if (httpRe.test(tab.url)) {
          tab.page.dispatchMessage(kind, data);
        } else {
          log(CRED, '[page.postMessage] unable to send event %s %O to %s', kind, data, tab.url);
        }
      },
      disconnect: function () {
        if (tab && tab.page && tab.page.dispatchMessage) {
          tab.page.dispatchMessage('api:disconnect');
        } else {
          l`${CRED}[tab.disconnect] couldn't call disconnect on tab with undefined page`
        }
      }
    };
    return port;
  }

  safari.application.browserWindows.forEach(initWindow);

  function initWindow(win) {
    win.tabs.forEach(initTab);
  }

  function initTab(tab) {
    if (tab.initialized) {
      return;
    }
    tab.initialized = true;
    tab.id = tab.id || runningTabId++;

    var port = createPort(tab);
    var page = pages[tab.id] || createPage(tab.id, tab.url, port);

    tab.addEventListener('activate', function (e) {
      log(CGRAY, '[tabs.activate] %s %s', tab.id, tab.url);
      api.tabs.on.focus.dispatch(pages[tab.id]);
    });

    tab.addEventListener('deactivate', function (e) {
      log(CGRAY, '[tabs.deactivate] %s %s', tab.id, tab.url);
      api.tabs.on.blur.dispatch(pages[tab.id]);
    });

    tab.addEventListener('beforeNavigate', function (e) {
      var id = tab.id;
      var page = pages[tab.id];
      var url = e.url;
      var match = googleSearchRe.exec(url);

      //if (page.url !== url) {
        removeTab(id);
        page = pages[id] = createPage(id, url, createPort(tab));
      //}

      l`${CGREEN}[onBeforeNavigate] %s${tab.id} %s${url} %s${match}`;

      if (match) {
        var query;
        try {
          query = decodeURIComponent(match[1].replace(plusRe, ' ')).trim();
        } catch (e) {
          log('[onBeforeNavigate] non-UTF-8 search query:', match[1], e);  // e.g. www.google.co.il/search?hl=iw&q=%EE%E9%E4
        }
        if (query) {
          api.on.search.dispatch(query, ~url.indexOf('sourceid=chrome') ? 'o' : 'n');
        }
      }
    });

    tab.addEventListener('navigate', function (e) {
      // Tends to happen before DOMContentLoaded.
      log(CGRAY, '[tabs.navigate] %s %s', tab.id, tab.url);
    });

    tab.addEventListener('close', function (e) {
      log(CGRAY, '[tabs.close] %s %s', tab.id, tab.url);
      removeTab(tab.id);
    });

    tab.addEventListener('message', onTabMessage.bind(null, tab));
  }

  function removeTab(tabId) {
    var page = pages[tabId];

    if (httpRe.test(page.url)) {
      api.tabs.on.unload.dispatch(page);
    }

    page._port.disconnect();
    delete pages[tabId];
  }

  var stripHashRe = /^[^#]*/;
  var googleSearchRe = /^https?:\/\/www\.google\.[a-z]{2,3}(?:\.[a-z]{2})?\/(?:|search|webhp)[\?#](?:.*&)?q=([^&#]*)/;
  var plusRe = /\+/g;
  var httpRe = /^https?:/;
  var hostRe = /^https?:\/\/[^\/]*/;
  return {
    bookmarks: {
      getAll: function (callback) {
        // unfortunately this is impossible in safari : (
        callback([]);
      }
    },
    errors: {
      init: function (handler) {
        while (errors.length) {
          handler.push(errors.shift());
        }
        handler.wrap = errors.wrap;
        errors = handler;
      },
      push: function (o) {
        errors.push(o);
      },
      wrap: errors.wrap
    },
    icon: {
      on: {click: new Listeners},
      set: function(tab, path) {
        // TODO(carlos): not sure about this one yet
        // if (tab === pages[tab.id]) {
        //   tab.icon = path;
        //   if (normalTab[tab.id]) {
        //     setPageAction(tab.id, path);
        //   }
        // }
      }
    },
    inspect: {
      pages: pages
    },
    isPackaged: function() {
      return isPackaged;
    },
    loadReason: 'enable',  // by elimination
    mode: {
      isDev: function () {
        return localStorage[':mode'] === 'dev';
      },
      toggle: function () {
        if (localStorage[':mode']) {
          delete localStorage[':mode'];
        } else {
          localStorage[':mode'] = 'dev';
        }
        chrome.runtime.reload();
      }
    },
    on: {
      beforeSearch: new Listeners,
      search: new Listeners
    },
    noop: function() {},
    play: function(path) {
      // Safari's extension global page can't play HTML5 audio,
      // so just tell the client page to do it
      var activeTab = safari.application.activeBrowserWindow.activeTab;
      var activePage = pages[activeTab.id];
      var src =  safari.extension.baseURI + path;
      activePage._port.postMessage('api:play', { src });
    },
    port: {
      on: function (handlers) {
        Object.keys(handlers).forEach(function (k) {
          if (portHandlers[k]) {
            throw Error(k + ' handler already defined');
          }
          portHandlers[k] = handlers[k];
        });
      }
    },
    util: {
      btoa: window.btoa.bind(window)
    },
    browser: {
      name: 'Safari',
      userAgent: navigator.userAgent
    },
    screenshot: function (callback) {
      var activeTab = safari.application.activeBrowserWindow.activeTab;
      activeTab.visibleContentsAsDataURL(function (dataUri) {
        var img = document.createElement('img');
        img.src = dataUri;
        callback(img, document.createElement('canvas'));
      });
    },
    socket: {
      open: function (url, handlers, onConnect, onDisconnect) {
        log('[api.socket.open]', url);
        var sc, rws = new ReconnectingWebSocket(url, {
          onConnect: errors.wrap(function () {
            sc.onConnect();
          }),
          onDisconnect: errors.wrap(function (why, sec) {
            sc.onDisconnect(why, sec);
          }),
          onMessage: errors.wrap(function (e) {
            sc.onMessage(e.data);
          })
        });
        return (sc = new SocketCommander(rws, handlers, onConnect, onDisconnect, log));
      }
    },
    storage: localStorage,
    tabs: {
      anyAt: function (url) {
        for (var id in pages) {
          var page = pages[id];
          if (page.url === url) {
            return page;
          }
        }
      },
      select: function (tabId) {
        var tab = getBrowserTabById(tabId);
        if (tab) {
          tab.activate();
          tab.browserWindow.activate();
        }
      },
      open: function (url, callback) {
        var tab = safari.application.activeBrowserWindow.openTab();
        initTab(tab);
        tab.url = url;

        if (callback) {
          callback(tab.id);
        }
      },
      selectOrOpen: function (url) {
        var tab = api.tabs.anyAt(url);
        if (tab) {
          api.tabs.select(tab.id);
        } else {
          api.tabs.open(url);
        }
      },
      close: function (tabId) {
        var tab = getBrowserTabById(tabId);
        if (tab) {
          tab.close();
        }
      },
      on: {
        focus: new Listeners,
        blur: new Listeners,
        loading: new Listeners,
        unload: new Listeners
      },
      each: function (callback) {
        for (var id in pages) {
          var page = pages[id];
          if (page && httpRe.test(page.url)) {
            callback(page);
          }
        }
      },
      eachSelected: function (callback) {
        safari.application.browserWindows.forEach(function (browserWindow) {
          var page = pages[browserWindow.activeTab.id];
          if (page && httpRe.test(page.url)) {
            callback(page);
          }
        });
      },
      emit: function (tab, type, data, opts) {
        var page = pages[tab.id];
        if (page && (page === tab || page.url.match(hostRe)[0] === tab.url.match(hostRe)[0])) {
          if ((page._handling || {})[type]) {
            log(CGREEN, '[api.tabs.emit] %s %s %O', tab.id, type, data);
            page._port.postMessage(type, data);
          } else if (opts && opts.queue) {
            var toEmit = page.toEmit;
            if (toEmit) {
              if (opts.queue === 1) {
                for (var i = 0; i < toEmit.length; i++) {
                  var m = toEmit[i];
                  if (m[0] === type) {
                    m[1] = data;
                    return;
                  }
                }
              }
              toEmit.push([type, data]);
            } else {
              page.toEmit = [[type, data]];
            }
          }
        } else {
          log(CRED, '[api.tabs.emit] suppressed %s %s navigated: %s -> %s', tab.id, type, tab.url, page && page.url);
        }
      },
      get: function (tabId) {
        var page = pages[tabId];
        if (page.url) {
          return pages[tabId];
        } else {
          return null;
        }
      },
      getFocused: function () {
        var selectedTab = safari.application.activeBrowserWindow.activeTab;
        var id = selectedTab.id;
        return id ? pages[id] : null;
      },
      isFocused: function (tab) {
        return getBrowserTabById(tab.id).active;
      },
      navigate: function (tabId, url) {
        var tab = getBrowserTabById(tabId);
        if (tab) {
          api.tabs.select(tabId);
          tab.url = url;
        }
      },
      reload: function (tabId) {
        var page = pages[tabId];
        page._port.postMessage('api:reload');
      }
    },
    toggleLogging: function (bool) {
      if (doLogging !== bool) {
        doLogging = bool;
        api.tabs.each(function (page) {
          api.tabs.emit(page, 'api:log', doLogging);
        });
      }
    },
    timers: {
      setTimeout: function (f, ms) {
        return window.setTimeout(errors.wrap(f), ms);
      },
      setInterval: function (f, ms) {
        return window.setInterval(errors.wrap(f), ms);
      },
      clearTimeout: window.clearTimeout.bind(window),
      clearInterval: window.clearInterval.bind(window)
    },
    version: safari.extension.bundleVersion,
    xhr: XMLHttpRequest
  };
}());
