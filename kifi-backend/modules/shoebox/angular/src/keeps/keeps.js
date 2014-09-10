'use strict';

angular.module('kifi')

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: []};

    $scope.$watch(function () {
      return keepService.seqResult() + ',' + tagService.allTags.length;
    }, function () {
      if ($scope.keeps && $scope.keeps.length && tagService.allTags.length) {
        keepService.joinTags($scope.keeps, tagService.allTags);
      }
    });

    $scope.dragKeeps = function (keep, event, mouseX, mouseY) {
      var draggedKeeps = keepService.getSelected();  // need to revise this.
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
  'keepService', '$window', '$timeout', 'keepActionService', 'selectionService', 'tagService', 'undoService',
  function (keepService, $window, $timeout, keepActionService, selectionService, tagService, undoService) {

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
        toggleEdit: '='
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope, element /*, attrs*/) {
        var selection = new selectionService.Selection();

        scope.toggleSelect = function (keep) {
          return selection.toggleSelect(keep);
        };

        scope.getSelected = function (keeps) {
          return selection.getSelected(keeps);
        };

        scope.isSelected = function (keep) {
          return selection.isSelected(keep);
        };

        scope.toggleSelectAll = function (keeps) {
          return selection.toggleSelectAll(keeps);
        };

        scope.isSelectedAll = function (keeps) {
          return selection.isSelectedAll(keeps);
        };

        scope.editingTags = false;
        scope.addingTag = {enabled: false};

        scope.keepClickAction = function (event, keep) {
          if (event.metaKey && event.target.tagName !== 'A' && event.target.tagName !== 'IMG') {
            if (!scope.editMode.enabled) {
              scope.toggleEdit(true);
            }
            scope.editMode.enabled = true;
            selection.toggleSelect(keep);
          } else if (event.target.href && scope.keepClick) {
            scope.keepClick(keep, event);
          }
        };

        scope.isMultiChecked = function () {
          return selection.getSelectedLength() > 0 && !selection.isSelectedAll(scope.keeps);
        };

        scope.isUnchecked = function () {
          return !scope.isSelectedAll(scope.keeps) && !scope.isMultiChecked();
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

        scope.scrollDistance = '100%';

        scope.unkeep = function (keeps) {
          var selectedKeeps = selection.getSelected(keeps);
          var originalKeeps = keeps.slice(0);

          keepActionService.unkeepMany(selectedKeeps).then(function () {
            _.forEach(selectedKeeps, function (selectedKeep){
              selectedKeep.makeUnkept();
              _.remove(keeps, function (keep) { return keep.id === selectedKeep.id; });
            });

            undoService.add(selectedKeeps.length + ' keeps deleted.', function () {
              keepActionService.keepMany(selectedKeeps);
              keeps = originalKeeps;
              tagService.addToKeepCount(selectedKeeps.length);
            });

            tagService.addToKeepCount(-1 * selectedKeeps.length);
          });
        };

        scope.togglePrivate = function (keeps) {
          keepActionService.togglePrivateMany(selection.getSelected(keeps));
        };

        scope.selectionPrivacyState = function () {
          if (_.every(selection.getSelected(scope.keeps), 'isPrivate')) {
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

        scope.$watch(function () {
          return selection.getSelected(scope.keeps).length;
        }, function () {
          scope.disableEditTags();
        });

        var lastSizedAt = $window.innerWidth;
        function resizeWindowListener() {
          if (Math.abs($window.innerWidth - lastSizedAt) > 250) {
            lastSizedAt = $window.innerWidth;
            sizeKeeps();
          }
        }

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
