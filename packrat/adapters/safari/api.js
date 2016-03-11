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
}
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
  var isPackaged = ~safari.extension.displayVersion.indexOf('dev');
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
            l`${CGREEN} [api:handling:emit] %s${page.id} %s${kind} %o${toEmitData != null ? toEmitData : ''}`;
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
          l`[api:onDOMContentLoaded] %s${tab.id} %s${tab.url}`;
        } else {
          l`${CRED}[api:onDOMContentLoaded] %s${tab.id} url mismatch:\n%s${tab.url}\n%s${page.url}`;
        }
        injectContentScripts(page, injected);
        respond();
        api.tabs.on.loading.dispatch(pages[tab.id]);
        if (doLogging) {
          page._port.postMessage('api:log', true);
        }
      } else {
        l`${CRED}[api:onDOMContentLoaded] no page for %s${tab.id} %s${tab.url}`; //', tab.id, tab.url);
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
    },
    'api:pagehide': function (data, respond, page) {
      // pagehide/pageshow are for the "Page Cache"/"bfcache" back/forward events
      l`[api:pagehide] %s${page.id} %s${page.url}`;
      // api.tabs.on.unload.dispatch(page);
    },
    'api:pageshow': function (data, respond, page) {
      l`[api:pageshow] %s${page.id} %s${page.url}`;
      api.tabs.on.loading.dispatch(page);
      // onPageShow(page.id, data.url);
    }
  };

  function onPageShow(tabId, url) {
    l`${CGRAY}[onCommitted] %s${tabId} %s${url}`;
    if (pages[tabId]) {
      removeTab(tabId);
    }
    var page = createPage(tabId, url);
    if (httpRe.test(page.url)) {
      api.tabs.on.loading.dispatch(page);
    }
  }

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
    var paths = scriptPaths.concat(stylePaths);
    scriptPaths.forEach(function (p) {
      loadContent(p, function (text) {
        scripts.push(text);
        done();
      });
    });

    stylePaths.forEach(function (p) {
      styles.push(p);
    });

    done();

    function done() {
      if (loadsLeft === 0) {
        page._port.postMessage('api:inject', { scripts, styles, paths });
        if (callback) {
          callback({ scripts, styles, paths });
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
      l`${CGREEN}[onMessage] %s${page.id} %s${kind} %s${data != null ? (data.join ? data.join(' ') : data) : ''}`;
      handler(data, respondToTab.bind(null, page, callbackId), page);
    } else {
      l`${CRED}[onMessage] %s${page.id} %s${kind} ignored, page: %O${page} handler: %s${!!handler}`;
    }
  }

  function respondToTab(tab, callbackId, data) {
    if (tab._port) {
      tab._port.postMessage('api:respond', { callbackId, data });
    }
  }

  // Icon handling
  safari.application.addEventListener('command', errors.wrap(function (e) {
    if (e.command === 'kifi-icon') {
      var tab = e.target.browserWindow.activeTab;
      var {url, id} = tab;
      if (url) {
        api.icon.on.click.dispatch(pages[id]);
      }
    }
  }));
  safari.application.addEventListener('validate', errors.wrap(function (e) {
    if (e.command === 'kifi-icon') {
      var tab = e.target.browserWindow.activeTab;
      var {url, id} = tab;
      // Disable the button if there is no URL loaded in the tab.
      e.target.disabled = !url;
    }
  }));

  safari.application.addEventListener('open', errors.wrap(function (e) {
    // is it a tab or a window?
    var target = e.target;
    var isWindow = !!target.tabs;
    if (isWindow) {
      l`[window.open]`;
      initWindow(target);
    } else {
      l`[tab.open]`;
      initTab(target);
    }
  }), true);

  safari.application.addEventListener('navigate', errors.wrap(function (e) {
    var target = e.target;
    l`${CRED}[application.navigate] %s${target.id} %s${target.url} %O${e}`;
  }), true);

  safari.application.addEventListener('activate', errors.wrap(function (e) {
    if (e.target instanceof SafariBrowserWindow) {
      onWindowActivate.apply(this, arguments);
    }
  }), true);

  safari.application.addEventListener('deactivate', errors.wrap(function (e) {
    if (e.target instanceof SafariBrowserWindow) {
      onWindowDeactivate.apply(this, arguments);
    }
  }), true);

  function onWindowActivate(e) {
    var win = e.target;
    var tab = win.activeTab;
    l`${CGRAY}[tabs.windowActivate] %s${tab.id} %s${tab.url}`;
    api.tabs.on.focus.dispatch(pages[tab.id]);
  }

  function onWindowDeactivate(e) {
    var win = e.target;
    var tab = win.activeTab;
    l`${CGRAY}[tabs.windowDeactivate] %s${tab.id} %s${tab.url}`;
    api.tabs.on.blur.dispatch(pages[tab.id]);
  }

  function createPort(tab) {
    var port = {
      postMessage: function (kind, data) {
        if (httpRe.test(tab.url)) {
          tab.page.dispatchMessage(kind, data);
        } else {
          l`${CRED}[page.postMessage] unable to send event %s${kind} %O${data} to %s${tab.url}`;
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

    tab.addEventListener('activate', errors.wrap(function (e) {
      l`${CGRAY}[tabs.activate] %s${tab.id} %s${tab.url}`;
      api.tabs.on.focus.dispatch(pages[tab.id]);
    }));

    tab.addEventListener('deactivate', errors.wrap(function (e) {
      l`${CGRAY}[tabs.deactivate] %s${tab.id} %s${tab.url}`;
      api.tabs.on.blur.dispatch(pages[tab.id]);
    }));

    tab.addEventListener('beforeNavigate', errors.wrap(function (e) {
      var id = tab.id;
      var page = pages[tab.id];
      var url = e.url;
      var match = googleSearchRe.exec(url);

      removeTab(id);
      page = pages[id] = createPage(id, url, createPort(tab));

      l`${CGREEN}[onBeforeNavigate] %s${tab.id} %s${url} %s${match}`;

      if (match) {
        var query;
        try {
          query = decodeURIComponent(match[1].replace(plusRe, ' ')).trim();
        } catch (e) {
          l`[onBeforeNavigate] non-UTF-8 search query: %s${match[1]} %O${e}`; // e.g. www.google.co.il/search?hl=iw&q=%EE%E9%E4
        }
        if (query) {
          api.on.search.dispatch(query, ~url.indexOf('sourceid=chrome') ? 'o' : 'n');
        }
      }
    }));

    tab.addEventListener('navigate', errors.wrap(function (e) {
      // Tends to happen before DOMContentLoaded.
      l`${CGRAY}[tabs.navigate] %s${tab.id} %s${tab.url}`;
    }));

    tab.addEventListener('close', errors.wrap(function (e) {
      l`${CGRAY}[tabs.close] %s${tab.id} %s${tab.url}`;
      var page = pages[tab.id];
      page._port.disconnect();
      removeTab(tab.id);
    }));

    tab.addEventListener('message', errors.wrap(onTabMessage.bind(null, tab)));
  }

  function removeTab(tabId) {
    var page = pages[tabId];

    if (httpRe.test(page.url)) {
      api.tabs.on.unload.dispatch(page);
    }

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
        var iconUri;
        var kept = false;
        var ii = false;
        var icon = safari.extension.toolbarItems[0];

        if (tab === pages[tab.id]) {
          ii = ~path.indexOf('II');
          kept = !(~path.indexOf('gray') || ~path.indexOf('dark'));
          iconUri = `${safari.extension.baseURI}icons/kifi.monochrome${ii ? '_II' : ''}${kept ? '.invert' : ''}.png`;

          l`${CGREEN} [icon.set] %s${tab.id} %s${tab.url} kept: %s${kept}, silenced: %s${ii}, path: %s${path}, iconUri: %s${iconUri}`;

          tab.icon = path;
          icon.image = iconUri;
          icon.toolTip = (kept ? 'Kept with Kifi' : 'Kifi');
        }
      }
    },
    inspect: {
      pages: pages
    },
    isPackaged: function() {
      return isPackaged;
    },
    loadReason: (function () {
      var extension = safari.extension;
      var settings = safari.extension.settings;
      var savedHasRun = settings.hasRun;
      var savedLastVersion = !!settings.lastVersion;

      settings.hasRun = true;
      settings.lastVersion = extension.bundleVersion;

      if (!savedHasRun) {
        return 'install';
      } else if (savedLastVersion !== extension.bundleVersion) {
        return 'update';
      } else if (savedLastVersion){
        return 'startup';
      } else {
        return 'enabled';
      }
    }()),
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
    requestUpdateCheck: function (force) {
      if (force) {
        emitUpdateAvailable();
      } else {
        var versionXPath = '//key[text()="CFBundleVersion"]/following-sibling::string/text()';
        var request = new XMLHttpRequest();
        request.open('GET', 'https://www.kifi.com/extensions/safari/KifiUpdates.plist', true);
        request.onload = function() {
          if (this.status >= 200 && this.status < 400) {
            var xml = this.responseXML;
            var xPathResult = xml.evaluate(versionXPath, xml, null, XPathResult.STRING_TYPE, null);
            var updateVersion;
            if (!xPathResult.invalidIteratorState) {
              updateVersion = xPathResult.stringValue
              if (safari.extension.bundleVersion !== updateVersion) {
                emitUpdateAvailable();
              }
            } else {
              onError.call(this, xPathResult);
            }
          } else {
            onError.call(this);
          }
        };
        request.onerror = onError;
        request.send();
      }

      function emitUpdateAvailable() {
        api.tabs.each(function (page) {
          page._port.postMessage('api:safari-update');
        });
      }

      function onError(extra) {
        l`${CRED}[requestUpdateCheck] an error occurred in the response %O${this} %s${this.response} %s${extra}`;
      }
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
        l`[api.socket.open] %s${url}`;
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
          if (page.url === url || page.nUri === url) {
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
            l`${CGREEN}[api.tabs.emit] %s${tab.id} %s${type} %O${data}`;
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
          l`${CRED}[api.tabs.emit] suppressed %s${tab.id} %s${type} navigated: %s${tab.url} -> %s${page && page.url}`;
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
