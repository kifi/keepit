'use strict';

angular.module('kifi')

.directive('kfKeepCardAttribution', [ '$state', 'profileService',
  function ($state, profileService) {
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
        var isAdmin = (profileService.me.experiments || []).indexOf('admin') !== -1;
        scope.showKeepPageLink = !$state.is('keepPage') && isAdmin;
      }
    };
  }
]);
