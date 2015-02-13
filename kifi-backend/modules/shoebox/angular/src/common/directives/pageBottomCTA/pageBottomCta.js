'use strict';

angular.module('kifi')

.directive('kfPageBottomCta', ['$rootScope', '$timeout', '$window', 'platformService', 'signupService',
  function ($rootScope, $timeout, $window, platformService, signupService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/pageBottomCTA/pageBottomCta.tpl.html',
      scope: {
        library: '='
      },
      link: function (scope /*element, attrs*/) {
        scope.show = false;

        scope.join = function ($event) {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedSignupPopup'
          });

          $event.preventDefault();
          scope.show = false;

          var userData = { libraryId: scope.library.id };
          if (platformService.isSupportedMobilePlatform()) {
            platformService.goToAppOrStore();
          } else {
            signupService.register(userData);
          }
        };

        scope.login = function ($event) {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedLoginPopup'
          });

          if (platformService.isSupportedMobilePlatform()) {
            $event.preventDefault();
            platformService.goToAppOrStore();
          }
        };

        function onScroll() {
          if (!scope.show) {
            $rootScope.$emit('trackLibraryEvent', 'view', { type: 'libraryLandingPopup' });
            scope.show = true;
            angular.element('.kf-lib-footer').css('padding-bottom', '270px');
          }
        }
        $window.addEventListener('scroll', onScroll);
        scope.$on('$destroy', function () {
          $window.removeEventListener('scroll', onScroll);
        });
      }
    };
  }
]);
