'use strict';

angular.module('kifi')

.directive('kfKeepToLibraryWidget', [
  '$rootElement', '$compile', '$document', '$filter', '$rootScope', '$templateCache', '$timeout', '$window',
  'keyIndices', 'libraryService', 'util',
  function ($rootElement, $compile, $document, $filter, $rootScope, $templateCache, $timeout, $window,
    keyIndices, libraryService, util) {
    return {
      restrict: 'A',
      /*
       * Relies on parent scope to have:
       *  libraries - an array of library objects to select from.
       *  librarySelection - an object whose 'library' property will be the selected library.
       *
       *  Optional properties on parent scope:
       *   excludeLibraries - an array of libraries to exclude from libraries when populating the widget.
       *   keptToLibraries - an array of library objects that are already keeping the keep.
       *   clickAction() - a function that can be called once a library is selected;
       *                   called with the element that this widget is on.
       *   libSelectTopOffset - amount to shift up relative to the element that has this directive as an attribute.
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
        var newLibraryNameInput = null;
        var submitting = false;


        //
        // Scope data.
        //
        scope.search = {};
        scope.showCreate = false;
        scope.newLibrary = {};
        scope.widgetLibraries = [];
        scope.excludeLibraries = scope.excludeLibraries || [];
        scope.keptToLibraries = scope.keptToLibraries || [];


        //
        // Internal methods.
        //
        function cancelTimeout() {
          $timeout.cancel(removeTimeout);
          $timeout.cancel(resetJustScrolledTimeout);
        }

        function clearSelection () {
          scope.widgetLibraries.forEach(function (library) {
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
            if (_.isFunction(scope.clickAction)) {
              scope.clickAction(element);
            }
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
          // By default, if there is enough room at the bottom, drop the widget down.
          // If there isn't enough room, scoot up just enough to fit the widget.
          // If scope.libSelectTopOffset is set, that overrides the default position.
          widget.hide();
          var scrollTop = angular.element($window).scrollTop();
          var elementOffset = element.offset().top;
          var distanceFromBottom = $window.innerHeight - elementOffset + scrollTop;

          // Wait for Angular to render the widget so we can grab its height.
          $timeout(function () {
            var widgetHeight = widget.height();

            // If there is enough room at the bottom, drop the widget down.
            // Otherwise, scoot up just enough to fit the widget.
            var widgetOffset = (distanceFromBottom - widgetHeight > 0) ? 0 : widgetHeight - distanceFromBottom;

            var top = element.offset().top - (scope.libSelectTopOffset || widgetOffset);
            var left = element.offset().left;
            widget.css({top: top + 'px', left: left + 'px'});
            widget.show();
          }, 0);

          // Set event handlers.
          $document.on('mousedown', onClick);

          // Initialize state.
          scope.search = {};
          selectedIndex = 0;
          libraryList = widget.find('.library-select-list');
          searchInput = widget.find('.keep-to-library-search-input');
          scope.showCreate = false;
          scope.newLibrary = {};
          newLibraryNameInput = widget.find('.keep-to-library-create-name-input');

          libraryService.fetchLibrarySummaries(false).then(function (data) {
            var libraries = _.filter(data.libraries, { access: 'owner' });

            libraries = _.filter(libraries, function (library) {
              return !_.find(scope.excludeLibraries, { 'id': library.id });
            });

            libraries.forEach(function (library) {
              library.keptTo = false;
              if (scope.keptToLibraries && _.find(scope.keptToLibraries, { 'id': library.id })) {
                library.keptTo = true;
              }
            });

            libraries[selectedIndex].selected = true;
            scope.widgetLibraries = libraries;
          });

          // Focus on search input.
          searchInput.focus();
        };

        scope.onHover = function (library) {
          if (justScrolled) {
            justScrolled = false;
          } else {
            clearSelection();
            library.selected = true;
            scope.librarySelection.library = library;
            selectedIndex = _.indexOf(scope.widgetLibraries, library);
          }
        };

        scope.onUnhover = function (library) {
          library.selected = false;
        };

        scope.processKeyEvent = function ($event) {
          function getNextIndex(index, direction) {
            var nextIndex = index + direction;
            return (nextIndex < 0 || nextIndex > scope.widgetLibraries.length - 1) ? index : nextIndex;
          }

          switch ($event.keyCode) {
            case keyIndices.KEY_UP:
              // Otherwise browser will move cursor to start of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, -1);
              scope.widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_DOWN:
              // Otherwise browser will move cursor to end of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, 1);
              scope.widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_ENTER:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              scope.librarySelection.library = scope.widgetLibraries[selectedIndex];
              if (_.isFunction(scope.clickAction)) {
                scope.clickAction(element);
              }
              removeWidget();
              break;
            case keyIndices.KEY_ESC:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              removeWidget();
              break;
          }
        };

        scope.showCreatePanel = function () {
          scope.showCreate = true;

          // Wait for the creation panel to be shown, and then focus on the
          // input.
          $timeout(function () {
            newLibraryNameInput.focus();
          }, 0);
        };

        scope.hideCreatePanel = function () {
          scope.showCreate = false;
        };

        scope.createLibrary = function (library) {
          if (submitting || !library.name || (library.name.length < 3)) {
            return;
          }

          library.slug = util.generateSlug(library.name);
          library.visibility = library.visibility || 'published';

          libraryService.createLibrary(library).then(function () {
            libraryService.fetchLibrarySummaries(true).then(function () {
              scope.$evalAsync(function () {
                scope.librarySelection.library = _.find(scope.widgetLibraries, { 'name': library.name });
                if (_.isFunction(scope.clickAction)) {
                  scope.clickAction(element);
                }
                removeWidget();
              });
            });
          })['finally'](function () {
            submitting = false;
          });
        };


        //
        // Clean up.
        //
        scope.$on('$destroy', removeWidget);
      }
    };
  }
]);
