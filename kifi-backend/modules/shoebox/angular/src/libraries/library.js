'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$analytics', '$location', '$state', '$stateParams', '$timeout', '$window',
  '$FB', '$twitter', 'util', 'initParams', 'library', 'keepDecoratorService', 'libraryService', 'modalService',
  'platformService', 'profileService', 'originTrackingService', 'installService',
  function (
    $scope, $rootScope, $analytics, $location, $state, $stateParams, $timeout, $window,
    $FB, $twitter, util, initParams, library, keepDecoratorService, libraryService, modalService,
    platformService, profileService, originTrackingService, installService) {

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

    function trackPageView(attributes) {
      var url = $analytics.settings.pageTracking.basePath + $location.url();

      attributes = _.extend(libraryService.getCommonTrackingAttributes(library), attributes);
      attributes = originTrackingService.applyAndClear(attributes);
      if ($rootScope.userLoggedIn) {
        attributes.owner = $scope.userIsOwner ? 'Yes' : 'No';
      }

      $analytics.pageTrack(url, attributes);
    }

    function trackShareEvent(action) {
      $timeout(function () {
        $rootScope.$emit('trackLibraryEvent', 'click', { action: action });
      });
    }

    function setTitle() {
      if (!$scope.librarySearch) {  // search.js does it for library search
        $window.document.title = library.name + ' by ' + library.owner.firstName + ' ' + library.owner.lastName + ' • Kifi' ;
      }
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
      libraryService.getKeepsInLibrary(library.id, offset, $stateParams.authToken).then(function (res) {
        var rawKeeps = res.keeps;
        if (rawKeeps.length) {
          $timeout(angular.bind(null, renderNextRawKeep, res.keeps.slice()));
        } else {
          $scope.hasMore = false;
          onDoneWithBatchOfRawKeeps();
        }
      });
    };

    function renderNextRawKeep(rawKeeps) {
      var keep = new keepDecoratorService.Keep(rawKeeps.shift());
      keep.buildKeep(keep);
      keep.makeKept();
      $scope.keeps.push(keep);
      if (rawKeeps.length) {
        $timeout(angular.bind(null, renderNextRawKeep, rawKeeps));
      } else {
        onDoneWithBatchOfRawKeeps();
      }
    }

    function onDoneWithBatchOfRawKeeps() {
      $scope.loading = false;
      $scope.hasMore = $scope.hasMore && $scope.keeps.length < library.numKeeps;

      // auto-load more irrespective of scrolling in some cases
      if ($scope.hasMore && !platformService.isSupportedMobilePlatform()) {
        var numLoaded = $scope.keeps.length;
        if (numLoaded < 20 || numLoaded < 30 && !$rootScope.userLoggedIn) {
          $timeout($scope.getNextKeeps.bind(null, numLoaded));
        }
      }
    }

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
      $rootScope.$emit('triggerAddKeep');
    };
    $scope.callImportBookmarks = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarks', {library: library});
    };
    $scope.callImportBookmarkFile = function () {
      $rootScope.$emit('showGlobalModal', 'importBookmarkFile', {library: library});
    };
    $scope.triggerInstall = function () {
      installService.triggerInstall(function () {
        modalService.open({
          template: 'common/modal/installExtensionErrorModal.tpl.html'
        });
      });
    };

    $scope.preloadFB = function () {
      $FB.init();
    };

    $scope.shareFB = function () {
      trackShareEvent('clickedShareFacebook');
      $FB.ui({
        method: 'share',
        href: library.absUrl +
          '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + library.id +
          '&kcid=na-vf_facebook-library_share-lid_' + library.id
      });
    };

    $scope.preloadTwitter = function () {
      $twitter.load();
    };

    $scope.shareTwitter = function (event) {
      trackShareEvent('clickedShareTwitter');
      event.target.href = 'https://twitter.com/intent/tweet' + util.formatQueryString({
        original_referer: library.absUrl,
        text: 'Discover this amazing @Kifi library about ' + library.name + '!',
        tw_p: 'tweetbutton',
        url: library.absUrl +
          '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + library.id +
          '&kcid=na-vf_twitter-library_share-lid_' + library.id
      });
    };

    //
    // Watches and listeners.
    //
    [
      $rootScope.$on('keepAdded', function (e, keeps, library) {
        keeps.forEach(function (keep) {
          // if the keep was added to the secret library from main or vice-versa, removes the keep from the current library
          if (library.kind === 'system_secret' && $scope.librarySlug === 'main' ||
              library.kind === 'system_main' && $scope.librarySlug === 'secret') {
            var idx = _.findIndex($scope.keeps, { url: keep.url });
            if (idx > -1) {
              $scope.keeps.splice(idx, 1);
            }
          } else if (library.id === $scope.library.id) {
            $scope.keeps.unshift(keep);
          }

          // add the new keep to the keep card's "my keeps" array
          var existingKeep = _.find($scope.keeps, { url: keep.url });
          if (existingKeep && !_.find($scope.keeps, { id: keep.id })) {
            existingKeep.keeps.push({
              id: keep.id,
              libraryId: library.id,
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
        setTitle();
      }),

      $rootScope.$on('$stateChangeStart', function (e, toState) {
        if (!util.startsWith(toState.name, 'library.')) {
          $rootScope.$emit('libraryOnPage', null);
        }
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

    setTitle();

    $rootScope.$emit('libraryOnPage', library);

    if (!libraryService.isLibraryMainOrSecret(library) && library.access !== 'none') {
      $rootScope.$emit('lastViewedLib', library);
    }

    if (library.keeps.length) {
      // dealing with keeps asynchronously, one by one, to allow header to be drawn
      $timeout(angular.bind(null, renderNextRawKeep, library.keeps.slice()));
    } else {
      onDoneWithBatchOfRawKeeps();
    }

    libraryService.getRelatedLibraries(library.id).then(function (libraries) {
      trackPageView({libraryRecCount: libraries.length});
      $scope.relatedLibraries = libraries;
      if (initParams.getAndClear('install') === '1' && !installService.installedVersion) {
        showInstallModal();
      }
      if (initParams.getAndClear('intent') === 'follow' && $scope.library.access === 'none') {
        libraryService.joinLibrary($scope.library.id);
      }
    });

    if (!$rootScope.userLoggedIn) {
      library.abTest = {
        name: 'exp_follow_popup',
        salt: 'hgg1dv',
        treatments: [
          {
            name: 'none'
          },
          {
            name: 'popupLibrary',
            data: {
              buttonText: 'Follow',
              mainHtml: 'Join Kifi to follow this library.<br/>Discover other libraries,<br/>and build your own!',
              quoteHtml: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
              quoteAttribution: 'Remy Weinstein, California'
            }
          },
          {
            name: 'popupCollection',
            data: {
              buttonText: 'Save',
              mainHtml: 'Join Kifi to save this collection.<br/>Discover other collections,<br/>and build your own!',
              quoteHtml: 'From business to personal, Kifi has been<br/>instrumental in my day-to-day life.',
              quoteAttribution: 'Remy Weinstein, California'
            }
          }
        ]
      };
      library.abTestTreatment = util.chooseTreatment(library.abTest.salt, library.abTest.treatments);
    }
  }
]);
