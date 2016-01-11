'use strict';

angular.module('kifi')

.directive('kfKeepCardAttribution', [ '$state',
  function ($state) {
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
        scope.showKeepPageLink = scope.keep.path && !$state.is('keepPage');
      }
    };
  }
]);
