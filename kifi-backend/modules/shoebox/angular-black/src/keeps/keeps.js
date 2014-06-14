'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: []};

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.allTags.length;
    }, function () {
      if ($scope.keeps && $scope.keeps.length && tagService.allTags.length) {
        keepService.joinTags($scope.keeps, tagService.allTags);
      }
    });

    $scope.dragKeeps = function (keep, event, mouseX, mouseY) {
      var draggedKeeps = keepService.getSelected();
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
      //event.dataTransfer.setDragImage(draggedKeepsElement[0], mouseX, mouseY);
    };

    $scope.stopDraggingKeeps = function () {
      $scope.data.draggedKeeps = null;
    };
  }
])

.directive('kfKeeps', [
  'keepService', '$window', '$timeout',
  function (keepService, $window, $timeout) {

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
      link: function (scope, element /*, attrs*/ ) {
        scope.toggleSelect = keepService.toggleSelect;
        scope.getSelected = keepService.getSelected;
        scope.isSelected = keepService.isSelected;
        scope.toggleSelectAll = keepService.toggleSelectAll;
        scope.isSelectedAll = keepService.isSelectedAll;
        scope.editingTags = false;
        scope.addingTag = {enabled: false};

        scope.keepClickAction = function (event, keep) {
          if (event.metaKey && event.target.tagName !== 'A' && event.target.tagName !== 'IMG') {
            if (!scope.editMode.enabled) {
              scope.toggleEdit(true);
            }
            scope.editMode.enabled = true;
            scope.toggleSelect(keep);
          }
        };

        scope.isMultiChecked = function () {
          return keepService.getSelectedLength() > 0 && !keepService.isSelectedAll();
        };

        scope.isUnchecked = function () {
          return !scope.isSelectedAll() && !scope.isMultiChecked();
        };

        scope.isShowMore = function () {
          return !scope.keepsLoading && scope.keepsHasMore;
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
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

        scope.unkeep = function () {
          keepService.unkeep(keepService.getSelected());
        };

        scope.togglePrivate = function () {
          keepService.togglePrivate(keepService.getSelected());
        };

        scope.selectionPrivacyState = function () {
          if (_.every(keepService.getSelected(), 'isPrivate')) {
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
          return scope.getSelected().length;
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
          var d1 = +new Date;
          scope.$broadcast('resizeImage');
          $timeout(function () {
            scope.keeps.forEach(function (keep) {
              keep.calcSizeCard && keep.calcSizeCard();
            });
            scope.keeps.forEach(function (keep) {
              keep.sizeCard && keep.sizeCard();
            });
          });
        }

        var lazyResizeListener = _.debounce(resizeWindowListener, 250);
        $window.addEventListener('resize', lazyResizeListener);
        scope.$on('$destroy', function () {
          $window.removeEventListener('resize', lazyResizeListener);
        });

      }
    };
  }
]);
