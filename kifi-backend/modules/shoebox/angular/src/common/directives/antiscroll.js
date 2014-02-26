'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      transclude: true,
      link: function (scope, element, attrs /*, ctrl, transclude*/ ) {
        var options;
        if (attrs.antiscroll) {
          options = scope.$eval(attrs.antiscroll);
        }
        scope.scroller = element.antiscroll(options).data('antiscroll');

        scope.refreshScroll = function () {
          return $timeout(function () {
            if (scope.scroller) {
              scope.scroller.refresh();
            }
          });
        };

        scope.refreshScroll();

        scope.$on('refreshScroll', scope.refreshScroll);
      },
      template: '<div class="antiscroll-inner" ng-transclude></div>'
    };
  }
]);
