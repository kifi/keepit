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
        scope.isMobile = platformService.isSupportedMobilePlatform();
        scope.show = false;

        scope.join = function ($event) {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedSignupPopup'
          });

          $event.preventDefault();
          scope.show = false;
          signupService.register({libraryId: scope.library.id});
        };

        scope.login = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedLoginPopup'
          });
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
