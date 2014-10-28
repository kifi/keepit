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
       *   keptToLibraries - an array of library ids that are already keeping the keep.
       *   clickAction() - a function that can be called once a library is selected;
       *                   called with the element that this widget is on.
       *   exitAction() - a function that is called once the widget exits (includes when the widget exits after an action).
       *
       *   Positioning properties:
       *   ----------------------
       *   libSelectDownOffset - shift the bottom of the widget this much below the element.
       *   libSelectMaxUpOffset - maximum amount to shift up the top of the widget above the element.
       *   libSelectLeftOffset - shift the left edge of the widget this much to the left of the element.
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
        var widgetLibraries = [];


        //
        // Scope data.
        //
        scope.search = {};
        scope.showCreate = false;
        scope.newLibrary = {};
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
          widgetLibraries.forEach(function (library) {
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

          if (_.isFunction(scope.exitAction)) {
            scope.exitAction();
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

          //
          // Position widget.
          //
          widget.hide();

          var desiredShiftDownDistance = scope.libSelectDownOffset || 0;
          var desiredMaxUpDistance = scope.libSelectMaxUpOffset || 1000;
          var desiredShiftLeftDistance = scope.libSelectLeftOffset || 0;

          var scrollTop = angular.element($window).scrollTop();
          var elementOffsetTop = element.offset().top;
          var distanceFromBottom = $window.innerHeight - elementOffsetTop + scrollTop;
          var distanceFromTop = elementOffsetTop - scrollTop;

          var scrollLeft = angular.element($window).scrollLeft();
          var elementOffsetLeft = element.offset().left;
          var distanceFromRight = $window.innerWidth - elementOffsetLeft + scrollLeft;

          // Wait for Angular to render the widget so we can grab its height.
          $timeout(function () {
            // Shift the widget left based on passed in desired shift left distance.
            // If the widget is cut off on the right, shift left some more so that the widget's
            // width is entirely visible.
            var widgetWidth = widget.width();
            var shiftLeftDistance = (distanceFromRight - widgetWidth + desiredShiftLeftDistance > 0) ?
              desiredShiftLeftDistance :
              widgetWidth - distanceFromRight;

            var left = element.offset().left - shiftLeftDistance;

            // Shift the widget up or down.
            var shiftUpDistance ;
            var widgetHeight = widget.height();

            // First, shift the widget down based on the passed in desired shift down distance.
            // After that, if the widget's top exceeds the passed in maximum top distance,
            // then shift the widget down enough so that the maximum top distance is not exceeded.
            if (widgetHeight - desiredShiftDownDistance <= desiredMaxUpDistance) {
              shiftUpDistance = widgetHeight - desiredShiftDownDistance;
            } else {
              shiftUpDistance = desiredMaxUpDistance;
            }

            // Second, check that the widget is not cut off on the bottom. If it's cut off,
            // shift it up enough so that the widget's bottom is visible.
            shiftUpDistance = (distanceFromBottom - widgetHeight + shiftUpDistance > 0) ?
              shiftUpDistance :
              widgetHeight - distanceFromBottom;

            // Third, check that the widget is not cut off on the top. If it's cut off,
            // shift it down enough so that the widget's top is visible.
            shiftUpDistance = (distanceFromTop - shiftUpDistance > 0) ?
              shiftUpDistance :
              distanceFromTop;

            var top = element.offset().top - shiftUpDistance;

            // Set the widget's position based on the left and top offsets calculated above.
            widget.css({top: top + 'px', left: left + 'px'});
            widget.show();

            // Wait for the widget to be shown, then focus on the search input.
            $timeout(function () {
              searchInput.focus();
            }, 0);
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
          scope.widgetRecentLibraries = [];
          scope.widgetOtherLibraries = [];

          var libraries = _.filter(libraryService.librarySummaries, { access: 'owner' });

          libraries = _.filter(libraries, function (library) {
            return !_.find(scope.excludeLibraries, { 'id': library.id });
          });

          // Sort libraries here.
          var groupedLibraries = _.groupBy(libraries, function (library) {
            return !!library.keptTo;
          });

          libraries = _.filter(libraries, function (library) {
            return !_.find(scope.excludeLibraries, { 'id': library.id });
          });

          libraries.forEach(function (library) {
            library.keptTo = false;
            if (_.indexOf(scope.keptToLibraries, library.id) !== -1) {
              library.keptTo = true;
            }
            if (_.indexOf(libraryService.recentLibraries, library.id) !== -1) {
              scope.widgetRecentLibraries.push(library);
            } else {
              scope.widgetOtherLibraries.push(library);
            }
          });

          // TODO(yiping): rename all 'keptTo' to 'keptIn' to be consistent with text in template.
          scope.widgetKeptInLibraries = groupedLibraries[true] || [];
          scope.widgetMyLibraries = groupedLibraries[false] || [];

          if (scope.widgetKeptInLibraries.length) {
            widgetLibraries = scope.widgetKeptInLibraries.concat(scope.widgetMyLibraries);
          } else {
            widgetLibraries = scope.widgetRecentLibraries.concat(scope.widgetOtherLibraries);
          }

          widgetLibraries[selectedIndex].selected = true;
        };

        scope.onHover = function (library) {
          if (justScrolled) {
            justScrolled = false;
          } else {
            clearSelection();
            library.selected = true;
            scope.librarySelection.library = library;
            selectedIndex = _.indexOf(widgetLibraries, library);
          }
        };

        scope.onUnhover = function (library) {
          library.selected = false;
        };

        scope.processKeyEvent = function ($event) {
          function getNextIndex(index, direction) {
            var nextIndex = index + direction;
            return (nextIndex < 0 || nextIndex > widgetLibraries.length - 1) ? index : nextIndex;
          }

          switch ($event.keyCode) {
            case keyIndices.KEY_UP:
              // Otherwise browser will move cursor to start of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, -1);
              widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_DOWN:
              // Otherwise browser will move cursor to end of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, 1);
              widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case keyIndices.KEY_ENTER:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              // If there are any libraries shown, select confirm the selected library.
              // Otherwise, go to the create panel.
              if (widget.find('.library-select-option').length) {
                scope.librarySelection.library = widgetLibraries[selectedIndex];
                if (_.isFunction(scope.clickAction)) {
                  scope.clickAction(element);
                }
                removeWidget();
              } else {
                scope.showCreatePanel();
              }
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
          scope.search = {};

          // Wait for the libraries panel to be shown, and then focus on the
          // search input.
          $timeout(function () {
            searchInput.focus();
          }, 0);
        };

        scope.createLibrary = function (library) {
          if (submitting || !library.name || (library.name.length < 3)) {
            return;
          }

          library.slug = util.generateSlug(library.name);
          library.visibility = library.visibility || 'published';

          libraryService.createLibrary(library).then(function () {
            libraryService.fetchLibrarySummaries(true).then(function (data) {
              scope.$evalAsync(function () {
                scope.librarySelection.library = _.find(widgetLibraries, { 'name': library.name });
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
