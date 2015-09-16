'use strict';

/* MESSAGE TICKER
 * ==============
 * Message ticker shows one message at a time at the top of the view port.
 * Each new message replaces its predecessor and restarts the clock on its
 * time before hiding.
 * 
 * Add this factory to a controller or directive by injecting 'messageTicker'
 * 
 * .directive('myDirective', ['messageTicker', function(messageTicker) { ... }]);
 *
 * The messageTicker function is now available to use. Usage instructions mirror
 * the "Status" component at http://getkickstart.com/docs/3.x/ui/notifications/
 * replacing k$.status for messageTicker.
 *
 * Currently, the message can be shown in yellow (default), green, and red.
 *
 * messageTicker({ text: 'error', type: 'yellow' });
 */

angular.module('kifi')

.factory('messageTicker', function() {

  this.debounceQueue = [];
  var messageTicker = function(opts) {
    var messageProps, messageBar, defaults, hideMessageBar, message, globalDebounce;

    defaults = {
      type: 'yellow',
      delay: 2000
    };

    globalDebounce = function(fn, id, delay, args, that) {
      delay = delay || 1000;
      that = that || this;
      args = args || [];
      if (typeof this.debounceQueue[id] !== 'object') {
        this.debounceQueue[id] = {};
      }
      if (typeof this.debounceQueue[id].debounceTimer !== 'undefined') {
        clearTimeout(this.debounceQueue[id].debounceTimer);
      }
      return this.debounceQueue[id] = {
        fn: fn,
        id: id,
        delay: delay,
        args: args,
        debounceTimer: setTimeout(function() {
          this.debounceQueue[id].fn.apply(that, this.debounceQueue[id].args);
          return this.debounceQueue[id] = void 0;
        }.bind(this), delay)
      };
    }.bind(this);

    messageProps = _.merge(defaults, opts);

    if (!document.querySelectorAll('#message_bar-message').length) {
      messageBar = document.createElement('div');
      messageBar.id = 'message_bar';
      messageBar.className = 'message_bar';
      messageBar.innerHTML = '<div class="message_bar-message" id="message_bar-message"></div>';
      document.body.appendChild(messageBar);
    }

    messageBar = document.querySelector('#message_bar');

    hideMessageBar = function() {
      messageBar.classList.add('hide');
      return setTimeout(function() {
        messageBar.classList.remove('hide');
        return messageBar.parentNode.removeChild(messageBar);
      }, 250);
    };

    if (messageProps.delay > 0) {
      globalDebounce(hideMessageBar, 'hideMessageBar', messageProps.delay, null, this);
    }
    message = document.querySelector('#message_bar-message');
    message.innerHTML = messageProps.text;
    return message.dataset.type = messageProps.type;
  }.bind(this);

  return messageTicker;
});
