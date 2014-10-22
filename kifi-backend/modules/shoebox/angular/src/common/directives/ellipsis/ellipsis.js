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
      ellipsisSymbol: '@',
      maxNumLines: '='
    },
    compile: function(/*elem, attr, linker*/) {

      return function(scope, element, attributes) {
        var copyElement = element.clone();
        copyElement.appendTo(element.parent());
        copyElement.css('display', 'block');
        copyElement.css('position', 'absolute');
        copyElement.css('top', '-99999px');
        copyElement.css('left', '-99999px');
        copyElement.css('visibility', 'hidden');

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

            var maxNumLines =
              (typeof(attributes.maxNumLines) !== 'undefined') ?
                attributes.maxNumLines :
                1;
            if (currentNumLines <= maxNumLines) { // entire name fits
              element.html(scope.ngBind);
              return;
            }

            var ellipsisSymbol =
              (typeof(attributes.ellipsisSymbol) !== 'undefined') ?
                attributes.ellipsisSymbol :
                '&hellip;';
            var appendString =
              (typeof(scope.ellipsisAppend) !== 'undefined' && scope.ellipsisAppend !== '') ?
                ellipsisSymbol + '<span>' + scope.ellipsisAppend + '</span>' :
                ellipsisSymbol;

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
        function interval() {
          buildEllipsis();
          numIntervals++;
          if (numIntervals < 3) {
            $timeout(interval, 1000);
          }
        }
        $timeout(interval, 200);


        //
        // Watchers
        //
        // Execute ellipsis truncate on ngBind update
        scope.$watch('ngBind', buildEllipsis);

        // Execute ellipsis truncate on ngBind update
        scope.$watch('ellipsisAppend', buildEllipsis);

        // When window width or height changes - re-init truncation
        angular.element($window).bind('resize', buildEllipsis);

      };
    }
  };
}]);
