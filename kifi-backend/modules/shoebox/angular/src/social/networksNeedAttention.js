'use strict';

angular.module('kifi')


.directive('kfNetworksNeedAttention', ['modalService', 'socialService',
  function (modalService, socialService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'social/networksNeedAttention.tpl.html',
      link: function (scope) {
        scope.networksNeedAttention = function () {
          return Object.keys(socialService.expiredTokens).length > 0;
        };
        scope.data = {};
        scope.doShow = function () {
          modalService.open({
            template: 'social/addNetworksModal.tpl.html'
          });
        };
      }
    };
  }
]);
