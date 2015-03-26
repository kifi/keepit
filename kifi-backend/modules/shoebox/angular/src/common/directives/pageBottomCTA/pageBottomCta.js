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
        scope.show = false;

        scope.title = 'There\'s more to see...';
        scope.subtitle = 'Sign up to see the rest of what\'s here!';
        // (todo) aaron: remove to release twitter waitlist
        //scope.twitterHandle = scope.library.attr && scope.library.attr.twitter.screenName;
        //scope.title = scope.twitterHandle ? 'Create a library of your tweeted links' : 'There\'s more to see...';
        //scope.subtitle = scope.twitterHandle ? 'Sign up for the Twitter Deep Search Beta' : 'Sign up to see the rest of what\'s here!';

        scope.join = function ($event, clickCase) {
          $rootScope.$emit('trackLibraryEvent', 'click', {
            action: 'clickedSignupPopup'
          });

          if (!clickCase) {
            $event.preventDefault();
            scope.show = false;
            signupService.register({libraryId: scope.library.id});
          }

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
            angular.element('.kf-lib-footer').css('padding-bottom', '300px');
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
