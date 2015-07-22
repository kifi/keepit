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

        // It's /very/ important to use createElementNS, setAttributeNS, etc. for SVGs.
        var useElement = document.createElementNS('http://www.w3.org/2000/svg', 'use');
        useElement.setAttributeNS('http://www.w3.org/1999/xlink', 'href', '#' + attrs.icon);

        var className = svgElement.getAttribute('class') || '';
        svgElement.setAttribute('class', className + (className ? ' ' : '') + 'symbol-sprite');
        svgElement.appendChild(useElement);
      };
    }
  };
}]);
