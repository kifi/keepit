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
    restrict  : 'A',
    scope   : {
      ngBind        : '=',
      ellipsisAppend    : '@',
      ellipsisSymbol    : '@'
    },
    compile : function(/*elem, attr, linker*/) {

      return function(scope, element, attributes) {
        /* Window Resize Variables */
          attributes.lastWindowResizeTime = 0;
          attributes.lastWindowResizeWidth = 0;
          attributes.lastWindowResizeHeight = 0;
          attributes.lastWindowTimeoutEvent = null;

        function buildEllipsis() {
          if (typeof(scope.ngBind) !== 'undefined') {
            element.html(scope.ngBind);
            var heightPerLine = 30;
            var currentHeight = element.height();
            var currentNumLines = currentHeight / heightPerLine;
            var maxIndex = scope.ngBind.length;

            if (currentNumLines <= 2) { // entire name fits
              return;
            }

            var ellipsisSymbol =
              (typeof(attributes.ellipsisSymbol) !== 'undefined') ?
                attributes.ellipsisSymbol : '&hellip;';
            var appendString =
              (typeof(scope.ellipsisAppend) !== 'undefined' && scope.ellipsisAppend !== '') ?
                ellipsisSymbol + '<span>' + scope.ellipsisAppend + '</span>' : ellipsisSymbol;

            // binary search for correct maxIndex
            var hi = scope.ngBind.length;
            var lo = scope.ngBind.length / 2;

            while (hi - lo > 1) {
              maxIndex = lo + Math.floor((hi - lo)/2);
              element.html(scope.ngBind.substr(0, maxIndex) + appendString);
              currentHeight = element.height();
              currentNumLines = currentHeight / heightPerLine;

              if (currentNumLines > 2) {
                hi = maxIndex;
              } else {
                lo = maxIndex;
              }
            }
            maxIndex = lo;
            element.html(scope.ngBind.substr(0, maxIndex) + appendString);
          }
        }

         /**
        * Watchers
        */
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

           /**
          * Execute ellipsis truncate on ngBind update
          */
          scope.$watch('ngBind', function () {
            buildEllipsis();
          });

           /**
          * Execute ellipsis truncate on ngBind update
          */
          scope.$watch('ellipsisAppend', function () {
            buildEllipsis();
          });

           /**
          * When window width or height changes - re-init truncation
          */
          angular.element($window).bind('resize', function () {
            $timeout.cancel(attributes.lastWindowTimeoutEvent);

            attributes.lastWindowTimeoutEvent = $timeout(function() {
              if (attributes.lastWindowResizeWidth !== window.innerWidth || attributes.lastWindowResizeHeight !== window.innerHeight) {
                buildEllipsis();
              }

              attributes.lastWindowResizeWidth = window.innerWidth;
              attributes.lastWindowResizeHeight = window.innerHeight;
            }, 75);
          });


      };
    }
  };
}]);