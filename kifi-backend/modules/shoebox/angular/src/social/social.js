'use strict';

angular.module('kifi.social', ['kifi.socialService'])

.directive('kfSocialConnectNetworks', [
  'socialService',
  function (socialService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'social/connectNetworks.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.data = scope.data || {};
        scope.data.show = true;
        scope.facebook = socialService.facebook;
        scope.linkedin = socialService.linkedin;
        scope.gmail = socialService.gmail;


        socialService.refresh();

      }
    };
  }
]);

