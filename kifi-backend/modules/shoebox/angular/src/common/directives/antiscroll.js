'use strict';

angular.module('antiscroll', ['kifi'])

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

        // Antiscroll doesn't refresh automatically if nodes are added to its container element.
        // This is an issue in Angular because this happens quite often. This bit below tries
        // to fix potential race condition issues. It's not 100%: if a scrollbar did exist,
        // then it won't refresh.
        element.on('mouseenter', function () {
          if (scope.scroller && (!scope.scroller.vertical || !scope.scroller.horizontal)) {
            scope.refreshScroll();
          }
        });


        scope.refreshScroll();

        // http://stackoverflow.com/questions/986937/how-can-i-get-the-browsers-scrollbar-sizes
        scope.width = 'calc(100% + ' + scrollbar.getAntiscrollWidth() + 'px)';

        scope.$on('refreshScroll', scope.refreshScroll);
      },
      template: '<div class="antiscroll-inner" ng-attr-style="width: {{width}}" ng-transclude></div>'
    };
  }
]);
