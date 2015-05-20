'use strict';

angular.module('kifi')

.directive('kfLibraryPassPhrase', [
  '$rootScope', '$state', '$timeout', 'libraryService',
  function ($rootScope, $state, $timeout, libraryService) {
    return {
      restrict: 'A',
      templateUrl: 'libraries/libraryPassPhrase.tpl.html',
      scope: {
        errorParams: '='
      },
      link: function (scope, element) {

        function reloadThisLibrary() {
          $state.transitionTo('library.keeps', scope.errorParams, {reload: true, inherit: false, notify: true});
        }

        //
        // Scope methods.
        //
        scope.submitPassPhrase = function () {
          var params = scope.errorParams;
          libraryService.authIntoLibrary(params.username, params.librarySlug, params.authToken, scope.passphrase.value.toLowerCase())
          .then(reloadThisLibrary)
          ['catch'](function () {
            scope.showErrorMessage = true;
            $timeout(function () {
              scope.showErrorMessage = false;
            }, 4200);
          });
        };


        //
        // Watches and listeners.
        //
        var deregister = $rootScope.$on('userLoggedInStateChange', function (e, loggedIn) {
          if (!loggedIn) {
            reloadThisLibrary();
          }
        });
        scope.$on('$destroy', deregister);


        //
        // Initialize.
        //

        element.find('input').first().focus();
      }
    };
  }
]);
