/**
 *  Angular directive to truncate multi-line text to visible height
 *  inputs:
 *  (required) ng-bind
 *  (optional) ellipsisAppend - string to append at end of truncated text
 *
 *  how to use:
 *  <p data-ellipsis data-ng-bind="longName"></p>
 *  <p data-ellipsis data-ng-bind="longName" data-ellipsis-append="read more"></p>
 *
 */
'use strict';
angular.module('kifi')

.directive('kfEllipsis', ['$timeout', '$window', function ($timeout, $window) {

  return {
    restrict :'A',
    scope: {
      fullText: '=',
      ellipsisAppend: '@',
      maxNumLines: '=',
      numReloads: '='
    },
    compile: function () {

      return function (scope, element) {
        var copyElement = element.clone()
          .css({
            'display': 'block',
            'position': 'absolute',
            'top': '-99999px',
            'left': '-99999px',
            'visibility': 'hidden'
          })
          .appendTo(element.parent());

        var lastWindowHeight = 0;
        var lastWindowWidth = 0;

        function buildEllipsis() {
          if (typeof scope.fullText === 'string') {
            copyElement.css('width', element.width());

            // measure height of one line
            copyElement.text('x');
            var heightPerLine = copyElement.height();

            copyElement.text(scope.fullText);
            var currentHeight = copyElement.height();
            var currentNumLines = currentHeight / heightPerLine;
            var maxIndex = scope.fullText.length;
            var maxNumLines = scope.maxNumLines || currentNumLines;

            if (currentHeight === 0) {
              element.text(scope.fullText);
              return;
            }
            if (currentNumLines <= maxNumLines) { // entire name fits
              element.text(scope.fullText);
              return;
            }

            var appendString = typeof scope.ellipsisAppend === 'string' && scope.ellipsisAppend || '\u2026';

            // binary search for correct maxIndex
            var hi = scope.fullText.length;
            var lo = 0;

            while (hi - lo > 1) {
              maxIndex = lo + Math.floor((hi - lo)/2);
              copyElement.text(scope.fullText.substr(0, maxIndex) + appendString);
              currentHeight = copyElement.height();
              currentNumLines = currentHeight / heightPerLine;

              if (currentNumLines > maxNumLines) {
                hi = maxIndex;
              } else {
                lo = maxIndex;
              }
            }
            maxIndex = lo;
            element.text(scope.fullText.substr(0, maxIndex) + appendString);
          }
        }

        // font-changes will affect sizing, but ng-bind applies first!
        // todo (aaron): find something better than this hack
        var numIntervals = 0;
        var maxIntervals = scope.numReloads || 0;
        $timeout(function intervalRebuild() {
          buildEllipsis();
          numIntervals++;
          if (numIntervals < maxIntervals) {
            $timeout(intervalRebuild, 1000);
          }
        }, 200);

        //
        // Watchers
        //
        // Execute ellipsis truncate on fullText update
        scope.$watch('fullText', buildEllipsis);

        // Execute ellipsis truncate on fullText update
        scope.$watch('ellipsisAppend', buildEllipsis);

        // When window width or height changes - re-init truncation
        angular.element($window).bind('resize', _.debounce(function() {
          if ($window.innerWidth !== lastWindowWidth || $window.innerHeight !== lastWindowHeight) {
            buildEllipsis();
          }
          lastWindowWidth = $window.innerWidth;
          lastWindowHeight = $window.innerHeight;
        }, 200));

      };
    }
  };
}]);
