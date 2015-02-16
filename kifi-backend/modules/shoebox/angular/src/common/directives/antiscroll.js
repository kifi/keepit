'use strict';

angular.module('antiscroll', ['kifi'])

// The parent of 'antiscroll-inner' should have 'position: relative; overflow: hidden'.
// The 'antiscroll-inner' element should have 'width: calc(100% + 30px)'.
// Children of 'antiscroll-inner' should have 'width: calc(100% - 30px)'.
.directive('antiscroll', [
  '$timeout',
  function ($timeout) {
    return {
      restrict: 'A',
      transclude: true,
      link: function (scope, element, attrs) {
        var options = attrs.antiscroll ? scope.$eval(attrs.antiscroll) : undefined;
        var scroller = element.antiscroll(options).data('antiscroll');
        var refresh = scroller.refresh.bind(scroller);

        $timeout(refresh);

        scope.$on('refreshScroll', function () {
          $timeout(refresh);
        });
      },

      template: '<div class="antiscroll-inner" ng-transclude></div>'
    };
  }
]);
