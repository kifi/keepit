'use strict';

angular.module('kifi')

.directive('kfKeepCardAttribution', [ 'profileService',
  function (profileService) {
    return {
      scope: {
        keep: '=keep',
        showLibraryAttribution: '=',
        isFirstItem: '='
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'keep/keepCardAttribution.tpl.html',
      link: function (scope) {
        scope.isAdmin = (profileService.me.experiments || []).indexOf('admin') !== -1;
      }
    };
  }
]);
