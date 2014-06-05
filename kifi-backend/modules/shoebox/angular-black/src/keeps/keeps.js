'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;
    $scope.data = {draggedKeeps: null};

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.allTags.length;
    }, function () {
      if ($scope.keeps && $scope.keeps.length && tagService.allTags.length) {
        keepService.joinTags($scope.keeps, tagService.allTags);
      }
    });

    $scope.dragKeeps = function (keep, event, mouseX, mouseY) {
      var draggedKeeps = [];//keepService.getSelected();
      if (draggedKeeps.length === 0) {
        draggedKeeps = [keep];
      }
      $scope.data.draggedKeeps = draggedKeeps;
      var draggedKeepsElement = $scope.getDraggedKeepsElement();
      var sendData = angular.toJson($scope.data.draggedKeeps);
      event.dataTransfer.setData('Text', sendData);
      event.dataTransfer.setDragImage(draggedKeepsElement[0], mouseX, mouseY);
    };

    $scope.stopDraggingKeeps = function () {
      $scope.data.draggedKeeps = null;
    };
  }
])

.directive('kfKeeps', [
  'keepService', '$document', '$log', '$window',
  function (keepService, $document, $log, $window) {

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        keepsLoading: '=',
        keepsHasMore: '=',
        keepClick: '=',
        scrollDisabled: '=',
        scrollNext: '&',
        editMode: '='
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
          var ellipsis = element.find('.kf-shadow-keep-ellipsis');
          var ellipsisCounter = element.find('.kf-shadow-keep-ellipsis-counter');
          var ellipsisCounterHidden = element.find('.kf-shadow-keep-ellipsis-counter-hidden');
          var second = element.find('.kf-shadow-keep-second');
          var last = element.find('.kf-shadow-keep-last');
          var keepHeaderHeight = 35;
          var ellipsisHeight = 28;
          if (scope.data.draggedKeeps.length === 2) {
            last.css({top: keepHeaderHeight + 'px'});
          } else if (scope.data.draggedKeeps.length === 3) {
            second.css({top: keepHeaderHeight + 'px'});
            last.css({top: 2 * keepHeaderHeight + 'px'});
          } else if (scope.data.draggedKeeps.length >= 4) {
            ellipsis.css({top: keepHeaderHeight + 'px', height: ellipsisHeight + 'px'});
            ellipsisCounter.css({left: (parseInt(ellipsis.width(), 10) - parseInt(ellipsisCounterHidden.width(), 10)) / 2});
            last.css({top: keepHeaderHeight + ellipsisHeight + 'px'});
          }
          return element.find('.kf-shadow-dragged-keeps');
        };

        var shadowDraggedKeeps = element.find('.kf-shadow-dragged-keeps');
        shadowDraggedKeeps.css({top: 0, width: element.find('.kf-my-keeps')[0].offsetWidth + 'px'});

        angular.element($window).on('scroll', function () {
          var scrollMargin = $window.innerHeight;
          var totalHeight = $document[0].documentElement.scrollHeight;
          if (!scope.scrollDisabled &&
            $window.pageYOffset + $window.innerHeight + scrollMargin > totalHeight) {
            scope.scrollNext();
          }
        });

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
      }
    };
  }
]);
