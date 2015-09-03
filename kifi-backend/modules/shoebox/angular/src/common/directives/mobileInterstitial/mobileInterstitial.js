'use strict';

angular.module('kifi')

.directive('kfMobileInterstitial',
  function() {
    return {
      restrict: 'A',
      templateUrl: 'common/directives/mobileInterstitial/mobileInterstitial.tpl.html',
      scope: { show: '=' },
      replace: true
    };
  }
);
