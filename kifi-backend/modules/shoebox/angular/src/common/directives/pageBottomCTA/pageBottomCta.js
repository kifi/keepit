'use strict';

angular.module('kifi')

.directive('kfPageBottomCta', ['$rootScope', '$window', 'signupService',
  function ($rootScope, $window, signupService) {
    return {
      restrict: 'A',
      replace: true,
      templateUrl: 'common/directives/pageBottomCTA/pageBottomCta.tpl.html',
      scope: {
        library: '='
      },
      link: function (scope) {
        scope.visible = false;
        scope.twitterHandle = scope.library.attr && scope.library.attr.twitter.screenName;

        scope.join = function ($event) {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedSignupPopup'
          });

          if (!scope.twitterHandle) {
            $event.preventDefault();
            scope.visible = false;
            signupService.register({libraryId: scope.library.id});
          }
        };

        scope.login = function () {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedLoginPopup'
          });
        };

        function onScroll() {
          if (window.pageYOffset > 400) {
            scope.$apply(function () {
              $rootScope.$emit('trackLibraryEvent', 'view', { type: 'libraryLandingPopup' });
              scope.visible = true;
              angular.element('.kf-lib-footer').css('padding-bottom', '300px');
              $window.removeEventListener('scroll', onScroll);
            });
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
