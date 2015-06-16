'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$analytics', '$location', '$state', '$stateParams', '$timeout', '$window',
  '$FB', '$twitter', 'env', 'util', 'URI', 'AB', 'initParams', 'library', 'libraryService', 'modalService',
  'platformService', 'profileService', 'originTrackingService', 'installService', 'libraryImageLoaded',
  function (
    $scope, $rootScope, $analytics, $location, $state, $stateParams, $timeout, $window,
    $FB, $twitter, env, util, URI, AB, initParams, library, libraryService, modalService,
    platformService, profileService, originTrackingService, installService, libraryImageLoaded) {

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
    $scope.libraryImageLoaded = libraryImageLoaded === true; // can also be an object containing a promise
    $scope.scrollDistance = '100%';
    $scope.loading = true;  // whether keeps are currently loading
    $scope.hasMore = true;   // whether there may be more keeps in this library than those currently in $scope.keeps
    $scope.isMobile = platformService.isSupportedMobilePlatform();
    $scope.passphrase = $scope.passphrase || {};
    $scope.$error = $scope.$error || {};
    $scope.userIsOwner = $rootScope.userLoggedIn && library.owner.id === profileService.me.id;
    $scope.edit = {
      enabled: false,
      actions: {
        bulkUnkeep: true,
        copyToLibrary: true,
        moveToLibrary: true
      }
    };


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
      $scope.keeps.push(augmentKeep(rawKeeps.shift()));
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

    function augmentKeep(keep) {
      keep.library = $scope.library;  // b/c library can vary for each keep card in the general case (e.g. search results)
      return keep;
    }

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
        href: env.origin + library.url +
          '?utm_medium=vf_facebook&utm_source=library_share&utm_content=lid_' + library.id +
          '&kcid=na-vf_facebook-library_share-lid_' + library.id
      });
    };

    $scope.preloadTwitter = function () {
      $twitter.load();
    };

    $scope.shareTwitter = function (event) {
      trackShareEvent('clickedShareTwitter');
      var absUrl = env.origin + library.url;
      event.target.href = 'https://twitter.com/intent/tweet' + URI.formatQueryString({
        original_referer: absUrl,
        text: 'Discover this amazing @Kifi library about ' + library.name + '!',
        tw_p: 'tweetbutton',
        url: absUrl +
          '?utm_medium=vf_twitter&utm_source=library_share&utm_content=lid_' + library.id +
          '&kcid=na-vf_twitter-library_share-lid_' + library.id
      });
    };

    $scope.showFeedModal = function () {
      libraryService.trackEvent('user_clicked_page', scope.library, { type: 'rss', action: 'clicked_subscribe_button'});
      modalService.open({
        template: 'libraries/libraryFeedModal.tpl.html',
        scope: $scope
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
          }

          var existingKeep = _.find($scope.keeps, {url: keep.url});
          if (!existingKeep && library.id === $scope.library.id) {
            $scope.keeps.unshift(augmentKeep(keep));
            existingKeep = keep;
          }

          // add the new keep to the keep card's "my keeps" array
          if (existingKeep && !_.find($scope.keeps, {id: keep.id})) {
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

    if (libraryImageLoaded.promise) {
      libraryImageLoaded.promise.then(function () {
        $scope.libraryImageLoaded = true;
      });
    }

    $rootScope.$emit('libraryOnPage', library);

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
      if (initParams.getAndClear('intent') === 'follow' && !$scope.library.membership) {
        // todo wtf why is this here and possible? no good.
        libraryService.joinLibrary($scope.library.id);
      }
    });

    libraryService.noteLibraryViewed(library.id);
  }
]);
