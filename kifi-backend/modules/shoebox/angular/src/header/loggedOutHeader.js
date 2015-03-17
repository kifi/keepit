'use strict';

angular.module('kifi')

.controller('LoggedOutHeaderCtrl', [
  '$scope', '$rootScope', '$state', '$timeout', '$location', '$window',
  'signupService', 'platformService', 'libraryService', 'util',
  function ($scope, $rootScope, $state, $timeout, $location, $window,
            signupService, platformService, libraryService, util) {
    $scope.library = null;
    $scope.search = {text: '', focused: false};
    $scope.isMobile = platformService.isSupportedMobilePlatform();

    $scope.clickLogo = function () {
      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', { callback: function (lib) {
          if (lib && lib.id) {
            libraryService.trackEvent('visitor_clicked_page', lib, {
              type: 'libraryLanding',
              action: 'clickedLogo'
            });
          }
        }});
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedLogo'
        });
      }
    };

    [
      $rootScope.$on('libraryOnPage', function (e, library) {
        $scope.library = library;

        if ($state.params && $state.params.q && util.startsWith($state.params.q, 'tag:')) {
          $scope.search.text = $state.params.q;
        }
      }),

      $rootScope.$on('$stateChangeSuccess', function (event, toState, toParams) {
        $scope.search.text = toState.name === 'library.search' ? toParams.q : '';
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.onInputFocus = function () {
      $scope.search.focused = true;
    };
    $scope.onInputBlur = function ($event) {
      $scope.search.focused = document.activeElement === $event.target;
    };

    function reactToQueryChange() {
      if ($state.is('library.search')) {
        $rootScope.$emit('searchTextUpdated', $scope.search.text, $scope.library.url);
      } else {
        $state.go('library.search', {q: $scope.search.text});
      }
    }

    // Use $state.params instead of $stateParams because this controller has no access
    // to the search parameters on the search controller via $stateParams.
    // See: http://stackoverflow.com/questions/23081397/ui-router-stateparams-vs-state-params
    $scope.search.text = $state.params.q || '';
    $scope.onQueryChange = util.$debounce($scope, reactToQueryChange, 250);

    $scope.$on('$destroy', $rootScope.$on('newQueryFromLocation', function (e, query) {
      $scope.search.text = query;
    }));

    $scope.onSearchBarClicked = function () {
      if ($scope.library && $scope.library.id) {
        $rootScope.$emit('trackLibraryEvent', 'click', {
          action: 'clickedSearchBar'
        });
      }
    };

    $scope.clearInput = function () {
      $scope.search.text = '';
      reactToQueryChange();
    };

    $scope.onKeydown = function (e) {
      switch (e.keyCode) {
        case 27:  // esc
          $scope.clearInput();
          break;
      }
    };

    $scope.join = function ($event) {
      $event.preventDefault();

      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', {
          callback: function (lib) {
            var userData;

            if (lib && lib.id) {
              userData = { libraryId: lib.id };

              libraryService.trackEvent('visitor_clicked_page', lib, {
                type: 'libraryLanding',
                action: 'clickedSignupHeader'
              });
            }

            if (platformService.isSupportedMobilePlatform()) {
              platformService.goToAppOrStore();
            } else {
              signupService.register(userData);
            }
          }
        });
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedSignupHeader'
        });

        if (platformService.isSupportedMobilePlatform()) {
          platformService.goToAppOrStore();
        } else {
          signupService.register();
        }
      }
    };

    $scope.login = function ($event) {
      if (platformService.isSupportedMobilePlatform()) {
        $event.preventDefault();
      }

      if (util.startsWith($state.current.name, 'library')) {
        $scope.$emit('getCurrentLibrary', {
          callback: function (lib) {
            if (lib && lib.id) {
              libraryService.trackEvent('visitor_clicked_page', lib, {
                type: 'libraryLanding',
                action: 'clickedLoginHeader'
              });
            }

            if (platformService.isSupportedMobilePlatform()) {
              platformService.goToAppOrStore();
            }
          }
        });
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedLoginHeader'
        });

        if (platformService.isSupportedMobilePlatform()) {
          platformService.goToAppOrStore();
        }
      }
    };
  }
]);
