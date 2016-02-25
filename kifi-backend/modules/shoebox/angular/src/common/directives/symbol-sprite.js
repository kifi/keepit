/**
 *  Angular directive to generate <svg> tags with proper <use> xlinks.
 *  Example of technique: https://css-tricks.com/svg-symbol-good-choice-icons/
 */
'use strict';
angular.module('kifi')

.directive('kfSymbolSprite', [
  '$window', '$q',
  function ($window, $q) {
    var MutationObserver = $window.MutationObserver;
    var symbolSpriteReady = getSymbolSpritePromise();

    function getSymbolSpritePromise() {
      var symbolSpriteContainer = angular.element('.symbol-sprite-container')[0];
      var deferred = $q.defer();
      var observer;

      if (MutationObserver && symbolSpriteContainer.children.length === 0) {
        observer = new MutationObserver(function () {
          deferred.resolve();
          observer.disconnect();
        });
        observer.observe(symbolSpriteContainer, { childList: true });
      } else {
        deferred.resolve();
      }

      return deferred.promise;
    }

    return {
      restrict: 'A',
      link: function (scope, element, attrs) {
        symbolSpriteReady
        .then(function () {
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
        });
      }
    };
  }
]);
