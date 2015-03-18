'use strict';

angular.module('kifi')

.directive('kfKeeps', [
  '$window', '$timeout', 'keepActionService', 'libraryService', 'modalService', 'selectionService', 'undoService', 'profileService',
  function ($window, $timeout, keepActionService, libraryService, modalService, selectionService, undoService, profileService) {

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
        editMode: '=',
        editOptions: '&',
        toggleEdit: '=',
        updateSelectedCount: '&',
        currentPageOrigin: '@'
      },
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope) {
        //
        // Internal data.
        //
        var lastSizedAt = $window.innerWidth;

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
        scope.editingTags = false;
        scope.addingTag = {enabled: false};

        // 'selection' keeps track of which keeps have been selected.
        scope.selection = new selectionService.Selection();

        // set default edit-mode options if it's not set by parent
        scope.editOptions = _.isObject(scope.editOptions()) ? scope.editOptions : function() {
          return {
            actions: {
              bulkUnkeep: true,
              copyToLibrary: true,
              moveToLibrary: true,
              editTags: true
            }
          };
        };


        //
        // Scope methods.
        //
        scope.keepClickAction = function (event, keep) {
          if (event.metaKey && event.target.tagName !== 'A' && event.target.tagName !== 'IMG') {
            if (!scope.editMode.enabled) {
              scope.toggleEdit(true);
            }
            scope.editMode.enabled = true;
            scope.selection.toggleSelect(keep);
          } else if (scope.keepClick) {
            // the timeout is to prevent pop-up blocker
            setTimeout(function () {
              scope.keepClick(keep, event);
            });
          }
          return true;
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
          return scope.scrollDisabled;
        };

        scope.unkeep = function (keeps) {
          var selectedKeeps = scope.selection.getSelected(keeps);
          var libraryId = scope.library.id;

          keepActionService.unkeepManyFromLibrary(libraryId, selectedKeeps).then(function () {
            _.forEach(selectedKeeps, function (selectedKeep) {
              selectedKeep.makeUnkept();
            });

            var keepsDeletedText = selectedKeeps.length > 1 ? ' keeps deleted' : ' keep deleted';
            undoService.add(selectedKeeps.length + keepsDeletedText, function () {
              keepActionService.keepToLibrary(_.map(selectedKeeps, function(keep) {
                var keepData = { url: keep.url };
                if (keep.title) { keepData.title = keep.title; }
                return keepData;
              }), libraryId).then(function () {
                _.forEach(selectedKeeps, function (selectedKeep) {
                  selectedKeep.makeKept();
                });

                scope.selection.selectAll(selectedKeeps);
                libraryService.addToLibraryCount(libraryId, selectedKeeps.length);
              })['catch'](modalService.openGenericErrorModal);
            });

            libraryService.addToLibraryCount(libraryId, -1 * selectedKeeps.length);
            scope.selection.unselectAll();

            scope.availableKeeps = _.difference(scope.availableKeeps, selectedKeeps);
            if (scope.availableKeeps.length < 10) {
              scope.scrollNext()(scope.availableKeeps.length);
            }
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.selectionPrivacyState = function (keeps) {
          if (_.every(scope.selection.getSelected(keeps), 'isPrivate')) {
            return 'Public';
          } else {
            return 'Private';
          }
        };

        scope.enableEditTags = function () {
          scope.editingTags = true;
        };

        scope.disableEditTags = function () {
          scope.editingTags = false;
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
        scope.$watch(function () {
          return scope.selection.getSelected(scope.keeps).length;
        }, function (numSelected) {
          scope.disableEditTags();
          scope.updateSelectedCount({ numSelected: numSelected });
        });

        scope.$watch(function () {
          return scope.editMode.enabled;
        }, function(enabled) {
          if (!enabled) {
            scope.selection.unselectAll();
          }
        });

        var lazyResizeListener = _.debounce(resizeWindowListener, 250);
        $window.addEventListener('resize', lazyResizeListener);

        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', lazyResizeListener);
          scope.editMode.enabled = false;
        });
      }
    };
  }
]);
