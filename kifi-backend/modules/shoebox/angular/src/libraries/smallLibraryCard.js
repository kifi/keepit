'use strict';

angular.module('kifi')

.directive('kfSmallLibraryCard', [
  '$location', 'modalService', '$window',
  function ($location, modalService, $window) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '=',
        origin: '@',
        action: '@'
      },
      templateUrl: 'libraries/smallLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickCard = function ($event) {
          $event.preventDefault();
          $location.path(scope.library.path).search('o', scope.origin);
          $window.scrollTo(0, 0);
          scope.$emit('trackLibraryEvent', 'click', { action: scope.action });
        };

        scope.openFollowersList = function (lib) {
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
