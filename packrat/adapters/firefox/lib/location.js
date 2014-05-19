/*jshint globalstrict:true */
'use strict';

const {Ci} = require('chrome');
const {browserWindows, BrowserWindow} = require('sdk/windows');
const tabs = require('sdk/tabs');
const {getTabId} = require('sdk/tabs/utils');
const {windows} = require('sdk/window/utils');
const browserNs = require('sdk/core/namespace').ns();

function getTabIdForBrowser(gBrowser, browser) {
  for (let tab of gBrowser.tabs) {
    if (tab.linkedBrowser === browser) {
      return getTabId(tab);
    }
  }
}

function getXpcomWindow(win) {
  for (let w of windows('navigator:browser')) {
    if (BrowserWindow({window: w}) === win) {
      return w;
    }
  }
}

function onChange(gBrowser, callback) {
  gBrowser.addTabsProgressListener(
    browserNs(gBrowser).listener = {onLocationChange: change.bind(gBrowser, callback)});
}

function offChange(gBrowser) {
  gBrowser.removeTabsProgressListener(browserNs(gBrowser).listener);
}

function change(callback, browser, progress, req, loc, flags) {
  callback(getTabIdForBrowser(this, browser), !(flags & Ci.nsIWebProgressListener.LOCATION_CHANGE_SAME_DOCUMENT));
}

function onFocus(win, callbacks) {
  for (let id in callbacks) {
    var el = win.document.getElementById(id);
    if (el) {
      el.addEventListener('focus', callbacks[id]);
    }
  }
}

function offFocus(win, callbacks) {
  for (let id in callbacks) {
    var el = win.document.getElementById(id);
    if (el) {
      el.removeEventListener('focus', callbacks[id]);
    }
  }
}

exports.onChange = function (callback) {
  for (let win of windows('navigator:browser')) {
    onChange(win.gBrowser, callback);
  }
  browserWindows.on('open', function(win) {
    onChange(getXpcomWindow(win).gBrowser, callback);
  });
  browserWindows.on('close', function(win) {
    var xpcomWin = getXpcomWindow(win);
    if (xpcomWin) {
      offChange(xpcomWin.gBrowser);
    }
  });
};

exports.onFocus = function (callback) {
  let callbacks = {
    urlbar: callback.bind(null, 'a'),
    searchbar: callback.bind(null, 's')
  };
  for (let win of windows('navigator:browser')) {
    onFocus(win, callbacks);
  }
  browserWindows.on('open', function (win) {
    onFocus(getXpcomWindow(win), callbacks);
  });
  browserWindows.on('close', function (win) {
    var xpcomWin = getXpcomWindow(win);
    if (xpcomWin) {
      offFocus(xpcomWin, callbacks);
    }
  });
};
