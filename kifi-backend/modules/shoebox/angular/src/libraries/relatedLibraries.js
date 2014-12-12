'use strict';

angular.module('kifi')

.directive('kfRelatedLibraries', [
  'libraryService', 'platformService', 'signupService',
  function (libraryService, platformService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        parentLibrary: '&',
        relatedLibraries: '='
      },
      templateUrl: 'libraries/relatedLibraries.tpl.html',
      link: function (scope/*, element, attrs*/) {
        var parentLibrary = scope.parentLibrary();
        scope.join = function ($event) {
          $event.preventDefault();

          libraryService.trackEvent('visitor_clicked_page', parentLibrary, {
            type: 'libraryLanding',
            action: 'clickedCreatedYourOwnJoinButton'
          });

          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register({ libraryId: parentLibrary.id });
          }
        };
      }
    };
  }
])

.directive('kfRelatedLibraryCard', [
  function () {
    return {
      restrict: 'A',
      replace: true,
      scope: {
        library: '='
      },
      templateUrl: 'libraries/relatedLibraryCard.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.clickLibraryReco = function ($event) {
          $event.target.href = scope.library.libraryUrl + '?o=lr';
          scope.$emit('trackLibraryEvent', 'click', { action: 'clickedLibraryRec' });
        };

        if (scope.library.followers.length > 0 && scope.library.otherFollowersCount > 0) {
          scope.followersToShow = _.take(scope.library.followers, 3);
        } else {
          scope.followersToShow = scope.library.followers;
        }

        scope.otherFollowersCount = scope.library.numFollowers - scope.followersToShow.length;
      }
    };
  }
])

;
