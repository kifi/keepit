'use strict';

angular.module('kifi')

.directive('kfMobileInterstitial', ['mobileOS',
  function(mobileOS) {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/mobileInterstitial/mobileInterstitial.tpl.html',
      scope: { },
      link: function($scope) {
        var cookieRegEx = new RegExp(/(?:(?:^|.*;\s*)kfHideMobileInterstitial\s*\=\s*([^;]*).*$)|^.*$/);
        $scope.show = 
          !document.cookie.replace(cookieRegEx, '$1') && (mobileOS === 'iOS' || mobileOS === 'Android');

        var appleLink = 'https://itunes.apple.com/us/app/kifi-new-way-to-build-your/id740232575?mt=8';
        var androidLink = 'https://play.google.com/store/apps/details?id=com.kifi';
        $scope.app_link =
          (mobileOS === 'iOS' ? appleLink : androidLink);

        $scope.permanentlyHide = function() {
          $scope.show = false;
          document.cookie='kfHideMobileInterstitial=true';
        };
      }
    };
  }]
);
