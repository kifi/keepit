//

const { Ci, Cc } = require("chrome");
const WM = Cc["@mozilla.org/appshell/window-mediator;1"].getService(Ci.nsIWindowMediator);
const ICON_ID = "kifi-urlbar-icon";
const windows = require("sdk/windows").browserWindows;

function indexOf(win) {
  for (let i = windows.length; i--;) {
    if (win === windows[i]) return i;
  }
}

// Gets the XPCOM window for a BrowserWindow, relying on both window collections using
// the same ordering (oldest to newest).
// https://addons.mozilla.org/en-US/developers/docs/sdk/latest/modules/sdk/windows.html#browserWindows
// https://developer.mozilla.org/en-US/docs/XPCOM_Interface_Reference/nsIWindowMediator#getEnumerator()
function getXpcomWindow(win) {
  // return WM.getMostRecentWindow("navigator:browser");
  const winIndex = indexOf(win);
  const xpcomWins = WM.getEnumerator("navigator:browser");
  var xpcomWin;
  for (let i = 0; i <= winIndex; i++) {
    xpcomWin = xpcomWins.hasMoreElements() && xpcomWins.getNext();
  }
  return xpcomWin || undefined;
}

exports.addToWindow = function(win, click) {
  // TODO: detect whether the window supports tabs and only add icon if it does?
  // for each (let n in xpcomWin.document.querySelector("#TabsToolbar").childNodes) {
  //   console.log("#############", n && n.nodeName, n && n.id);
  // }

  const clickListener = click.bind(null, win);

  let xpcomWin = getXpcomWindow(win);

  let iconEl = xpcomWin.document.createElementNS("http://www.mozilla.org/keymaster/gatekeeper/there.is.only.xul", "image");
  iconEl.setAttribute("id", ICON_ID);
  iconEl.setAttribute("height", 19);
  iconEl.setAttribute("class", "urlbar-icon");
  iconEl.setAttribute("collapsed", true);
  iconEl.addEventListener("click", clickListener);

  let tb = xpcomWin.document.getElementById("urlbar-icons");
  tb.insertBefore(iconEl, tb.firstChild);

  return function removeFromWindow() {
    iconEl.addEventListener("click", clickListener);
    tb.removeChild(iconEl);
  };
};

exports.show = function(win, uri) {
  let xpcomWin = getXpcomWindow(win);
  let iconEl = xpcomWin.document.getElementById(ICON_ID);
  iconEl.setAttribute("src", uri);
  iconEl.removeAttribute("collapsed");
};

exports.hide = function(win) {
  let xpcomWin = getXpcomWindow(win);
  let iconEl = xpcomWin.document.getElementById(ICON_ID);
  iconEl.setAttribute("collapsed", true);
};
