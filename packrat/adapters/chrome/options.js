$(function() {
  'use strict';
  var main = chrome.extension.getBackgroundPage(), api = main.api, env = api.prefs.get('env');

  $(api.isPackaged() ? null : 'select').show()
  .find("[value=" + env + "]").prop("selected", true).end()
  .change(function() {
    api.prefs.set('env', this.value);
    chrome.runtime.reload();
  });
});
