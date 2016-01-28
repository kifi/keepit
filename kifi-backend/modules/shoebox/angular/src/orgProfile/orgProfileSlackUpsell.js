'use strict';

angular.module('kifi')

.directive('kfOrgProfileSlackUpsell', [
  '$window', '$rootScope', 'messageTicker', '$analytics', 'slackService',
  function ($window, $rootScope, messageTicker, $analytics, slackService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {
        getLibrary: '&library',
        getOrg: '&org'
      },
      templateUrl: 'orgProfile/orgProfileSlackUpsell.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $analytics.eventTrack('user_viewed_pane', { type: 'orgProfileSlackUpsell'});
        $scope.userLoggedIn = $rootScope.userLoggedIn;

        $scope.onClickedSynOnlyGeneral = function() {
          $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileSlackUpsell', action: 'syncGeneral' });
          slackService.getAddIntegrationLink($scope.getLibrary().id).then(function (resp) {
            if (resp.redirect) {
              $window.location = resp.redirect;
              kfModalCtrl.close();
            } else {
              messageTicker({ text: 'Oops, that didn’t work. Try again?', type: 'red' });
            }
          });
        };

        $scope.onClickedSyncAllSlackChannels = function() {
          var org = $scope.getOrg();
          $analytics.eventTrack('user_clicked_pane', { type: 'orgProfileSlackUpsell', action: 'syncAllChannels' });
          slackService.publicSync($scope.getOrg().id).then(function (resp) {
            if (resp.redirect) {
              $window.location = resp.redirect;
              kfModalCtrl.close();
            } else {
              messageTicker({ text: 'Oops, that didn’t work. Try again?', type: 'red' });
            }
          });
          kfModalCtrl.close();
        };

        $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
