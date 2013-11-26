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

function onFocus(win, callback) {
  for (let id of ['urlbar', 'searchbar']) {
    win.document.getElementById(id).addEventListener('focus', callback);
  }
}

function offFocus(win, callback) {
  for (let id of ['urlbar', 'searchbar']) {
    win.document.getElementById(id).removeEventListener('focus', callback);
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
    offChange(getXpcomWindow(win).gBrowser);
  });
};

exports.onFocus = function (callback) {
  for (let win of windows('navigator:browser')) {
    onFocus(win, callback);
  }
  browserWindows.on('open', function(win) {
    onFocus(getXpcomWindow(win), callback);
  });
  browserWindows.on('close', function(win) {
    offFocus(getXpcomWindow(win));
  });
};
