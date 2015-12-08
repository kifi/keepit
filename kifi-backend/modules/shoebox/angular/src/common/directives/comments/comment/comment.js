'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['messageFormattingService', 'extensionLiaison',
    function (messageFormattingService, extensionLiaison) {
      return {
        restrict: 'A',
        scope: {
          keep: '=',
          comment: '=',
          threadId: '='
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function ($scope) {
          $scope.commentParts = messageFormattingService.trimExtraSpaces(messageFormattingService.full($scope.comment.text));

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };
        }
      };
    }
  ]);
