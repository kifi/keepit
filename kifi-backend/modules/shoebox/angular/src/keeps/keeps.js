'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService'])

.controller('KeepsCtrl', [
  '$scope', 'profileService', 'keepService', 'tagService',
  function ($scope, profileService, keepService, tagService) {
    $scope.me = profileService.me;

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
  'keepService', '$document', '$log',
  function (keepService, $document, $log) {

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
      link: function (scope, element /*, attrs*/ ) {
        keepService.reset();

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;
        scope.isSelected = keepService.isSelected;
        scope.preview = keepService.preview;
        scope.togglePreview = keepService.togglePreview;
        scope.isPreviewed = keepService.isPreviewed;

        function bringCardIntoViewUp() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          if (offset.top < 250) {
            var next = elem.parent().prev().prev() || elem.parent().prev() || elem.parent();
            if (next[0]) {
              next[0].scrollIntoView(true);
            }
          }
        }

        function bringCardIntoViewDown() {
          var elem = element.find('.detailed');
          var offset = elem.offset();
          var wrapperHeight = element.find('.keeps-wrapper').height();

          if (offset.top > wrapperHeight - 100) {
            var next = elem.parent().next().next() || elem.parent().next() || elem.parent();
            if (next[0]) {
              next[0].scrollIntoView(false);
            }
          }
        }

        function keepKeyBindings(e) {
          if (e && e.currentTarget && e.currentTarget.activeElement && e.currentTarget.activeElement.tagName === 'BODY') {
            var captured = false;
            /* jshint maxcomplexity: false */
            switch (e.which) {
              case 13:
                var p = keepService.getHighlighted();
                keepService.togglePreview(p);
                captured = true;
                break;
              case 27: // esc
                if (keepService.isDetailOpen()) {
                  keepService.clearState();
                  captured = true;
                }
                break;
              case 38: // up
              case 75: // k
                keepService.previewPrev();
                bringCardIntoViewUp();
                captured = true;
                break;
              case 40: // down
              case 74: // j
                keepService.previewNext();
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
          keepService.clearState();
          $document.off('keydown', keepKeyBindings);
        });

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
