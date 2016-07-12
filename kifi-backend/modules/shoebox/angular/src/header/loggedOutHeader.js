'use strict';

angular.module('kifi')

.controller('LoggedOutHeaderCtrl', [
  '$scope', '$rootScope', '$state', '$stateParams', '$timeout', '$location', '$document',
  'platformService', 'libraryService', 'orgProfileService', 'util',
  function ($scope, $rootScope, $state, $stateParams, $timeout, $location, $document,
            platformService, libraryService, orgProfileService, util) {
    $scope.library = null;
    $scope.search = {text: '', focused: false};

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
        $rootScope.$emit('searchTextUpdated', $scope.search.text, $scope.library.path || $scope.library.url);
      } else {
        $state.go('library.search', {q: $scope.search.text});
      }
    }

    // Use $state.params instead of $stateParams because this controller has no access
    // to the search parameters on the search controller via $stateParams.
    // See: http://stackoverflow.com/questions/23081397/ui-router-stateparams-vs-state-params
    $scope.search.text = $state.params.q || '';
    var authToken = $state.params.authToken;
    $scope.authTokenParam = authToken ? 'authToken='+authToken : '';
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
          $timeout(function () {  // Angular throws an exception if an event is triggered during $digest/$apply
            angular.element(document.activeElement).filter('.kf-loh-search-input').blur();
          });
          break;
      }
    };

    $scope.join = function () {
      var isMobile = platformService.isSupportedMobilePlatform();
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

            if (isMobile) {
              platformService.goToAppOrStore();
            }
          }
        });
      } else if (util.startsWith($state.current.name, 'userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedSignupHeader'
        });

        if (isMobile) {
          platformService.goToAppOrStore();
        }
      } else if ($state.includes('orgProfile.slack')) {
        orgProfileService.userOrOrg($stateParams.handle).then(function (resData) {
          if (resData.result) {
            orgProfileService.trackEvent('visitor_clicked_page', resData.result.organization, { type: 'orgLanding', action: 'clickedSignupHeader' });
          }
        });
      }
    };

    $scope.login = function ($event) {
      var isMobile = platformService.isSupportedMobilePlatform();
      if (isMobile) {
        $event.preventDefault();
      }

      if ($state.includes('library')) {
        $scope.$emit('getCurrentLibrary', {
          callback: function (lib) {
            if (lib && lib.id) {
              libraryService.trackEvent('visitor_clicked_page', lib, {
                type: 'libraryLanding',
                action: 'clickedLoginHeader'
              });
            }

            if (isMobile) {
              platformService.goToAppOrStore();
            }
          }
        });
      } else if ($state.includes('userProfile')) {
        $rootScope.$emit('trackUserProfileEvent', 'click', {
          action: 'clickedLoginHeader'
        });

        if (isMobile) {
          platformService.goToAppOrStore();
        }
      } else if ($state.includes('orgProfile.slack')) {
        orgProfileService.userOrOrg($stateParams.handle).then(function (resData) {
          if (resData.result) {
            orgProfileService.trackEvent('visitor_clicked_page', resData.result.organization, { type: 'orgLanding', action: 'clickedLoginHeader' });
          }
        });
      }
    };

    function onDocKeyDown(e) {
      if (e.which === 191) { // '/'
        if (!e.metaKey && !e.shiftKey && !e.altKey && !e.ctrlKey && !e.isDefaultPrevented() &&
            !angular.element(document.activeElement).is('input,textarea,[contenteditable],[contenteditable] *')) {
          e.preventDefault();
          angular.element('.kf-loh-search-input').focus();
        }
      }
    }
    $document.on('keydown', onDocKeyDown);
    $scope.$on('$destroy', function () {
      $document.off('keydown', onDocKeyDown);
    });
  }
]);
