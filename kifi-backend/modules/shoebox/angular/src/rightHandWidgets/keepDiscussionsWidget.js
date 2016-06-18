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
          scope.latestActivityText = {};
          scope.extraRecipients = {};
          if(scope.keeps) {
            scope.keeps.forEach(function (keep) {
              scope.latestActivityText[keep.id] = keep.activity.latestEvent &&
                messageFormattingService.activityEventToPlainText(keep.activity.latestEvent);
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
