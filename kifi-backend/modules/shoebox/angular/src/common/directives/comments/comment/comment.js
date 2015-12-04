'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['messageFormattingService',
    function (messageFormattingService) {
      return {
        restrict: 'A',
        scope: {
          comment: '='
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function (scope) {
          scope.commentParts = messageFormattingService.full(scope.comment.text);
        }
      };
    }
  ]);
