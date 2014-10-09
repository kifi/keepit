'use strict';

angular.module('kifi')

.directive('kfKeepToLibraryWidget', [
  '$rootElement', '$compile', '$document', '$filter', '$templateCache', '$timeout', 'keyIndices',
  function ($rootElement, $compile, $document, $filter, $templateCache, $timeout, keyIndices) {
    return {
      restrict: 'A',
      /*
       * Relies on parent scope to have:
       *  libraries - an array of library objects to select from.
       *  selection - an object whose 'library' property will be the selected library.
       */
      link: function (scope, element/*, attrs*/) {
        //
        // Internal data.
        //
        var widget = null;
        var selectedIndex = 0;
        var searchInput = null;
        var libraryList = null;
        var justScrolled = false;
        var removeTimeout = null;
        var resetJustScrolledTimeout = null;


        //
        // Scope data.
        //
        scope.search = {};


        //
        // Internal methods.
        //
        function cancelTimeout() {
          $timeout.cancel(removeTimeout);
          $timeout.cancel(resetJustScrolledTimeout);
        }

        function clearSelection () {
          scope.libraries.forEach(function (library) {
            library.selected = false;
          });
        }

        function removeWidget() {
          clearSelection();
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

          // Clicked on a selection option? Also exit, but after a small timeout because
          // otherwise the widget disappears too quickly for the user to see a nice
          // highlight on their selection.
          if (angular.element(event.target).closest('.library-select-option').length) {
            removeTimeout = $timeout(removeWidget, 200);
            return;
          }
        }

        /**
         * If a user uses up-and-down arrows to select a library that is not visible,
         * scroll the list of libraries so that the selected library is visible.
         *
         * @param {number} selectedIndex - the index of the selected library in the library list.
         */
        function adjustScroll(selectedIndex) {

          /**
           * After we finish scrolling, we set a flag that a scroll has just happened so that
           * a mouseenter event on a library item that was triggered as a result of the scroll
           * would not result in that library item being selected. After a short amount of time,
           * set the flag to false so that mouseenter can function as normal.
           */
          function setJustScrolled() {
            justScrolled = true;
            resetJustScrolledTimeout = $timeout(function () {
              justScrolled = false;
            }, 200);
          }

          // Each library list item is 47px high, and we fit in 8 library list items within the
          // visible area. For a library item to be visible, it should be entirely within the
          // visible area (this means its top offset should be at least one library item height from
          // the visible bottom).

          var selectedLibraryTop = selectedIndex * 47;
          var visibleTop = libraryList.scrollTop();
          var visibleBottom = visibleTop + (8 * 47);

          if (selectedLibraryTop < visibleTop) {
            libraryList.scrollTop(selectedLibraryTop);
            setJustScrolled();
          } else if (selectedLibraryTop > (visibleBottom - 47)) {
            libraryList.scrollTop(selectedLibraryTop - (7 * 47));
            setJustScrolled();
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
          var top = element.offset().top - 100;
          var left = element.offset().left;
          widget.css({top: top + 'px', left: left + 'px'});

          // Set event handlers.
          $document.on('mousedown', onClick);

          // Initialize state.
          scope.search = {};
          selectedIndex = 0;
          scope.libraries[selectedIndex].selected = true;
          libraryList = widget.find('.library-select-list');
          searchInput = widget.find('.keep-to-library-search-input');

          // Focus on search input.
          searchInput.focus();
        };

        scope.onHover = function (library) {
          if (justScrolled) {
            justScrolled = false;
          } else {
            clearSelection();
            library.selected = true;
            scope.selection.library = library;
            selectedIndex = _.indexOf(scope.libraries, library);
          }
        };

        scope.onUnhover = function (library) {
          library.selected = false;
        };

        scope.processKeyEvent = function ($event) {
          function getNextIndex(index, direction) {
            var nextIndex = index + direction;
            return (nextIndex < 0 || nextIndex > scope.libraries.length - 1) ? index : nextIndex;
          }

          switch ($event.keyCode) {
            case keyIndices.KEY_UP:
              // Otherwise browser will move cursor to start of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, -1);
              scope.libraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_DOWN:
              // Otherwise browser will move cursor to end of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, 1);
              scope.libraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_ENTER:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              scope.selection.library = scope.libraries[selectedIndex];
              removeWidget();
              break;
            case keyIndices.KEY_ESC:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              removeWidget();
              break;
          }
        };


        //
        // Clean up.
        //
        scope.$on('$destroy', removeWidget);
      }
    };
  }
]);
