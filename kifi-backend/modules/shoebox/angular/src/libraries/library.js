'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$analytics', '$location', '$state', '$stateParams', '$timeout', '$window', 'util', 'initParams', 'library',
  'keepDecoratorService', 'libraryService', 'modalService', 'platformService', 'profileService', 'originTrackingService', 'installService',
  function ($scope, $rootScope, $analytics, $location, $state, $stateParams, $timeout, $window, util, initParams, library,
    keepDecoratorService, libraryService, modalService, platformService, profileService, originTrackingService, installService) {
    //
    // A/B Tests.
    //
    var abTest = {
      name: 'exp_follow_popup',
      salt: 'hgg1dv',
      treatments: [
        {
          name: 'none',
          isControl: true
        },
        {
          name: 'popupLibrary',
          data: {
            buttonText: 'Follow',
            mainText: 'Join Kifi to follow this library.<br/>Discover other libraries,<br/>and build your own!',
            quote: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
            quoteAttribution: 'Remy Weinstein, California'
          }
        },
        {
          name: 'popupCollection',
          data: {
            buttonText: 'Save',
            mainText: 'Join Kifi to save this collection.<br/>Discover other collections,<br/>and build your own!',
            quote: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
            quoteAttribution: 'Remy Weinstein, California'
          }
        }
      ]
    };


    //
    // Internal data.
    //
    var selectedCount = 0;

    //
    // Internal functions
    //
    function showInstallModal() {
      $scope.platformName = installService.getPlatformName();
      if ($scope.platformName) {
        $scope.thanksVersion = 'installExt';
        $scope.installExtension = function() {
          $analytics.eventTrack('visitor_clicked_page', {type: 'installLibrary', action: 'install'});
          installService.triggerInstall();
        };
      } else {
        $scope.thanksVersion = 'notSupported';
      }

      if (library.id) {
        $rootScope.$emit('trackLibraryEvent', 'view', { type: 'installLibrary' });
      }

      $scope.close = function () {
        $analytics.eventTrack('visitor_clicked_page', {type : 'installLibrary', action: 'close'});
        modalService.close();
      };

      modalService.open({
        template: 'signup/thanksForRegisteringModal.tpl.html',
        scope: $scope
      });
    }

    function showPersonaModal() {
      modalService.open({
        template: 'persona/managePersonaUnescapableModal.tpl.html',
        modalData: {
          onClose: function() {
            if (!installService.installedVersion) {
              showInstallModal();
            }
          },
          finishText: 'Done'
        }
      });
    }

    function trackPageView(attributes) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();

      attributes = _.extend(libraryService.getCommonTrackingAttributes(library), attributes);
      attributes = originTrackingService.applyAndClear(attributes);
      if ($rootScope.userLoggedIn) {
        attributes.owner = $scope.userIsOwner ? 'Yes' : 'No';
      }

      $analytics.pageTrack(url, attributes);
    }

    function setTitle(lib) {
      $window.document.title = lib.name + ' by ' + lib.owner.firstName + ' ' + lib.owner.lastName + ' • Kifi' ;
    }

    function reloadThisLibrary() {
      $state.transitionTo($state.current, $stateParams, {reload: true, inherit: false, notify: true});
    }

    //
    // Scope data.
    //
    $scope.librarySearch = $state.current.name === 'library.search';
    $scope.username = $stateParams.username;
    $scope.librarySlug = $stateParams.librarySlug;
    $scope.keeps = [];
    $scope.library = library;
    $scope.scrollDistance = '100%';
    $scope.loading = true;  // whether keeps are currently loading
    $scope.hasMore = true;   // whether there may be more keeps in this library than those currently in $scope.keeps
    $scope.isMobile = platformService.isSupportedMobilePlatform();
    $scope.passphrase = $scope.passphrase || {};
    $scope.$error = $scope.$error || {};
    $scope.userIsOwner = $rootScope.userLoggedIn && library.owner.id === profileService.me.id;


    //
    // Scope methods.
    //

    $scope.getNextKeeps = function (offset) {
      if ($scope.loading || $scope.keeps.length === 0) {
        return;
      }
      $scope.loading = true;
      return libraryService.getKeepsInLibrary(library.id, offset, $stateParams.authToken).then(function (res) {
        var rawKeeps = res.keeps;
        rawKeeps.forEach(function (rawKeep) {
          var keep = new keepDecoratorService.Keep(rawKeep);
          keep.buildKeep(keep);
          keep.makeKept();

          $scope.keeps.push(keep);
        });

        $scope.hasMore = $scope.keeps.length < library.numKeeps;
        $scope.loading = false;

        // Preload for non-mobile public pages
        if (!$rootScope.userLoggedIn && $scope.hasMore && $scope.keeps.length < 30 && !platformService.isSupportedMobilePlatform()) {
          $scope.$evalAsync($scope.getNextKeeps.bind(null, $scope.keeps.length));
        }

        return $scope.keeps;
      });
    };

    $scope.getSubtitle = function () {
      if ($scope.loading) {
        return 'Loading...';
      }

      // If there are selected keeps, display the number of keeps
      // in the subtitle.
      if (selectedCount > 0) {
        return (selectedCount === 1) ? '1 Keep selected' : selectedCount + ' Keeps selected';
      }

      var numShown = $scope.keeps.length;
      switch (numShown) {
      case 0:
        return 'No Keeps';
      case 1:
        return 'Showing the only Keep';
      case 2:
        return 'Showing both Keeps';
      }
      return 'Showing ' + numShown + ' of ' + library.numKeeps + ' Keeps';
    };

    $scope.updateSelectedCount = function (numSelected) {
      selectedCount = numSelected;
    };

    $scope.libraryKeepClicked = function (keep, event) {
      var eventAction = event.target.getAttribute('click-action');
      $rootScope.$emit('trackLibraryEvent', 'click', { action: eventAction });
    };

    $scope.callAddKeep = function () {
      $rootScope.$emit('triggerAddKeep', library);
    };
    $scope.callImportBookmarks = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarks');
    };
    $scope.callImportBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile');
    };
    $scope.callTriggerInstall = function () {
      $rootScope.$emit('triggerExtensionInstall');
    };


    //
    // Watches and listeners.
    //
    [
      $rootScope.$on('keepAdded', function (e, libSlug, keeps, library) {
        keeps.forEach(function (keep) {
          // checks if the keep was added to the secret library from main or
          // vice-versa.  If so, it removes the keep from the current library
          if ((libSlug === 'secret' && $scope.librarySlug === 'main') ||
              (libSlug === 'main' && $scope.librarySlug === 'secret')) {
            var idx = _.findIndex($scope.keeps, { url: keep.url });
            if (idx > -1) {
              $scope.keeps.splice(idx, 1);
            }
          } else if (libSlug === $scope.librarySlug) {
            $scope.keeps.unshift(keep);
          }

          // add the new keep to the keep card's "my keeps" array
          var existingKeep = _.find($scope.keeps, { url: keep.url });
          if (existingKeep && !_.find($scope.keeps, { id: keep.id })) {
            existingKeep.keeps.push({
              id: keep.id,
              isMine: true,
              libraryId: library.id,
              mine: true,
              visibility: library.visibility
            });
          }
        });
      }),

      $rootScope.$on('getCurrentLibrary', function (e, args) {
        args.callback($scope.library);
      }),

      $rootScope.$on('trackLibraryEvent', function (e, eventType, attributes) {
        attributes.libraryRecCount = $scope.relatedLibraries ? $scope.relatedLibraries.length : 0;
        if (eventType === 'click') {
          if (!$rootScope.userLoggedIn) {
            attributes.type = attributes.type || 'libraryLanding';
            libraryService.trackEvent('visitor_clicked_page', $scope.library, attributes);
          } else {
            attributes.type = attributes.type || 'library';
            libraryService.trackEvent('user_clicked_page', $scope.library, attributes);
          }
        } else if (eventType === 'view') {
          trackPageView(attributes);
        }
      }),

      $rootScope.$on('$stateChangeSuccess', function (e, toState) {
        $scope.librarySearch = toState.name === 'library.search';
        setTitle(library);
      }),

      $rootScope.$on('userLoggedInStateChange', function () {
        reloadThisLibrary();
      })

    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });


    //
    // Initialize.
    //

    setTitle(library);

    $rootScope.$emit('libraryUrl', library);
    $rootScope.$broadcast('relatedLibrariesChanged', []);

    if (library.kind === 'user_created' && library.access !== 'none') {
      $rootScope.$emit('lastViewedLib', library);
    }

    // dealing with keeps asynchronously to allow header to be drawn
    $timeout(function () {
      library.keeps.forEach(function (rawKeep) {
        var keep = new keepDecoratorService.Keep(rawKeep);
        keep.buildKeep(keep);
        keep.makeKept();
        $scope.keeps.push(keep);
      });

      $scope.loading = false;
      $scope.hasMore = library.keeps.length < library.numKeeps;
      if ($scope.hasMore) {
        $scope.$evalAsync($scope.getNextKeeps.bind(null, library.keeps.length)); // fetch the next page
      }
    });

    libraryService.getRelatedLibraries(library.id).then(function (libraries) {
      trackPageView({libraryRecCount: libraries.length});
      $scope.relatedLibraries = libraries;
      $rootScope.$broadcast('relatedLibrariesChanged', libraries);

      //if (initParams.install === '1' && !installService.installedVersion) {
        //showInstallModal();
        //}
      if (initParams.install === '1') {
        showPersonaModal();
        initParams.install = undefined;
      }
      if (initParams.intent === 'follow' && $scope.library.access === 'none') {
        libraryService.joinLibrary($scope.library.id);
      }
    });

    library.abTest = abTest;
    library.abTestTreatment = util.chooseTreatment(library.abTest.salt, library.abTest.treatments);
  }
]);
