'use strict';

angular.module('kifi')

.directive('kfKeepToLibraryWidget', [
  '$rootElement', '$compile', '$document', '$filter', '$templateCache', '$timeout',
  function ($rootElement, $compile, $document, $filter, $templateCache, $timeout) {
    return {
      restrict: 'A',
      /*
       * Relies on parent scope to have on its scope:
       *  libraries - an array of library objects to select from.
       *  selection - an object whose 'library' property will be the selected library.
       */
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var widget = null;
        var timeout = null;


        //
        // Scope data.
        //
        scope.search = {};


        //
        // Internal methods.
        //
        function cancelTimeout() {
          $timeout.cancel(timeout);
        }

        function removeWidget() {
          $document.off('mousedown', onClick);
          cancelTimeout();

          if (widget) {
            widget.remove();
          }
        }

        function onClick(event) {
          // Clicked outside widget? Exit.
          if (!angular.element(event.target).closest('.keep-to-library-widget').length) {
            scope.$apply(removeWidget);
            return;
          }

          // Clicked on a selection option? Remember the option and exit.
          if (angular.element(event.target).closest('.library-select-option').length) {
            // Using timeout because therwise the widget disappears too quickly and
            // the user doesn't get to see the selected library highlighted.
            $timeout(removeWidget, 200);
            return;
          }
        }


        //
        // Scope methods.
        //
        scope.showWidget = function () {
          // Create widget.
          widget = angular.element($templateCache.get('keep/keepToLibraryWidget.tpl.html'));
          $rootElement.find('html').append(widget);
          $compile(widget)(scope);

          // Set position.
          var top = element.offset().top;
          var left = element.offset().left;
          widget.css({top: top + 'px', left: left + 'px'});

          // Clear search test.
          scope.search = {};

          // Set event handlers.
          $document.on('mousedown', onClick);
        };

        scope.selectLibrary = function (library) {
          scope.selection.library = library;
        };


        //
        // Clean up.
        //
        scope.$on('$destroy', removeWidget);
      }
    };
  }
]);
