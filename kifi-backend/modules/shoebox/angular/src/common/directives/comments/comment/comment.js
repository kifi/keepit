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
        link: function (scope, element) {
          //var queryResult = element[0].querySelector('.kf-keep-comment-commenter-text');
          //var wrappedQueryResult = angular.element(queryResult);
          scope.commentParts = messageFormattingService.full(scope.comment.text);
          //wrappedQueryResult.append(messageFormattingService.full(scope.comment.text));

        }
      };
    }
  ]);
