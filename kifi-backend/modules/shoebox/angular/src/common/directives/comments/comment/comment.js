'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['messageFormattingService', 'extensionLiaison', 'profileService',
    function (messageFormattingService, extensionLiaison, profileService) {
      return {
        restrict: 'A',
        scope: {
          keep: '=',
          comment: '=',
          threadId: '=',
          deleteComment: '&'
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function ($scope) {
          $scope.commentParts = messageFormattingService.trimExtraSpaces(messageFormattingService.full($scope.comment.text));
          $scope.me = profileService.me;
          $scope.canSeeCommentActions = profileService.isAdmin() && $scope.me.id === $scope.comment.sentBy.id ||
            $scope.me.id === $scope.keep.user.id || $scope.me.id === $scope.keep.library.owner.id;

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };


          $scope.commentActionItems = [
            {
              title: 'Delete',
              action: $scope.deleteComment()
            }
          ];
        }
      };
    }
  ]);
