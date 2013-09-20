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

function getBrowser(win) {
  for (let w of windows('navigator:browser')) {
    if (BrowserWindow({window: w}) === win) {
      return w.gBrowser;
    }
  }
}

function on(gBrowser, callback) {
  gBrowser.addTabsProgressListener(
    browserNs(gBrowser).listener = {onLocationChange: change.bind(gBrowser, callback)});
}

function off(gBrowser) {
  gBrowser.removeTabsProgressListener(browserNs(gBrowser).listener);
}

function change(callback, browser, progress, req, loc, flags) {
  callback(getTabIdForBrowser(this, browser), !(flags & Ci.nsIWebProgressListener.LOCATION_CHANGE_SAME_DOCUMENT));
}

exports.onChange = function(callback) {
  for (let win of windows('navigator:browser')) {
    on(win.gBrowser, callback);
  }

  browserWindows.on('open', function(win) {
    on(getBrowser(win), callback);
  });
  browserWindows.on('close', function(win) {
    off(getBrowser(win));
  });
};
