'use strict';

angular.module('kifi')


  .directive('kfKeepCardAttribution', [
    function () {
      return {
        scope: {
          keep: '=keep',
          showLibraryAttribution: '=',
          isFirstItem: '='
        },
        replace: true,
        restrict: 'A',
        templateUrl: 'keep/keepCardAttribution.tpl.html',
        link: function () {
        }
      };
    }
  ]);
