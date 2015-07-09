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
        var file = '/dist/symbol-sprite.svg';
        element[0].innerHTML = '<use xlink:href="' + file + '#' + attrs.icon + '" />';
        element[0].classList.add('symbol-sprite');
      };
    }
  };
}]);
