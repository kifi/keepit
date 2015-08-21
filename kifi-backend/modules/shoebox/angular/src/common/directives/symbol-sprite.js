/**
 *  Angular directive to generate <svg> tags with proper <use> xlinks.
 *  Example of technique: https://css-tricks.com/svg-symbol-good-choice-icons/
 */
'use strict';
angular.module('kifi')

.directive('kfSymbolSprite', [function () {
  return {
    restrict :'A',
    compile: function () {
      return function (scope, element, attrs) {
        var svgElement = element[0];

        var paths = angular.element('symbol#' + attrs.icon);
        svgElement.innerHTML = paths.html();

        var className = svgElement.getAttribute('class') || '';
        svgElement.setAttribute('class', className + (className ? ' ' : '') + 'symbol-sprite');
        svgElement.setAttribute('viewBox', '0 0 512 512');
      };
    }
  };
}]);
