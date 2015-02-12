'use strict';

angular.module('kifi')

.directive('kfPageBottomCta', ['$timeout', '$window', 'platformService', 'signupService',
  function ($timeout, $window, platformService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/pageBottomCTA/pageBottomCta.tpl.html',
      scope: {
        library: '='
      },
      link: function (scope /*element, attrs*/) {
        scope.join = function ($event) {
          $event.preventDefault();
          var userData = { libraryId: scope.library.id };
          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register(userData);
          }
        };

        scope.login = function ($event) {
          if (platformService.isSupportedMobilePlatform()) {
            $event.preventDefault();
            platformService.goToAppOrStore();
          }
        };

        scope.show = false;

        function onScroll() {
          $timeout(function () {
            scope.show = true;
            angular.element('.kf-lib-footer').css('padding-bottom', '270px');
          }, 1000);
          $window.removeEventListener('scroll', onScroll);
        }

        $window.addEventListener('scroll', onScroll);
      }
    };
  }
]);
