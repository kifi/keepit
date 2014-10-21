'use strict';

angular.module('kifi')

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'tagService', 'util',
  function ($scope, profileService, tagService, util) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: []};

    // Whenever new keeps are loaded or when tags have been added or removed,
    // sync up the keep tags with the current list of tags.
    function joinTags() {
      var keeps = $scope.keeps;
      var tags = tagService.allTags;
      if (keeps && keeps.length && tags.length) {
        var tagsById = _.indexBy(tags, 'id');
        var toTag = function (id) {
          return tagsById[id];
        };

        _.forEach(keeps, function (keep) {
          var newTagList = _(keep.collections).union(keep.tags).map(toTag).compact().value();
          if (keep.tagList) {
            util.replaceArrayInPlace(keep.tagList, newTagList);
          } else {
            keep.tagList = newTagList;
          }
        });
      }
    }
    $scope.$watch(function () {
      return $scope.keepsLoading;
    }, function (newVal, oldVal) {
      if (!newVal && oldVal) {
        joinTags();
      }
    });
    $scope.$watch(function () {
      return tagService.allTags.length;
    }, joinTags);

    $scope.dragKeeps = function (keep, event, mouseX, mouseY, selection) {
      var draggedKeeps = selection.getSelected($scope.keeps);
      if (draggedKeeps.length === 0) {
        draggedKeeps = [keep];
      }
      $scope.data.draggedKeeps = draggedKeeps;
      var sendData = angular.toJson($scope.data.draggedKeeps);
      event.dataTransfer.setData('Text', sendData);
      //event.dataTransfer.setData('text/plain', '');
      var draggedKeepsElement = $scope.getDraggedKeepsElement();
      draggedKeepsElement.find('.kf-keep').css('background', 'rgba(255,255,255,.7)');
      event.dataTransfer.setDragImage(draggedKeepsElement[0], mouseX, mouseY);
    };

    $scope.stopDraggingKeeps = function () {
      $scope.data.draggedKeeps = null;
    };
  }
])

.directive('kfKeeps', [
  '$rootScope', '$window', '$timeout', 'keepActionService', 'libraryService', 'modalService', 'selectionService', 'tagService', 'undoService',
  function ($rootScope, $window, $timeout, keepActionService, libraryService, modalService, selectionService, tagService, undoService) {

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
        toggleEdit: '=',
        updateSelectedCount: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope, element /*, attrs*/) {
        //
        // Internal data.
        //
        var lastSizedAt = $window.innerWidth;


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

        function copyToLibrary () {
          // Copies the keeps that are selected into the library that is selected.
          var selectedKeeps = scope.selection.getSelected(scope.keeps);
          var selectedLibrary = scope.librarySelection.library;

          keepActionService.copyToLibrary(_.pluck(selectedKeeps, 'id'), selectedLibrary.id).then(function () {
            // TODO: look at result and flag errors. Right now, even a partial error is flagged so that's
            //       not good.
            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('librarySummariesChanged');
            });
          });
        }

        function moveToLibrary () {
          // Moves the keeps that are selected into the library that is selected.
          var selectedKeeps = scope.selection.getSelected(scope.keeps);
          var selectedLibrary = scope.librarySelection.library;

          keepActionService.moveToLibrary(_.pluck(selectedKeeps, 'id'), selectedLibrary.id).then(function () {
            // TODO: look at result and flag errors. Right now, even a partial error is flagged so that's
            //       not good.
            libraryService.fetchLibrarySummaries(true).then(function () {
              $rootScope.$emit('librarySummariesChanged');
            });
          });
        }


        //
        // Scope data.
        //
        scope.scrollDistance = '100%';
        scope.editingTags = false;
        scope.addingTag = { enabled: false };

        // 'selection' keeps track of which keeps have been selected.
        scope.selection = new selectionService.Selection();


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
          } else if (event.target.href && scope.keepClick) {
            scope.keepClick(keep, event);
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

        scope.getDraggedKeepsElement = function () {
          if (scope.data.draggedKeeps.length >= 4) {
            var ellipsis = element.find('.kf-shadow-keep-ellipsis');
            var ellipsisCounter = element.find('.kf-shadow-keep-ellipsis-counter');
            var ellipsisCounterHidden = element.find('.kf-shadow-keep-ellipsis-counter-hidden');
            ellipsisCounter.css({left: (parseInt(ellipsis.width(), 10) - parseInt(ellipsisCounterHidden.width(), 10)) / 2});
          }
          return element.find('.kf-shadow-dragged-keeps');
        };

        scope.draggedTwo = function () {
          return scope.data.draggedKeeps && scope.data.draggedKeeps.length === 2;
        };

        scope.draggedThree = function () {
          return scope.data.draggedKeeps && scope.data.draggedKeeps.length === 3;
        };

        scope.draggedMore = function () {
          return scope.data.draggedKeeps && scope.data.draggedKeeps.length > 3;
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
        };

        scope.unkeep = function (keeps) {
          var selectedKeeps = scope.selection.getSelected(keeps);

          if (scope.librariesEnabled) {
            var libraryId = selectedKeeps[0].libraryId;

            keepActionService.unkeepManyFromLibrary(libraryId, selectedKeeps).then(function () {
              _.forEach(selectedKeeps, function (selectedKeep) {
                selectedKeep.makeUnkept();
              });

              undoService.add(selectedKeeps.length + ' keeps deleted.', function () {
                keepActionService.keepToLibrary(_.pluck(selectedKeeps, 'url'), libraryId);

                _.forEach(selectedKeeps, function (selectedKeep) {
                  selectedKeep.makeKept();
                });

                libraryService.addToLibraryCount(libraryId, selectedKeeps.length);
              });

              libraryService.addToLibraryCount(libraryId, -1 * selectedKeeps.length);
            });
          } else {
            keepActionService.unkeepMany(selectedKeeps).then(function () {
              _.forEach(selectedKeeps, function (selectedKeep) {
                selectedKeep.makeUnkept();
              });

              undoService.add(selectedKeeps.length + ' keeps deleted.', function () {
                keepActionService.keepMany(selectedKeeps);
                _.forEach(selectedKeeps, function (selectedKeep) {
                  selectedKeep.makeKept();
                });
              });

              tagService.addToKeepCount(-1 * selectedKeeps.length);
            });
          }
        };

        scope.togglePrivate = function (keeps) {
          keepActionService.togglePrivateMany(scope.selection.getSelected(keeps));
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


        //
        // Watches and listeners.
        //
        scope.$watch(function () {
          return libraryService.isAllowed();
        }, function (newVal) {
          scope.librariesEnabled = newVal;
        });

        scope.$watch('keepsLoading', function (newVal) {
          if (!newVal) {
            if (scope.librariesEnabled) {
              libraryService.fetchLibrarySummaries().then(function () {
                scope.libraries = _.filter(libraryService.librarySummaries, function (library) {
                  return scope.library && library.access !== 'read_only' && library.id !== scope.library.id;
                });

                scope.librarySelection = {};
                scope.clickAction = function (widgetElement) {
                  if (widgetElement.closest('.copy-to-library').length) {
                    copyToLibrary();
                  } else if (widgetElement.closest('.move-to-library').length) {
                    moveToLibrary();
                  }
                };
              });
            }
          }
        });

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

        $rootScope.$on('librarySummariesChanged', function () {
          if (scope.librariesEnabled) {
            scope.libraries = _.filter(libraryService.librarySummaries, function (lib) {
              return lib.access !== 'read_only';
            });
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
