'use strict';

angular.module('kifi')

.controller('LibraryCtrl', [
  '$scope', '$rootScope', '$analytics', '$location', '$state', '$stateParams', '$timeout', '$window',
  'env', 'util', 'URI', 'AB', 'initParams', 'library', 'libraryService', 'modalService',
  'platformService', 'profileService', 'originTrackingService', 'installService',
  'libraryImageLoaded', 'slackService',
  function (
    $scope, $rootScope, $analytics, $location, $state, $stateParams, $timeout, $window,
    env, util, URI, AB, initParams, library, libraryService, modalService,
    platformService, profileService, originTrackingService, installService,
    libraryImageLoaded, slackService) {

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

    function setTitle() {
      if (!$scope.librarySearch) {  // search.js does it for library search
        $window.document.title = library.name + ' by ' + library.owner.firstName + ' ' + library.owner.lastName + ' • Kifi' ;
      }
    }

    function reloadThisLibrary() {
      $state.transitionTo($state.current, $stateParams, {reload: true, inherit: false, notify: true});
    }


    var SlackIntegration = function(serverSlackIntegration) {
      this.data = serverSlackIntegration;
      this.library = library;

      this.keepToSlack = this.data.toSlack && this.data.toSlack.status === 'on';

      this.slackToKeep = this.data.fromSlack && this.data.fromSlack.status === 'on';

      if (this.data.space) {
        if (this.data.space.user) {
          // can't see other user integrations for now
          this.spaceName = 'Your';
        } else {
          var orgs = profileService.me.orgs;
          for (var i = 0; i < orgs.length; i++) {
            if (orgs[i].id === this.data.space.org) {
              this.spaceName = orgs[i].name;
            }
          }
        }
      }

      this.onKeepToSlackChanged = function(on) {
        this.data.toSlack.status = on ? 'on' : 'off';
        this.data.toSlack.space = this.data.space;
        slackService.modifyLibraryPushIntegration(this.library.id, this.data.toSlack.id, on)
        .then(function (resp) {
          if (resp.success) {
            return;
          } else if (resp.redirect && resp.redirect.indexOf('showSlackDialog') === -1) {
            $window.location = resp.redirect;
          }
        });
      };

      this.onSlackToKeepChanged = function(on) {
        this.data.fromSlack.status = $scope.canAddKeepsToLibrary && on ? 'on' : 'off';
        this.data.fromSlack.space = this.data.space;
        slackService.modifyLibraryIngestIntegration(this.library.id, this.data.fromSlack.id, on)
        .then(function (resp) {
          if (resp.success) {
            return;
          } else if (resp.redirect && resp.redirect.indexOf('showSlackDialog') === -1) {
            $window.location = resp.redirect;
          }
        });
      };

      this.getChannelName = function() {
        if (this.data.channelName[0] === '@') {
          return this.data.channelName;
        } else {
          return '#' + this.data.channelName.replace('#', '');
        }
      };

      this.guardDeleteIntegration = function() {
        var self = this;
        modalService.open({
          template: 'common/modal/simpleModal.tpl.html',
          modalDefaults: {
            title: 'Delete Integration?',
            content: 'Are you sure you\'d like to delete this integration?',
            centered: true,
            withCancel: true,
            actionText: 'Yes',
            action: function() {
              self.deleteIntegration();
            }
          }
        });
      };

      this.getSpaceMenuItems = function() {
        var currSpaceId = this.data.space.user || this.data.space.org;
        var orgs = profileService.me.orgs;
        var items = [];
        if (profileService.me.id !== currSpaceId) {
          items.push({type: 'me', name: 'Your Profile', id: profileService.me.id, userOrOrg: profileService.me});
        }

        orgs.forEach(function(org) {
          if (((org.viewer && org.viewer.permissions) || []).indexOf('create_slack_integration') !== -1
              && currSpaceId !== org.id) {
            items.push({type: 'org', name: org.name, id: org.id, userOrOrg: org});
          }
        });
        return items;
      };

      this.menuItems = this.getSpaceMenuItems();

      this.deleteIntegration = function() {
        var ids = [];
        if (this.data.toSlack) {
          ids.push(this.data.toSlack.id);
        }
        if (this.data.fromSlack) {
          ids.push(this.data.fromSlack.id);
        }
        libraryService.deleteSlackIntegrations(this.library.id, ids);
        $scope.slackIntegrations.splice($scope.slackIntegrations.indexOf(this), 1);
      };

      this.deleteUrl = function() {
        return '/';
      };

    };

    var me = profileService.me;

    //
    // Scope data.
    //
    $scope.librarySearch = $state.current.name === 'library.search';
    $scope.username = $stateParams.handle;
    $scope.librarySlug = $stateParams.librarySlug;
    $scope.keeps = [];
    $scope.library = library;
    $scope.libraryImageLoaded = libraryImageLoaded === true; // can also be an object containing a promise
    $scope.scrollDistance = '100%';
    $scope.loading = true;  // whether keeps are currently loading
    $scope.hasMore = true;   // whether there may be more keeps in this library than those currently in $scope.keeps
    $scope.isMobile = platformService.isSupportedMobilePlatform();
    $scope.$error = $scope.$error || {};
    $scope.userIsOwner = $rootScope.userLoggedIn && library.owner.id === me.id;
    $scope.isAdminExperiment = (me.experiments || []).indexOf('admin') !== -1;
    $scope.canAddKeepsToLibrary = (
      library.membership && (
        library.membership.access === 'owner' ||
        library.membership.access === 'read_write'
      )
    ) || (
      (me.orgs || []).filter(function (org) { return library.org && library.org.id === org.id; }).length > 0 &&
      library.orgMemberAccess === 'read_write'
    );

    // slack stuff
    $scope.slackIntegrations = [];

    $scope.sortSlackIntegrations = function() {
      var integrations = $scope.slackIntegrations;
      var buckets = {};

      integrations.forEach(function(integration) {
        var space = null;
        if (integration.data.space) {
          space = integration.data.space.org || integration.data.space.user;
        }
        if (space) {
          if (!buckets[space]) {
            buckets[space] = [];
          }
          buckets[space].push(integration);
        }

      });

      integrations = [];
      Object.keys(buckets).forEach(function (space) {
        integrations = integrations.concat(buckets[space]);
      });

      $scope.slackIntegrations = integrations;


    };

    $scope.processSlackIntegrations = function() {
      //var i = JSON.parse(JSON.stringify(library.slack.integrations[0]));
      //i.space = {'user': profileService.me.id};
      //library.slack.integrations.push(i);
      //library.slack.integrations.push(JSON.parse(JSON.stringify(library.slack.integrations[0])));
      if (library.slack && library.slack.integrations) {
        var integrations = library.slack.integrations.map(function(integration) {
          return new SlackIntegration(integration);
        });

        $scope.slackIntegrations = integrations;
        $scope.sortSlackIntegrations();
      }
    };

    $scope.processSlackIntegrations();


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

    $scope.slackIntegrationHasHeader = function(index) {
      if (index === 0) {
        return true;
      }

      var last = $scope.slackIntegrations[index - 1];
      var curr = $scope.slackIntegrations[index];

      var lastId = last.data.space.user || last.data.space.org;
      var currId = curr.data.space.user || curr.data.space.org;

      return lastId !== currId;
    };

    $scope.addChannel = function() {
      slackService.getAddIntegrationLink(library.id).then(function (link) {
        if (link) {
          $window.location = link.redirect;
        }
      });
    };

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
      var eventAction = event.currentTarget.getAttribute('click-action');
      $rootScope.$emit('trackLibraryEvent', 'click', { action: eventAction, keepView: $scope.galleryView ? 'gallery' : 'list' });
      var eventType = profileService.userLoggedIn() ? 'user_viewed_content' : 'visitor_viewed_content';
      $analytics.eventTrack(eventType, { source: 'library', contentType: 'keep', keepId: keep.id, libraryId: library.id,
        orgId: library.org && library.org.id });
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

    $scope.showUnableToKeepFromSlackModal = function(){
      modalService.open({
        template: 'common/modal/simpleModal.tpl.html',
        modalDefaults: {
          title: 'Oops!',
          content: 'You aren\'t allowed to keep to a library unless you are the owner or a collaborator on the library',
          centered: true,
          actionText: 'OK'
        }
      });
    };


    $scope.openSlackIntegrations = function() {
      modalService.open({
        template: 'libraries/modals/librarySlackIntegrationModal.tpl.html',
        scope: $scope
      });
    };

    $scope.processSlackRequest = function() {
      libraryService.trackEvent('user_clicked_page', $scope.library, { type: 'slack', action: 'clicked_slack_button'});
      // queue random logic
      if (!$rootScope.userLoggedIn) {
        // handle user needs to log in
        $scope.asd = 'asd';
      } else if (profileService.me && profileService.me.orgs.length > 0) {

        if ((library.permissions || []).indexOf('create_slack_integration') !== -1) {
          if (library.slack && library.slack.integrations && library.slack.integrations.length > 0) {
            $scope.openSlackIntegrations();
          } else {
            $scope.addChannel();
          }
        } else {
          // show ask for more info modal
          modalService.open({
            template: 'libraries/modals/librarySlackNoSlackIntegrationPermissionFoundModal.tpl.html',
            scope: $scope
          });
        }
      } else {
        modalService.open({
          template: 'libraries/modals/librarySlackCreateTeamNeededModal.tpl.html',
          scope: $scope
        });
      }

    };

    // query param handling
    var showSlackDialog = $stateParams.showSlackDialog || initParams.getAndClear('showSlackDialog');
    if (showSlackDialog) {
      $scope.processSlackRequest();
    }

    $scope.showFeedModal = function () {
      libraryService.trackEvent('user_clicked_page', $scope.library, { type: 'rss', action: 'clicked_subscribe_button'});
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

          var existingKeep = _.find($scope.keeps, function (k) {
            return k.url === keep.url || k.id === keep.id;
          });

          if (library.id === $scope.library.id) {
            if (!existingKeep || new Date(existingKeep.createdAt) !== new Date(keep.createdAt)) {
              // New keep, or existing keep that has changed times
              if (existingKeep) {
                $scope.keeps.splice($scope.keeps.indexOf(existingKeep), 1);
              }
              var idx = _.sortedIndex($scope.keeps, keep, function (k) {
                return +new Date(k.createdAt) * -1;
              });
              existingKeep = augmentKeep(keep);
              $scope.keeps.splice(idx, 0, existingKeep);
            }
          }

          // add the new keep to the keep card's 'my keeps' array
          if (existingKeep && !_.find(existingKeep.keeps, {id: keep.id})) {
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

    $timeout(function() {
      libraryService.trackEvent('user_viewed_page', $scope.library, {
        type: 'library',
        keepView: $scope.galleryView ? 'gallery' : 'list',
        libraryRecCount: $scope.relatedLibraries && $scope.relatedLibraries.length
      });
    }, 500);

    var deregisterUpdateLibrary;
    var knownUpdatesPending = 0;
    var updateLibrary = function (interval, countSinceUpdate) {
      countSinceUpdate = countSinceUpdate || 0;
      deregisterUpdateLibrary = $timeout(function () {
        var lastKnownUpdate = getLastKnownUpdate();
        libraryService.checkLibraryForUpdates($scope.library.id, lastKnownUpdate).then(function (res) {
          var status = res.data.updates;
          if (status.updates > knownUpdatesPending) { // An update we don't know about has occured
            knownUpdatesPending = status.updates;
            $scope.$broadcast('keepUpdatesPending', knownUpdatesPending);
            updateLibrary(10000, 0);
          } else if (countSinceUpdate < 1440) { // Roughly a day of no updates
            $scope.$broadcast('keepUpdatesPending', status.updates);
            updateLibrary(Math.min(60000, interval + 10000), countSinceUpdate + 1);
          } else {
            clickToRefresh();
          }
        })['catch'](clickToRefresh);
      }, interval);

      function clickToRefresh() {
        $timeout.cancel(deregisterUpdateLibrary);
        knownUpdatesPending = -1;
        $scope.$broadcast('keepUpdatesPending', -1);
      }
    };

    $scope.$on('refreshLibrary', function () {
      if (knownUpdatesPending <= 0 || knownUpdatesPending >= 10) { // error, or far too many
        $state.reload();
      } else {
        libraryService.getKeepsInLibrary(library.id, 0, $stateParams.authToken).then(function (res) {
          var keeps = res.keeps.slice().reverse(); // reversing, because they're pushed to the top, so oldest first
          $rootScope.$emit('keepAdded', keeps, library);
          $timeout.cancel(deregisterUpdateLibrary);

          $scope.library.lastKept = (res.keeps && res.keeps[0] && res.keeps[0].createdAt) || $scope.library.lastKept || $scope.library.modifiedAt;
          updateLibrary(10000);
        });
      }

      $scope.$broadcast('keepUpdatesPending', 0);
      knownUpdatesPending = 0;
    });

    $scope.$on('$destroy', function () {
      $timeout.cancel(deregisterUpdateLibrary);
    });

    var getLastKnownUpdate = function () {
      var newestKeep = _.max($scope.keeps, function (k) {
        return +new Date(k.createdAt);
      });
      return newestKeep.createdAt || $scope.library.lastKept || $scope.library.modifiedAt;
    };
    updateLibrary(10000);
  }
]);
