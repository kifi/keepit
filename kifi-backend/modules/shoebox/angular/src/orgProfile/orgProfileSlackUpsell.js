'use strict';

angular.module('kifi')

.directive('kfOrgProfileSlackUpsell', [
  '$window', '$rootScope', 'messageTicker', '$analytics', 'orgProfileService', 'slackService', 'installService',
  function ($window, $rootScope, messageTicker, $analytics, orgProfileService, slackService, installService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {
        getLibrary: '&library',
        getOrg: '&org'
      },
      templateUrl: 'orgProfile/orgProfileSlackUpsell.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $scope.userLoggedIn = $rootScope.userLoggedIn;

        $scope.onClickedSyncAllSlackChannels = function() {
          orgProfileService.trackEvent('user_clicked_page', $scope.getOrg(), { type: 'orgProfileSlackUpsell', action: 'clickedSlackSync' });
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

        $scope.cancel = function() {
          orgProfileService.trackEvent('user_clicked_page', $scope.getOrg(), { type: 'orgProfileSlackUpsell', action: 'clickedClose' });
          if (!installService.installedVersion && installService.canInstall) {
            $window.location = '/install';
          }
        };

        orgProfileService.trackEvent('user_viewed_page', $scope.getOrg(), { type: 'orgProfileSlackUpsell' });
      }
    };
  }
]);
