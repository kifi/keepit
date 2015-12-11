'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['messageFormattingService', 'extensionLiaison', 'profileService', 'keepService', 'modalService',
    function (messageFormattingService, extensionLiaison, profileService, keepService, modalService) {
      return {
        restrict: 'A',
        scope: {
          keep: '=',
          comment: '=',
          threadId: '='
        },
        templateUrl: 'common/directives/comments/comment/comment.tpl.html',
        link: function ($scope, element) {
          $scope.commentParts = messageFormattingService.trimExtraSpaces(messageFormattingService.full($scope.comment.text));
          $scope.me = profileService.me;
          $scope.canSeeCommentActions = $scope.me.id === $scope.comment.sentBy.id && profileService.isAdmin();

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };


          $scope.commentActionItems = [
            {
              title: 'Delete',
              action: function(event) {
                keepService.deleteMessageFromKeepDiscussion($scope.keep.pubId, $scope.comment.id).then(function () {
                  element.remove();
                })['catch'](modalService.openGenericErrorModal);
              }
            }
          ];
        }
      };
    }
  ]);
