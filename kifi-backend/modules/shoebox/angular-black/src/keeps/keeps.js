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
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope, element /*, attrs*/ ) {

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;

        var antiscroll = element.find('.antiscroll-inner');
        var wrapper = element.find('.keeps-wrapper');

        function bringCardIntoViewUp() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          if (!offset || !offset.top) {
            return;
          }

          if (offset.top - 300 < 0) {
            antiscroll.scrollTop(antiscroll.scrollTop() + (offset.top - 300));
          }
        }

        function bringCardIntoViewDown() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          if (!offset || !offset.top) {
            return;
          }
          var wrapperHeight = wrapper.height();

          if (offset.top + 100 > wrapperHeight) {
            antiscroll.scrollTop(antiscroll.scrollTop() + (offset.top + 100 - wrapperHeight));
          }
        }

        function keepKeyBindings(e) {
          var meta = e && (e.shiftKey || e.altKey || e.ctrlKey || e.metaKey);
          if (e && !meta && e.currentTarget && e.currentTarget.activeElement && e.currentTarget.activeElement.tagName === 'BODY') {
            var captured = false;
            /* jshint maxcomplexity: false */
            switch (e.which) {
              case 13: // enter
                //var p = keepService.getHighlighted();
                //keepService.togglePreview(p);
                captured = true;
                break;
              case 38: // up
              case 75: // k
                //keepService.previewPrev();
                bringCardIntoViewUp();
                captured = true;
                break;
              case 40: // down
              case 74: // j
                //keepService.previewNext();
                bringCardIntoViewDown();
                captured = true;
                break;
              case 32: // space
                keepService.toggleSelect();
                captured = true;
                break;
            }
            if (captured) {
              scope.$apply();
              e.preventDefault();
            } else {
              $log.log('key', String.fromCharCode(e.which), e.which);
            }
          }
        }

        $document.on('keydown', keepKeyBindings);

        scope.$on('$destroy', function () {
          $document.off('keydown', keepKeyBindings);
        });

        scope.isShowMore = function () {
          return !scope.keepsLoading && scope.keepsHasMore;
        };

        scope.onClickKeep = function (keep, $event) {
          return keep || $event; // so that jshint doesn't complain
          // commenting out, not used yet.
          /*if ($event.target.tagName !== 'A') {
            if ($event.ctrlKey || $event.metaKey) {
              if (scope.isSelected(keep)) {
                scope.unselect(keep);
              } else {
                scope.select(keep);
              }
            } else {
              scope.togglePreview(keep);
            }
          } else if (scope.keepClick) {
            scope.keepClick(keep, $event);
          }*/
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
      }
    };
  }
]);
