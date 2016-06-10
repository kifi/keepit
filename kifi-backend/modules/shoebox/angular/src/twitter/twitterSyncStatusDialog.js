'use strict';

angular.module('kifi')

.directive('kfTwitterSyncStatusDialog', [
  '$window', '$analytics', 'profileService',
  function ($window, $analytics, profileService) {
    return {
      restrict: 'A',
      require: '^kfModal',
      scope: {},
      templateUrl: 'twitter/twitterSyncStatusDialog.tpl.html',
      link: function ($scope, element, attrs, kfModalCtrl) {
        $analytics.eventTrack('user_viewed_pane', { type: 'twitterSyncStatusDialog'});
        profileService.savePrefs({twitter_sync_promo: null});
        $scope.onClickOkay = $scope.close = function() {
          kfModalCtrl.close();
        };
      }
    };
  }
]);
