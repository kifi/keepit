'use strict';

const {Cc, Ci} = require('chrome');
const AppShellService = Cc['@mozilla.org/appshell/appShellService;1'].getService(Ci.nsIAppShellService);

const {getMostRecentBrowserWindow} = require('sdk/window/utils');
const {getActiveTab, getTabContentWindow} = require('sdk/tabs/utils');

const NS = 'http://www.w3.org/1999/xhtml';

function createScreenshotCanvas() {
  let win = getTabContentWindow(getActiveTab(getMostRecentBrowserWindow()));
  let canvas = AppShellService.hiddenDOMWindow.document.createElementNS(NS, 'canvas');
  canvas.mozOpaque = true;
  let w = canvas.width = win.innerWidth;
  let h = canvas.height = win.innerHeight;
  let ctx = canvas.getContext('2d');
  ctx.drawWindow(win, win.scrollX, win.scrollY, w, h, 'rgb(255,255,255)');
  return canvas;
}

exports.take = function () {
  return createScreenshotCanvas().toDataURL();
};
