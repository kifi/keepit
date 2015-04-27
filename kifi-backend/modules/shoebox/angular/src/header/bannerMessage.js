'use strict';

angular.module('kifi')

.directive('kfBannerMessage', [
  '$timeout', 'undoService',
  function ($timeout, undoService) {
    return {
      restrict: 'A',
      scope: {
        message: '=kfBannerMessage'
      },
      templateUrl: 'header/bannerMessage.tpl.html',
      link: function (scope) {
        if (scope.message) {
          $timeout(function () {
            scope.message = null;
          }, 5000);
        }

        scope.$watch(function () {
          return undoService.getMessage();
        }, function (val) {
          scope.undoMessage = val;
        });

        scope.undo = undoService.undo;
      }
    };
  }
]);
