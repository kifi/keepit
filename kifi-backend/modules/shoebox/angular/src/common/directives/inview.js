'use strict';

angular.module('kifi')

.directive('kfInview', [
  '$window',
  function ($window) {
    // Shamelessly stolen from https://remysharp.com/2009/01/26/element-in-view-event-plugin
    // and adapted for use in angular
    var SCROLL_DEBOUNCE_INTERVAL = 50;
    var document = $window.document;
    var elementList = [];

    function getViewportScroll() {
      return document.documentElement.scrollTop || document.body.scrollTop;
    }

    function _onScroll() {
      var viewportHeight = $window.innerHeight;
      var scrollTop = getViewportScroll();

      elementList.forEach(function ($el) {
        var top = $el.offset().top;
        var height = $el.height();
        var inview = $el.data('inview') || false;

        if (scrollTop > (top + height) || scrollTop + viewportHeight < top) {
          if (inview) {
            $el.data('inview', false);
            $el.trigger('inview', [ false ]);
          }
        } else if (scrollTop < (top + height)) {
          if (!inview) {
            $el.data('inview', true);
            $el.trigger('inview', [ true ]);
          }
        }
      });
    }
    var onScroll = _.debounce(_onScroll, SCROLL_DEBOUNCE_INTERVAL);

    function registerInviewChecking(element) {
      if (elementList.length === 0) {
        $window.addEventListener('scroll', onScroll);
      }

      elementList.push(element);
    }

    function deregisterInviewChecking(element) {
      var index = elementList.indexOf(element);
      if (index !== -1) {
        elementList.splice(index, 1);
      }

      if (elementList.length === 0) {
        // HACK(carlos): Firefox bug? It will enter this block even when elementList.length > 0
        // Removing an already removed listener is a noop, so it's nbd, but it's weird.
        $window.removeEventListener('scroll', onScroll);
      }
    }

    return {
      restrict: 'A',
      link: function ($scope, element, attr) {
        registerInviewChecking(element);

        element.on('inview', function () {
          var fn = $scope.$eval(attr.kfInview);
          if (fn && typeof fn === 'function') {
            fn.apply(this, arguments);
          }
        });

        $scope.$on('$destroy', function () {
          deregisterInviewChecking(element);
          element.off('inview');
        });

        // Schedule a check after the initial display of the element
        $scope.$evalAsync(function () {
          onScroll();
        });
      }
    };
  }
]);
