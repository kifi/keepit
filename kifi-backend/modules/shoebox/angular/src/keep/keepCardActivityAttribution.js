'use strict';

angular.module('kifi')

.directive('kfKeepCardActivityAttribution', [
  function () {
    return {
      scope: {
        keep: '=keep'
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/keepCardActivityAttribution.tpl.html',
      link: function (scope) {
        var keep = scope.keep;
        var messages = keep && keep.discussion && keep.discussion.messages;
        var author = keep && keep.author;
        if (author && messages) {
          scope.lastComment = messages.filter(function (message) {
            return message.sentBy && message.sentBy.id !== author.id;
          })[0];
        }
      }
    };
  }
]);
