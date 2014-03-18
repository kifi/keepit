'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService', '$document', '$log',
  function ($scope, profileService, keepService, tagService, $document, $log) {
    $scope.me = profileService.me;

    function keepKeyBindings(e) {
      if (e && e.currentTarget && e.currentTarget.activeElement && e.currentTarget.activeElement.tagName === 'BODY') {
        var captured = false;
        switch (e.which) {
          case 27:
            if (keepService.isPreviewed()) {
              keepService.clearState();
              captured = true;
            }
            break;
          case 38: // up
          case 75: // k
            keepService.previewPrev();
            captured = true;
            break;
          case 40: // down
          case 74: // j
            keepService.previewNext();
            captured = true;
            break;
          case 32: // space
            keepService.toggleSelect();
            captured = true;
            break;
          default:
            $log.log('key', String.fromCharCode(e.which), e.which);
            break;
        }
        if (captured) {
          $scope.$apply();
          e.preventDefault();
        }
      }
    }

    $document.on('keydown', keepKeyBindings);

    $scope.$on('$destroy', function () {
      keepService.clearState();
      $document.off('keydown', keepKeyBindings);
    });

    $scope.$watch(function () {
      return ($scope.keeps && $scope.keeps.length || 0) + ',' + tagService.list.length;
    }, function () {
      // update antiscroll
      $scope.refreshScroll();

      if ($scope.keeps && $scope.keeps.length && tagService.list.length) {
        keepService.joinTags($scope.keeps, tagService.list);
      }
    });
  }
])

.directive('kfKeeps', [
  'keepService',
  function (keepService) {

    return {
      restrict: 'A',
      scope: {
        keeps: '=',
        keepsLoading: '=',
        keepsHasMore: '=',
        scrollDistance: '=',
        scrollDisabled: '=',
        scrollNext: '&'
      },
      controller: 'KeepsCtrl',
      templateUrl: 'keeps/keeps.tpl.html',
      link: function (scope /*, element, attrs*/ ) {
        keepService.reset();

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;
        scope.isSelected = keepService.isSelected;
        scope.preview = keepService.preview;
        scope.togglePreview = keepService.togglePreview;
        scope.isPreviewed = keepService.isPreviewed;

        scope.isShowMore = function () {
          return !scope.keepsLoading && scope.keepsHasMore;
        };

        scope.onClickKeep = function (keep, $event) {
          if ($event.target.tagName !== 'A') {
            if ($event.ctrlKey || $event.metaKey) {
              if (scope.isSelected(keep)) {
                scope.unselect(keep);
              } else {
                scope.select(keep);
              }
            } else {
              scope.togglePreview(keep);
            }
          }
        };

        scope.isScrollDisabled = function () {
          return scope.scrollDisabled;
        };

        if (scope.scrollDistance == null) {
          scope.scrollDistance = '100%';
        }
      }
    };
  }
]);
