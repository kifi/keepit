/**
 *  Angular directive to generate <svg> tags with proper <use> xlinks.
 *  Example of technique: https://css-tricks.com/svg-symbol-good-choice-icons/
 */
'use strict';
angular.module('kifi')

.directive('kfSymbolSprite', [
  '$window',
  function ($window) {
    var MutationObserver = $window.MutationObserver;

    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        function renderSymbolSprite() {
          var paths = angular.element('symbol#' + attrs.icon);
          var path = paths.children('g').clone()[0];

          var svgElement;
          var className;

          if (path) {
            svgElement = element[0];
            className = svgElement.getAttribute('class') || '';

            svgElement.appendChild(path);
            svgElement.setAttribute('class', className + (className ? ' ' : '') + 'symbol-sprite');
            svgElement.setAttribute('viewBox', '0 0 512 512');
          } else {
            element.remove();
          }
        }

        var symbolSpriteContainer = angular.element('.symbol-sprite-container')[0];
        var observer;
        if (symbolSpriteContainer.children.length === 0) {
          observer = new MutationObserver(renderSymbolSprite);
          observer.observe(symbolSpriteContainer, { childList: true });
        } else {
          renderSymbolSprite();
        }
      }
    };
  }
]);
