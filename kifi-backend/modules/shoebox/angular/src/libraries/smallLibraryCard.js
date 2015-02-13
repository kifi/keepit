'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  '$location', 'modalService', '$window', 'platformService',
  function ($location, modalService, $window, platformService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        action: '@',
        origin: '@'
      },
      templateUrl: 'libraries/smallLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickCard = function () {
          $window.scrollTo(0, 0);
          scope.$emit('trackLibraryEvent', 'click', { action: scope.action });
        };

        scope.openFollowersList = function (lib) {
          if (platformService.isSupportedMobilePlatform()) {
            return;
          }

          modalService.open({
            template: 'libraries/libraryFollowersModal.tpl.html',
            modalData: {
              library: lib,
              currentPageOrigin: 'libraryPage.RelatedLibraries'
            }
          });
        };
      }
    };
  }
])

;
