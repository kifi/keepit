'use strict';

angular.module('kifi.social', [])

.directive('kfSocialConnectNetworks', [
  function () {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'social/connectNetworks.tpl.html',
      link: function (/*scope, element, attrs*/) {

      }
    };
  }
]);

