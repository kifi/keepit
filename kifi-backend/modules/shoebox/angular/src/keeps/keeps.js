'use strict';

angular.module('kifi')

.directive('kfKeeps', [
  '$window', '$timeout', '$injector', 'KeepSelection', 'keepActionService', 'libraryService',
  'modalService', 'undoService', 'profileService', '$rootScope', 'util',
  function (
    $window, $timeout, $injector, KeepSelection, keepActionService, libraryService,
    modalService, undoService, profileService, $rootScope, util) {

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
        galleryView: '=',
        currentPageOrigin: '@'
      },
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope) {
        //
        // Internal data.
        //
        var winWidth = $window.innerWidth;
        var twoColMinWidth = 720;  // also in stylesheet


        //
        // Internal methods.
        //

        function onWinResize() {
          winWidth = $window.innerWidth;
        }


        //
        // Scope data.
        //
        scope.me = profileService.me;
        scope.getYoutubeId = util.getYoutubeIdFromUrl;
        scope.availableKeeps = [];
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
          return scope.scrollDisabled || winWidth < twoColMinWidth;
        };

        scope.unkeep = function (keeps) {
          var selectedKeeps = scope.selection.getSelected(keeps);
          var libraryId = scope.library.id;

          keepActionService.unkeepManyFromLibrary(libraryId, selectedKeeps).then(function () {
            _.forEach(selectedKeeps, function (selectedKeep) {
              selectedKeep.unkept = true;
              selectedKeep.keepersTotal--;
            });

            libraryService.addToLibraryCount(libraryId, -selectedKeeps.length);
            scope.selection.unselectAll();

            scope.availableKeeps = _.difference(scope.availableKeeps, selectedKeeps);
            if (scope.availableKeeps.length < 10) {
              scope.scrollNext()(scope.availableKeeps.length);
            }

            undoService.add(selectedKeeps.length > 1 ? selectedKeeps.length + ' keeps deleted.' : 'Keep deleted.', function () {
              keepActionService.keepToLibrary(_.map(selectedKeeps, function (keep) { return _.pick(keep, 'url', 'title'); }), libraryId).then(function () {
                _.forEach(selectedKeeps, function (keep) {
                  keep.unkept = false;
                  keep.keepersTotal++;
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
              selectedKeep.unkept = true;
            });

            libraryService.fetchLibraryInfos(true);
            var currentLibraryId = scope.library.id;
            libraryService.addToLibraryCount(currentLibraryId, -1 * selectedKeeps.length);
            scope.availableKeeps = _.difference(scope.availableKeeps, selectedKeeps);
            scope.selection.unselectAll();
          })['catch'](modalService.openGenericErrorModal);
        };

        scope.deleteKeep = function (event, keep) {
          angular.element(event.target).closest('.kf-keep').find('.kf-knf').remove();
          var libraryId = keep.library.id;
          keepActionService.unkeepFromLibrary(libraryId, keep.id).then(function () {
            keep.unkept = true;
            keep.keepersTotal--;

            libraryService.addToLibraryCount(libraryId, -1);
            scope.$emit('keepRemoved', {url: keep.url}, keep.library);

            undoService.add('Keep deleted.', function () {  // TODO: rekeepToLibrary endpoint that takes a keep ID
              keepActionService.keepToLibrary([{url: keep.url}], libraryId).then(function () {
                keep.unkept = false;
                keep.keepersTotal++;

                libraryService.addToLibraryCount(libraryId, 1);
                scope.$emit('keepAdded', [keep], keep.library);
              })['catch'](modalService.openGenericErrorModal);
            });
          })['catch'](modalService.openGenericErrorModal);
        };

        $rootScope.$on('editKeepNote', function(e, event, keep) { scope.editKeepNote(event, keep); });

        scope.editKeepNote = function (event, keep) {
          if (keep.user.id !== profileService.me.id) { return; }
          var keepEl = angular.element(event.target).closest('.kf-keep');
          var editor = keepEl.find('.kf-knf-editor');
          if (!editor.length) {
            var noteEl = keepEl.find('.kf-keep-note');
            $injector.get('keepNoteForm').init(noteEl, keep.note, keep.library && keep.library.id, keep.pubId, function update(noteText) {
              keep.note = noteText;
            });
          } else {
            editor.focus();
          }
        };


        scope.removeImage = function (event, keep) {
          var keepEl = angular.element(event.target).closest('.kf-keep');
          keepEl.find('.kf-keep-image').remove();
          keepEl.find('.kf-keep-card-image').remove();
          delete keep.summary.imageUrl;
          delete keep.summary.imageWidth;
          delete keep.summary.imageHeight;
          keepActionService.removeKeepImage(keep.libraryId, keep.id);
        };

        //
        // Watches and listeners.
        //

        scope.$watchCollection(function () {
          return scope.keeps;
        }, function (keeps) {
          scope.availableKeeps = _.reject(keeps, {unkept: true});
        });

        scope.$watch('edit.enabled', function (newVal, oldVal) {
          if (oldVal && !newVal) {
            scope.selection.unselectAll();
          }
        });

        $window.addEventListener('resize', onWinResize);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', onWinResize);
        });

        scope.onWidgetLibraryClicked = function(clickedLibrary) {
          $rootScope.$broadcast('onWidgetLibraryClicked', { clickedLibrary: clickedLibrary });
        };

        scope.$on('keepUpdatesPending', function (e, count) {
          if (count >= 10) {
            scope.keepUpdatesPending = '10+';
          } else {
            scope.keepUpdatesPending = count;
          }
        });

        scope.refreshLibrary = function () {
          scope.keepUpdatesPending = 0;
          scope.$emit('refreshLibrary');
        };
      }
    };
  }
]);
