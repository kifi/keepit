'use strict';

angular.module('kifi')


.directive('kfNetworksNeedAttention', ['socialService', '$rootScope',
  function (socialService, $rootScope) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'social/networksNeedAttention.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networksNeedAttention = function () {
          return Object.keys(socialService.expiredTokens).length > 0;
        };
        scope.data = {};
        scope.doShow = function () {
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };
      }
    };
  }
]);
