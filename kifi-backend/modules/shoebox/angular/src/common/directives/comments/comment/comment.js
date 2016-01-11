'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['$state', 'messageFormattingService', 'extensionLiaison', 'profileService',
    function ($state, messageFormattingService, extensionLiaison, profileService) {
      return {
        restrict: 'A',
        scope: {
          keep: '=',
          comment: '=',
          threadId: '=',
          getDeleteComment: '&deleteComment'
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function ($scope) {
          $scope.commentParts = messageFormattingService.trimExtraSpaces(messageFormattingService.full($scope.comment.text));
          $scope.me = profileService.me;
          $scope.canSeeCommentActions = (
            $scope.me.id === ($scope.comment.sentBy && $scope.comment.sentBy.id) ||
            $scope.me.id === ($scope.keep.user && $scope.keep.user.id) ||
            $scope.me.id === ($scope.keep.library && $scope.keep.library.owner && $scope.keep.library.owner.id)
          );
          $scope.showKeepPageLink = $scope.keep.path && !$state.is('keepPage');

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };


          $scope.commentActionItems = [
            {
              title: 'Delete',
              action: $scope.getDeleteComment()
            }
          ];
        }
      };
    }
  ]);
