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

        scope.select = keepService.select;
        scope.unselect = keepService.unselect;
        scope.toggleSelect = keepService.toggleSelect;
        scope.isSelected = keepService.isSelected;
        scope.preview = keepService.preview;
        scope.togglePreview = keepService.togglePreview;
        scope.isPreviewed = keepService.isPreviewed;

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
          if (e && e.currentTarget && e.currentTarget.activeElement && e.currentTarget.activeElement.tagName === 'BODY') {
            var captured = false;
            /* jshint maxcomplexity: false */
            switch (e.which) {
              case 13: // enter
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
