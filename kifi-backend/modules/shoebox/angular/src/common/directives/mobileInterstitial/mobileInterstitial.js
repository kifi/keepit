'use strict';

angular.module('kifi')

.directive('kfMobileInterstitial', ['mobileOS',
  function(mobileOS) {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/mobileInterstitial/mobileInterstitial.tpl.html',
      scope: { },
      link: function($scope) {
        $scope.show = !document.cookie.replace(/(?:(?:^|.*;\s*)kfHideMobileInterstitial\s*\=\s*([^;]*).*$)|^.*$/, "$1") && (mobileOS === "iOS" || mobileOS === "Android")

        $scope.app_link = (mobileOS === 'iOS' ? 'https://itunes.apple.com/us/app/kifi-new-way-to-build-your/id740232575?mt=8' : 'https://play.google.com/store/apps/details?id=com.kifi');

        $scope.permanentlyHide = function() {
          $scope.show = false;
          document.cookie="kfHideMobileInterstitial=true";
        };
      }
    };
  }]
);
