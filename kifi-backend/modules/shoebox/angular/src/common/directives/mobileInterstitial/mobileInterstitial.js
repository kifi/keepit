'use strict';

angular.module('kifi')

.directive('kfMobileInterstitial',
  function() {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/mobileInterstitial/mobileInterstitial.tpl.html',
      scope: { show: '=' },
      link: function($scope) {
        if (!!document.cookie.replace(/(?:(?:^|.*;\s*)kfShowMobileInterstitial\s*\=\s*([^;]*).*$)|^.*$/, "$1")) {
          $scope.show = false;
        }

        $scope.permanentlyHide = function() {
          $scope.show = false;
          document.cookie="kfShowMobileInterstitial=true";
        };
      }
    };
  }
);
