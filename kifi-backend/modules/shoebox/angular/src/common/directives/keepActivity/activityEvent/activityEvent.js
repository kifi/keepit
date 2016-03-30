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

          function processElements(elements) {
            var processedElements = [];
            if (elements) {
              elements.forEach(function (element) {
                if (element.kind === 'text') {
                  var parts = messageFormattingService.trimExtraSpaces(messageFormattingService.full(element.text));
                  parts.forEach(function (part) {
                    if (part.type === 'LOOK_HERE' && $scope.keep) {
                      part.url = $scope.keep.url;
                      part.locator = '/messages/' + $scope.keep.pubId;
                    }
                    processedElements.push(part);
                  });
                } else {
                  processedElements.push(element);
                }
              });
            }
            return processedElements;
          }

          $scope.activity = $scope.activityEvent.header || [{kind: 'user', text: $scope.activityEvent.sentBy.firstName}];
          $scope.body = processElements($scope.activityEvent.body || [{kind: 'text', text: $scope.activityEvent.text}]);

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

          if ($scope.editKeepNote && $scope.activityEvent && $scope.activityEvent.kind === 'initial') {
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
