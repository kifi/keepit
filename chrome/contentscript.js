!function() {
  function log() {
    console.log.apply(console, Array.prototype.concat.apply(["[kifi][" + new Date().getTime() + "] "], arguments));
  }

  log("content script starting");
  if (window !== top) {
    log("should run only on top window");
    return;
  }
  var href = document.location.href;
  if (href.match(/^chrome-devtools:/)) return;
  log("host is " + document.location.host);

  /*$(function() {
    chrome.extension.sendRequest({
      "type": "set_page_icon",
      "is_kept": false
    });
  });*/ // edge case bug. Will fix.

  chrome.extension.sendRequest({type: "init_page", location: href}, function(response) {
    log("init_page response:", response);
  });
}();
