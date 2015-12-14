'use strict';

angular.module('kifi')

  .directive('kfKeepComment', ['$state', 'messageFormattingService', 'extensionLiaison', 'profileService', 'keepService', 'modalService',
    function ($state, messageFormattingService, extensionLiaison, profileService, keepService, modalService) {
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
          var isAdmin = profileService.isAdmin();
          $scope.canSeeCommentActions = $scope.me.id === $scope.comment.sentBy.id && isAdmin;
          $scope.showKeepPageLink = !$state.is('keepPage') && isAdmin;

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };


          $scope.commentActionItems = [
            {
              title: 'Delete',
              action: function() {
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
