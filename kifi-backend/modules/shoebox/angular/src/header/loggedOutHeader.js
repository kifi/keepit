'use strict';

angular.module('kifi')

.controller('LoggedOutHeaderCtrl', [
  '$scope', '$rootScope', '$state', '$timeout', '$location', '$window',
  'signupService', 'platformService', 'libraryService', 'routeService', 'util',
  function ($scope, $rootScope, $state, $timeout, $location, $window,
            signupService, platformService, libraryService, routeService, util) {
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
        $scope.libOwnerPicUrl = library && routeService.formatPicUrl(library.owner.id, library.owner.pictureName, 100);
        $scope.libOwnerProfileUrl = library && routeService.getProfileUrl(library.owner.username);

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
      $scope.search.focused = $window.document.activeElement === $event.target;
    };

    function reactToQueryChange() {
      var q = $scope.search.text;
      if (q) {
        if ($state.params.q) {
          $location.search('q', q).replace();
        } else {
          $location.url($scope.library.url + '/find?q=' + q + '&f=a');
        }
      } else {
        $location.url($scope.library.url);
      }
    }

    $scope.search.text = $state.params.q || '';
    $scope.onQueryChange = util.$debounce($scope, reactToQueryChange, 250);

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
