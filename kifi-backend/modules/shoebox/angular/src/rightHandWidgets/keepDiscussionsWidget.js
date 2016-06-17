'use strict';

angular.module('kifi')

.directive('kfKeepDiscussionsWidget', [
  '$location', 'messageFormattingService',
  function ($location, messageFormattingService) {
    return {
      restrict: 'A',
      scope: {
        keeps: '=keeps'
      },
      templateUrl: 'rightHandWidgets/keepDiscussionsWidget.tpl.html',
      link: function (scope) {
        scope.maxDiscussionRecipientsPerType = 2;
        scope.onClickDiscussion = function(keep) {
          if (keep.path) {
            $location.path(keep.path);
          }
        };
        scope.$watch('keeps', function () {
          scope.latestActivityElements = {};
          scope.extraRecipients = {};
          if(scope.keeps) {
            scope.keeps.forEach(function (keep) {
              scope.latestActivityElements[keep.id] = keep.activity && keep.activity.latestEvent &&
                messageFormattingService.processActivityEventElements(keep.activity.latestEvent.body);

              var extraLibraries = _.drop(keep.recipients.libraries || [], scope.maxDiscussionRecipientsPerType);
              var extraUsers = _.drop(keep.recipients.users || [], scope.maxDiscussionRecipientsPerType);
              var extraEmails = _.drop(keep.recipients.emails || [], scope.maxDiscussionRecipientsPerType);
              scope.extraRecipients[keep.id] = {
                libraries: extraLibraries,
                users: extraUsers,
                emails: extraEmails,
                count: extraLibraries.length + extraUsers.length + extraEmails.length
              };
            });
          }
        });
      }
    };
  }
]);
