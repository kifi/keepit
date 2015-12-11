'use strict';

const {Services} = require('resource://gre/modules/Services.jsm');
const {browserWindows} = require('sdk/windows');
const {viewFor} = require('sdk/view/core');
const NS = 'http://www.w3.org/1999/xhtml';

function newCanvas() {
  return Services.appShell.hiddenDOMWindow.document.createElementNS(NS, 'canvas');
}

exports.createBlankCanvas = newCanvas;

exports.takeToCanvas = function () {
  let win = viewFor(browserWindows.activeWindow);
  let canvas = newCanvas();
  canvas.mozOpaque = true;
  let w = canvas.width = win.innerWidth;
  let h = canvas.height = win.innerHeight;
  let ctx = canvas.getContext('2d');
  ctx.drawWindow(win, win.scrollX, win.scrollY, w, h, '#fff');
  return canvas;
};
