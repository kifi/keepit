/*jshint globalstrict:true */
'use strict';

const ICON_ID = 'kifi-urlbar-icon';
const {BrowserWindow} = require('sdk/windows');
const {windows} = require('sdk/window/utils');

function getXpcomWindow(win) {
  for (let w of windows('navigator:browser')) {
    if (BrowserWindow({window: w}) === win) {
      return w;
    }
  }
}

exports.addToWindow = function(win, click) {
  // TODO: detect whether the window supports tabs and only add icon if it does?
  // for each (let n in xpcomWin.document.querySelector('#TabsToolbar').childNodes) {
  //   console.log('#############', n && n.nodeName, n && n.id);
  // }

  const xpcomWin = getXpcomWindow(win);
  const tb = xpcomWin.document.getElementById('urlbar-icons');
  if (tb) {
    const iconEl = xpcomWin.document.createElementNS('http://www.mozilla.org/keymaster/gatekeeper/there.is.only.xul', 'image');
    tb.insertBefore(iconEl, tb.firstChild);
    iconEl.setAttribute('id', ICON_ID);
    iconEl.setAttribute('height', 19);
    iconEl.setAttribute('class', 'urlbar-icon');
    iconEl.setAttribute('collapsed', true);

    const clickListener = click.bind(null, win);
    iconEl.addEventListener('click', clickListener);

    return function removeFromWindow() {
      iconEl.removeEventListener('click', clickListener);
      tb.removeChild(iconEl);
    };
  } else {
    return function () {};
  }
};

exports.show = function(win, uri) {
  const xpcomWin = getXpcomWindow(win);
  const iconEl = xpcomWin && xpcomWin.document.getElementById(ICON_ID);
  if (iconEl) {
    iconEl.setAttribute('src', uri);
    iconEl.removeAttribute('collapsed');
  }
};

exports.hide = function(win) {
  const xpcomWin = getXpcomWindow(win);
  const iconEl = xpcomWin && xpcomWin.document.getElementById(ICON_ID);
  if (iconEl) {
    iconEl.setAttribute('collapsed', true);
  }
};
