'use strict';

angular.module('kifi')

.directive('kfKeepToLibraryWidget', [
  '$rootElement', '$compile', '$document', '$filter', '$rootScope', '$templateCache', '$timeout', '$window',
  'KEY', 'libraryService', 'util', 'profileService', 'modalService',
  function ($rootElement, $compile, $document, $filter, $rootScope, $templateCache, $timeout, $window,
    KEY, libraryService, util, profileService, modalService) {
    return {
      restrict: 'A',
      /*
       * Scope properties
       *  (optional) widgetActionText - Text to display for action into library. E.g., 'Keep' will result
       *                                'Keep to a library' in the widget header.
       *  (optional) keptToLibraryIds - an array of library ids that are already keeping the keep.
       *
       *  ---------
       *  Callbacks
       *  ---------
       *  (optional) librarySelectAction - a function that is called when a library has been selected;
       *                                   called with the selected library.
       *  (optional) libraryClickAction - a function that can be called once a library is clicked;
       *                                 called with the clicked library.
       *  (optional) widgetExitAction - a function that is called once the widget exits (includes when the widget exits after an action).
       *
       *  ----------------------
       *  Positioning properties
       *  ----------------------
       *  (optional) libSelectDownOffset - shift the bottom of the widget this much below the element.
       *  (optional) libSelectMaxUpOffset - maximum amount to shift up the top of the widget above the element.
       *  (optional) libSelectLeftOffset - shift the left edge of the widget this much to the left of the element.
       */
      scope: {
        widgetActionText: '@',
        keptToLibraryIds: '=',
        librarySelectAction: '&',
        libraryClickAction: '&',
        widgetExitAction: '&',
        libSelectDownOffset: '=',
        libSelectMaxUpOffset: '=',
        libSelectLeftOffset: '='
      },
      link: function (scope, element) {
        //
        // Internal data.
        //
        var widget = null;
        var searchInput = null;
        var libraryList = null;
        var newLibraryNameInput = null;

        var keptToLibraryIds = [];
        var selectedIndex = 0;
        var justScrolled = false;
        var removeTimeout = null;
        var resetJustScrolledTimeout = null;
        var allLibraries = [];
        var widgetLibraries = [];
        var libraryNameSearch = null;

        // Initialize library
        scope.libraryProps = {};
        scope.newLibrary = { visibility: 'published' };
        scope.space = {};
        scope.me = profileService.me;


        //
        // Internal methods.
        //
        function init() {
          element.on('click', function () {
            if (profileService.shouldBeWindingDown()) {
              modalService.showWindingDownModal();
            } else {
              initWidget();
            }
          });

          scope.$on('$destroy', removeWidget);
        }

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

          if (_.isFunction(scope.widgetExitAction)) {
            scope.widgetExitAction();
          }
        }

        function invokeWidgetCallbacks(selectedLibrary) {
          if (_.isFunction(scope.librarySelectAction)) {
            scope.librarySelectAction({ selectedLibrary: selectedLibrary });
          }

          if (_.isFunction(scope.libraryClickAction)) {
            scope.libraryClickAction({ clickedLibrary: selectedLibrary });
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
            invokeWidgetCallbacks(widgetLibraries[selectedIndex]);
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

          // Each library list item is 47px high. For a library item to be visible, it should be
          // entirely within the visible area (this means its top offset should be at least one
          // library item height from the visible bottom).

          // If we are not showing any subheaders (e.g., 'Kept In'), then we are displaying 6 librares.
          // When we display subheaders, we are displaying 5 libraries (number of displayed libraries
          // depends on max-height set in the stylesheet.).
          var numLibrariesVisible = scope.widgetKeptInLibraries.length || scope.widgetRecentLibraries.length ? 5 : 6;

          var selectedLibraryTop = selectedIndex * 47;
          var visibleTop = libraryList.scrollTop();
          var visibleBottom = visibleTop + (numLibrariesVisible * 47);

          if (selectedLibraryTop < visibleTop) {
            libraryList.scrollTop(selectedLibraryTop);
            setJustScrolled();
          } else if (selectedLibraryTop > (visibleBottom - 47)) {
            libraryList.scrollTop(selectedLibraryTop - ((numLibrariesVisible - 1) * 47));
            setJustScrolled();
          }
        }

        function groupWidgetLibraries(libraries) {
          libraries = libraries.slice();
          var recentIds = libraryService.getRecentIds();

          scope.widgetKeptInLibraries = _.remove(libraries, function (lib) {
            return keptToLibraryIds.indexOf(lib.id) >= 0;
          });
          scope.widgetRecentLibraries = _.remove(libraries, function (lib) {
            return recentIds.indexOf(lib.id) >= 0;
          }).sort(function (a, b) {  // restore recentIds's sort order
            return recentIds.indexOf(a.id) - recentIds.indexOf(b.id);
          });
          scope.widgetOtherLibraries = libraries;

          // widgetLibraries is the list of libraries being displayed in the widget in display order.
          widgetLibraries = scope.widgetKeptInLibraries.concat(scope.widgetRecentLibraries, scope.widgetOtherLibraries);

          // Select the first listed library.
          selectedIndex = 0;
          if (widgetLibraries.length) {
            widgetLibraries[selectedIndex].selected = true;
          }
        }

        function initWidget() {
          //
          // Create widget.
          //
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
            var shiftUpDistance;
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


          //
          // Initialize state.
          //
          selectedIndex = 0;
          keptToLibraryIds = scope.keptToLibraryIds || [];

          scope.search = {};
          scope.showCreate = false;
          scope.$error = {};

          libraryList = widget.find('.library-select-list');
          searchInput = widget.find('.keep-to-library-search-input');
          newLibraryNameInput = widget.find('.keep-to-library-create-name-input');


          //
          // Group widget libraries.
          //
          allLibraries = libraryService.getOwnInfos();

          libraryNameSearch = new Fuse(allLibraries, {
            keys: ['name'],
            threshold: 0.3  // 0 means exact match, 1 means match with anything.
          });

          groupWidgetLibraries(allLibraries);


          //
          // Set event handlers.
          //
          $document.on('mousedown', onClick);
        }


        //
        // Scope methods.
        //
        scope.onHover = function (library) {
          if (justScrolled) {
            justScrolled = false;
          } else {
            clearSelection();
            library.selected = true;
            selectedIndex = _.indexOf(widgetLibraries, library);
          }
        };

        scope.onUnhover = function (library) {
          library.selected = false;
        };

        scope.onSearchInputChange = function (name) {
          var matchedLibraries = libraryNameSearch.search(name);
          groupWidgetLibraries(!name ? allLibraries : matchedLibraries);
        };

        scope.processKeyEvent = function ($event) {
          function getNextIndex(index, direction) {
            var nextIndex = index + direction;
            return (nextIndex < 0 || nextIndex > widgetLibraries.length - 1) ? index : nextIndex;
          }

          switch ($event.keyCode) {
            case KEY.UP:
              // Otherwise browser will move cursor to start of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, -1);
              widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case KEY.DOWN:
              // Otherwise browser will move cursor to end of input.
              $event.preventDefault();

              clearSelection();
              selectedIndex = getNextIndex(selectedIndex, 1);
              widgetLibraries[selectedIndex].selected = true;

              adjustScroll(selectedIndex);

              break;
            case KEY.ENTER:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              // If there are any libraries shown, select confirm the selected library.
              // Otherwise, go to the create panel.
              if (widget.find('.library-select-option').length) {
                invokeWidgetCallbacks(widgetLibraries[selectedIndex]);
                removeWidget();
              } else {
                // If there are no libraries shown, prepopulate the create-library
                // name input field with the search query.
                scope.newLibrary.name = scope.search.name;

                scope.showCreatePanel();
              }
              break;
            case KEY.ESC:
              // Prevent any open modals from processing this.
              $event.stopPropagation();

              removeWidget();
              break;
          }
        };

        scope.showCreatePanel = function () {
          scope.showCreate = true;

          // If there are no libraries shown, prepopulate the create-library
          // name input field with the search query.
          if (!widget.find('.library-select-option').length) {
            scope.newLibrary.name = scope.search.name;
          }

          // Wait for the creation panel to be shown, and then focus on the
          // input.
          $timeout(function () {
            // Focus without scrolling
            var x = $window.scrollX, y = $window.scrollY;
            newLibraryNameInput.focus();
            $window.scrollTo(x, y);
          }, 0);
        };

        scope.hideCreatePanel = function () {
          scope.showCreate = false;

          // Wait for the libraries panel to be shown, and then focus on the
          // search input and scroll back to the top.
          $timeout(function () {
            searchInput.focus();
            libraryList.scrollTop(0);
          }, 0);
        };

        scope.onceLibraryCreated = function(library) {
          libraryService.fetchLibraryInfos(true).then(function () {
            scope.$evalAsync(function () {
              invokeWidgetCallbacks(_.find(libraryService.getOwnInfos(), {id: library.id}));
            });
          });

          removeWidget();
        };

        init();
      }
    };
  }
]);
