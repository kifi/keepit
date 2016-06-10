'use strict';

angular.module('kifi')

.directive('kfTwitterSyncDialog', [
  '$window', '$analytics', 'modalService', 'net', 'profileService',
  function ($window, $analytics, modalService, net, profileService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {},
      templateUrl: 'twitter/twitterSyncDialog.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $analytics.eventTrack('user_viewed_pane', { type: 'twitterSyncDialog'});
        profileService.savePrefs({twitter_sync_promo: null});
        $scope.onClickSync = function () {
          net.twitterSync().then(function (res) {
            if (res && res.auth) {
              $window.location = res.auth;
            } else {
              kfModalCtrl.close();
              modalService.open({
                template: 'twitter/twitterSyncStatusDialogModal.tpl.html',
                scope: $scope
              });
            }
          })['catch'](modalService.openGenericErrorModal);
        };

        $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
