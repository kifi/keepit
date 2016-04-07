'use strict';

angular.module('kifi')

.directive('kfStructuredTextSegment', [
  'RecursionHelper',
  function (RecursionHelper) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/structuredText/structuredTextSegment.tpl.html',
      scope: {
        segment: '=kfStructuredTextSegment'
      },
      compile: function (element) {
        function link($scope) {
          $scope.isSegmentText = function (segment) {
            return !$scope.isSegmentLink(segment) && !$scope.isSegmentHover(segment);
          };

          $scope.isSegmentLink = function (segment) {
            return !!segment.url;
          };

          $scope.isSegmentHover = function (segment) {
            return !!segment.hover && segment.hover.length > 0;
          };
        }

        return RecursionHelper.compile(element, link);
      }
    };
  }
]);
