/**
 *  Angular directive to truncate multi-line text to visible height
 *  inputs:
 *  (required) ng-bind
 *  (optional) ellipsisAppend - string to append at end of truncated text
 *  (optional) ellipsisSymbol - string to use as ellipsis - default is '...'
 *
 *  how to use:
 *  <p data-ellipsis data-ng-bind="longName"></p>
 *  <p data-ellipsis data-ng-bind="longName" data-ellipsis-symbol="---"></p>
 *  <p data-ellipsis data-ng-bind="longName" data-ellipsis-append="read more"></p>
 *
 */
'use strict';
angular.module('kifi')

.directive('kfEllipsis', ['$timeout', '$window', function($timeout, $window) {

  return {
    restrict :'A',
    scope: {
      ngBind: '=',
      ellipsisAppend: '@',
      maxNumLines: '=',
      numReloads: '='
    },
    compile: function(/*elem, attr, linker*/) {

      return function(scope, element) {
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
          if (typeof(scope.ngBind) !== 'undefined') {
            copyElement.css('width', element.width());

            // measure height of one line
            copyElement.html('x');
            var heightPerLine = copyElement.height();

            copyElement.html(scope.ngBind);
            var currentHeight = copyElement.height();
            var currentNumLines = currentHeight / heightPerLine;
            var maxIndex = scope.ngBind.length;
            var maxNumLines = scope.maxNumLines || currentNumLines;

            if (currentHeight === 0) {
              element.html(scope.ngBind);
              return;
            }
            if (currentNumLines <= maxNumLines) { // entire name fits
              element.html(scope.ngBind);
              return;
            }

            var appendString =
              (typeof(scope.ellipsisAppend) !== 'undefined' && scope.ellipsisAppend !== '') ?
                scope.ellipsisAppend :
                '<span>&hellip;</span>';

            // binary search for correct maxIndex
            var hi = scope.ngBind.length;
            var lo = 0;

            while (hi - lo > 1) {
              maxIndex = lo + Math.floor((hi - lo)/2);
              copyElement.html(scope.ngBind.substr(0, maxIndex) + appendString);
              currentHeight = copyElement.height();
              currentNumLines = currentHeight / heightPerLine;

              if (currentNumLines > maxNumLines) {
                hi = maxIndex;
              } else {
                lo = maxIndex;
              }
            }
            maxIndex = lo;
            element.html(scope.ngBind.substr(0, maxIndex) + appendString);
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
        // Execute ellipsis truncate on ngBind update
        scope.$watch('ngBind', buildEllipsis);

        // Execute ellipsis truncate on ngBind update
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
