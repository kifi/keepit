'use strict';

angular.module('kifi.social', [])

.directive('kfSocialConnectNetworks', [
  '$document',
  function ($document) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'social/connectNetworks.tpl.html',
      link: function (scope, element/*, attrs*/) {

      }
    };
  }
]);

