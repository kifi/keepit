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
        var attribution = keep && keep.sourceAttribution;
        var keeper = (attribution && attribution.kifi) || (keep && keep.user);
        if (keeper && messages) {
          scope.lastComment = messages.filter(function (message) {
            return message.sentBy && message.sentBy.id !== keeper.id;
          })[0];
        }
      }
    };
  }
]);
