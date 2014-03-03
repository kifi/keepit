'use strict';

angular.module('antiscroll', ['kifi.scrollbar'])

.directive('antiscroll', [
  '$timeout', 'scrollbar',
  function ($timeout, scrollbar) {
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

        // http://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
        scope.width = 'calc(100% + ' + scrollbar.getAntiscrollWidth() + 'px)';

        scope.$on('refreshScroll', scope.refreshScroll);
      },
      template: '<div class="antiscroll-inner" ng-attr-style="width: {{width}}" ng-transclude></div>'
    };
  }
]);
