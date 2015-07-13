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
        element[0].innerHTML = '<use xlink:href="#' + attrs.icon + '" />';
        element[0].className += ('symbol-sprite');
      };
    }
  };
}]);
