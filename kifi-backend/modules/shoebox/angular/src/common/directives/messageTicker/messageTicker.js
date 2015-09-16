'use strict';

angular.module('kifi')

.factory('messageTicker', function() {
  var messageTicker = function() {
    var status, statusBar, defaults, hideStatusBar, statusMessage;

    defaults = {
      type: 'yellow',
      delay: 2000
    };

    status = _.merge(defaults, opts);

    if (!document.querySelectorAll('#message_ticker').length) {
      statusBar = document.createElement('div');
      statusBar.id = 'status_bar';
      statusBar.className = 'status_bar';
      statusBar.innerHTML = '<div class="status_bar-status" id="status-bar-status"></div>';
      document.body.appendChild(statusBar);
    }

    statusBar = document.querySelector('#status_bar');
    hideStatusBar = function() {
      statusBar.classList.add('hide');
      return setTimeout(function() {
        statusBar.classList.remove('hide');
        return statusBar.parentNode.removeChild(statusBar);
      }, 250);
    };

    if (status.delay > 0) {
      _.debounce(hideStatusBar, status.delay);
    }
    statusMessage = document.querySelector('#status_bar-status');
    statusMessage.innerHTML = status.text;
    return statusMessage.dataset.type = status.type;
  }
  return messageTicker;
});
