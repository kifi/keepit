/*jshint globalstrict:true */
'use strict';

const ICON_ID = 'kifi-urlbar-icon';
const { viewFor } = require('sdk/view/core');

exports.addToWindow = function(win, click) {
  const domWin = viewFor(win);
  const tb = domWin.document.getElementById('urlbar-icons');
  if (tb) {
    const iconEl = domWin.document.createElementNS('http://www.mozilla.org/keymaster/gatekeeper/there.is.only.xul', 'image');
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
  const domWin = viewFor(win);
  const iconEl = domWin && domWin.document.getElementById(ICON_ID);
  if (iconEl) {
    iconEl.setAttribute('src', uri);
    iconEl.removeAttribute('collapsed');
  }
};

exports.hide = function(win) {
  const domWin = viewFor(win);
  const iconEl = domWin && domWin.document.getElementById(ICON_ID);
  if (iconEl) {
    iconEl.setAttribute('collapsed', true);
  }
};
