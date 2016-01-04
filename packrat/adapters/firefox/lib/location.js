/*jshint globalstrict:true */
'use strict';

const {Services} = require('resource://gre/modules/Services.jsm');
const {browserWindows} = require('sdk/windows');
const browserNs = require('sdk/core/namespace').ns();
const {viewFor} = require('sdk/view/core');

// Mozilla doesn't want us to use require('chrome'),
// so just save the value here. If something breaks,
// go get the freshest copy from the nsIWebProgressListener
const LOCATION_CHANGE_SAME_DOCUMENT = 0x00000001; // = Ci.nsIWebProgressListener.LOCATION_CHANGE_SAME_DOCUMENT)

// Mozilla doesn't want us to use require('sdk/tabs/utils'),
// so just copy this pure function from it. If something breaks
// just get the freshest value from mozilla-central/addon-sdk/source/lib/sdk/tabs/utils.js
function getTabId(tab) {
  if (tab.browser) {
    // fennec
    return tab.id;
  }

  return String.split(tab.linkedPanel, 'panel').pop();
}

function getTabIdForBrowser(gBrowser, browser) {
  for (let tab of gBrowser.tabs) {
    if (tab.linkedBrowser === browser) {
      return getTabId(tab);
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
  callback(getTabIdForBrowser(this, browser), !(flags & LOCATION_CHANGE_SAME_DOCUMENT));
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
  var enumerator = Services.wm.getEnumerator('navigator:browser');
  while(enumerator.hasMoreElements()) {
    var win = enumerator.getNext();
    onChange(win.gBrowser, callback);
  }
  browserWindows.on('open', function(win) {
    var domWin = viewFor(win);
    if (domWin) {
      onChange(viewFor(win).gBrowser, callback);
    }
  });
  browserWindows.on('close', function(win) {
    var domWin = viewFor(win);
    if (domWin) {
      offChange(domWin.gBrowser);
    }
  });
};

exports.onFocus = function (callback) {
  let callbacks = {
    urlbar: callback.bind(null, 'a'),
    searchbar: callback.bind(null, 's')
  };
  var enumerator = Services.wm.getEnumerator('navigator:browser');
  while(enumerator.hasMoreElements()) {
    var win = enumerator.getNext();
    onFocus(win, callback);
  }
  browserWindows.on('open', function (win) {
    var domWin = viewFor(win);
    if (domWin) {
      onFocus(viewFor(win), callbacks);
    }
  });
  browserWindows.on('close', function (win) {
    var domWin = viewFor(win);
    if (domWin) {
      offFocus(domWin, callbacks);
    }
  });
};
