'use strict';

angular.module('kifi')

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'tagService', 'util',
  function ($scope, profileService, tagService, util) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: []};

    $scope.$watch(function () {
      // TODO: is this too inefficient? Will this be called too many times?
      return $scope.keeps.length + ',' + tagService.allTags.length;
    }, function () {
      if ($scope.keeps && $scope.keeps.length && tagService.allTags.length) {
        util.joinTags($scope.keeps, tagService.allTags);
      }
    });

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
  '$window', '$timeout', 'keepActionService', 'selectionService', 'tagService', 'undoService',
  function ($window, $timeout, keepActionService, selectionService, tagService, undoService) {

    return {
      restrict: 'A',
      scope: {
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
                keep.calcSizeCard();
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
