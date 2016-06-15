'use strict';

angular.module('kifi')

  .directive('kfActivityEvent', ['$state', 'messageFormattingService', 'extensionLiaison', 'profileService',
    function ($state, messageFormattingService, extensionLiaison, profileService) {
      return {
        restrict: 'A',
        scope: {
          keep: '=',
          activityEvent: '=kfActivityEvent',
          threadId: '=',
          getDeleteComment: '&deleteComment',
          editKeepNote: '='
        },
        templateUrl: 'common/directives/keepActivity/activityEvent/activityEvent.tpl.html',
        link: function ($scope) {
          $scope.me = profileService.me;
          $scope.showKeepPageLink = $scope.keep.path && !$state.is('keepPage');

          $scope.openLookHere = function(event) {
            event.preventDefault();
            extensionLiaison.openDeepLink($scope.keep.url, $scope.keep.discussion.locator);
          };

          $scope.activity = $scope.activityEvent.header || [{kind: 'user', text: $scope.activityEvent.sentBy.firstName}];
          $scope.body = messageFormattingService.processActivityEventElements(
            $scope.activityEvent.body || [{kind: 'text', text: $scope.activityEvent.text}], $scope.keep);

          var canDeleteMessage = $scope.activityEvent.kind === 'comment' && (
            $scope.me.id === ($scope.activityEvent.author && $scope.activityEvent.author.id) ||
            $scope.me.id === ($scope.keep.user && $scope.keep.user.id) ||
            $scope.me.id === ($scope.keep.library && $scope.keep.library.owner && $scope.keep.library.owner.id)
          );

          $scope.eventActionItems = [];

          if (canDeleteMessage) {
            $scope.eventActionItems.push({
              title: 'Delete',
              action: $scope.getDeleteComment()
            });
          }

          if ($scope.editKeepNote && $scope.activityEvent && $scope.activityEvent.kind === 'note') {
            $scope.eventActionItems.push({
              title: 'Edit Note',
              action: function (event) {
                $scope.editKeepNote(event, $scope.keep);
              }
            });
          }
        }
      };
    }
  ]);
