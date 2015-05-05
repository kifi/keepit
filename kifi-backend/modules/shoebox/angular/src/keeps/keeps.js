'use strict';

angular.module('kifi')

.directive('kfKeeps', [
  '$window', '$timeout', 'keepActionService', 'libraryService', 'modalService', 'KeepSelection', 'undoService', 'profileService',
  function ($window, $timeout, keepActionService, libraryService, modalService, KeepSelection, undoService, profileService) {

    return {
      restrict: 'A',
      scope: {
        library: '=',
        keeps: '=',
        keepsLoading: '=',
        keepsHasMore: '=',
        keepClick: '=',
        scrollDisabled: '=',
        scrollNext: '&',
        edit: '=',
        currentPageOrigin: '@'
      },
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope) {
        //
        // Internal data.
        //
        var lastSizedAt = $window.innerWidth;
        var twoColMinWidth = 720;  // also in stylesheet

        scope.$watchCollection(function () {
          return scope.keeps;
        }, function (keeps) {
          scope.availableKeeps = _.reject(keeps, {unkept: true});
        });


        //
        // Internal methods.
        //
        function sizeKeeps() {
          scope.$broadcast('resizeImage');

          $timeout(function () {
            scope.keeps.forEach(function (keep) {
              if (keep.calcSizeCard) {
                keep.calcSizeCard(keep);
              }
            });
            scope.keeps.forEach(function (keep) {
              if (keep.sizeCard) {
                keep.sizeCard();
              }
            });
          });
        }

        function resizeWindowListener() {
          if (Math.abs($window.innerWidth - lastSizedAt) > 250) {
            lastSizedAt = $window.innerWidth;
            sizeKeeps();
          }
        }


        //
        // Scope data.
        //
        scope.me = profileService.me;
        scope.scrollDistance = '100%';
        scope.selection = new KeepSelection();


        //
        // Scope methods.
        //
        scope.keepClickAction = function (event, keep) {
          if (scope.keepClick) {
            // the timeout is to prevent pop-up blocker
            setTimeout(function () {
              scope.keepClick(keep, event);
            });
          }
        };

        scope.isMultiChecked = function (keeps) {
          return scope.selection.getSelectedLength() > 0 && !scope.selection.isSelectedAll(keeps);
        };

        scope.isUnchecked = function (keeps) {
          return !scope.selection.isSelectedAll(keeps) && !scope.isMultiChecked(keeps);
        };

        scope.isShowMore = function () {
          return !scope.keepsLoading && scope.keepsHasMore;
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled || lastSizedAt < twoColMinWidth;
        };

        scope.unkeep = function (keeps) {
          var selectedKeeps = scope.selection.getSelected(keeps);
          var libraryId = scope.library.id;

          keepActionService.unkeepManyFromLibrary(libraryId, selectedKeeps).then(function () {
            _.forEach(selectedKeeps, function (selectedKeep) {
              selectedKeep.makeUnkept();
            });

            libraryService.addToLibraryCount(libraryId, -selectedKeeps.length);
            scope.selection.unselectAll();

            scope.availableKeeps = _.difference(scope.availableKeeps, selectedKeeps);
            if (scope.availableKeeps.length < 10) {
              scope.scrollNext()(scope.availableKeeps.length);
            }

            undoService.add(selectedKeeps.length > 1 ? selectedKeeps.length + ' keeps deleted' : 'keep deleted', function () {
              keepActionService.keepToLibrary(_.map(selectedKeeps, function (keep) { return _.pick(keep, 'url', 'title'); }), libraryId).then(function () {
                _.forEach(selectedKeeps, function (keep) {
                  keep.makeKept();
                });

                libraryService.addToLibraryCount(libraryId, selectedKeeps.length);
                scope.selection.selectAll(selectedKeeps);

                scope.availableKeeps = _.reject(scope.keeps, {unkept: true});
              })['catch'](modalService.openGenericErrorModal);
            });
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.onWidgetKeepToLibraryClicked = function (clickedLibrary) {
          var selectedKeeps = scope.selection.getSelected(scope.availableKeeps);
          var keepInfos = selectedKeeps.map(function (keep) {
            return { 'url': keep.url, 'title': keep.title };
          });

          keepActionService.keepToLibrary(keepInfos, clickedLibrary.id).then(function (data) {
            var addedKeeps = data.keeps;
            if (addedKeeps.length > 0) {
              libraryService.fetchLibraryInfos(true);
              scope.$emit('keepAdded', addedKeeps, clickedLibrary);
            }
            libraryService.fetchLibraryInfos(true);
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.onWidgetCopyLibraryClicked = function (clickedLibrary) {
          // Copies the keeps that are selected into the library that is selected.
          var selectedKeeps = scope.selection.getSelected(scope.availableKeeps);

          keepActionService.copyToLibrary(_.pluck(selectedKeeps, 'id'), clickedLibrary.id).then(function (data) {
            var addedKeeps = data.successes;
            if (addedKeeps.length > 0) {
              libraryService.fetchLibraryInfos(true);
              scope.$emit('keepAdded', addedKeeps, clickedLibrary);
            }
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.onWidgetMoveLibraryClicked = function (clickedLibrary) {
          // Moves the keeps that are selected into the library that is selected.
          var selectedKeeps = scope.selection.getSelected(scope.availableKeeps);

          keepActionService.moveToLibrary(_.pluck(selectedKeeps, 'id'), clickedLibrary.id).then(function () {
            // TODO: look at result and flag errors. Right now, even a partial error is flagged so that's
            //       not good.
            _.forEach(selectedKeeps, function (selectedKeep) {
              selectedKeep.makeUnkept();
            });

            libraryService.fetchLibraryInfos(true);
            var currentLibraryId = scope.library.id;
            libraryService.addToLibraryCount(currentLibraryId, -1 * selectedKeeps.length);
            scope.availableKeeps = _.difference(scope.availableKeeps, selectedKeeps);
            scope.selection.unselectAll();
          })['catch'](modalService.openGenericErrorModal);
        };


        //
        // Watches and listeners.
        //

        scope.$watch('edit.enabled', function (newVal, oldVal) {
          if (oldVal && !newVal) {
            scope.selection.unselectAll();
          }
        });

        var lazyResizeListener = _.debounce(resizeWindowListener, 250);
        $window.addEventListener('resize', lazyResizeListener);

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', lazyResizeListener);
        });
      }
    };
  }
]);
