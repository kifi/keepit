'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$window', '$rootScope', '$scope', '$stateParams', '$location', '$q', '$timeout',
  '$analytics', 'feedService', 'Paginator', 'routeService', 'modalService', 'profileService',
  function($window, $rootScope, $scope, $stateParams, $location, $q, $timeout,
   $analytics, feedService, Paginator, routeService, modalService, profileService) {
    function feedSource(pageNumber, pageSize) {
      var lastKeep = $scope.feed[$scope.feed.length - 1];

      function tryGetFullPage(limit, streamEnd, deferred, results) {
        deferred = deferred || $q.defer();
        results = results || [];

        feedService.getFeed(limit, streamEnd && streamEnd.id, null, $scope.feedFilter.selected).then(function (keepData) {
          results = results.concat(keepData.keeps);

          if (keepData.keeps.length === 0 || results.length >= limit) {
            deferred.resolve(results.slice(0, limit));
          } else { // keepData.length !== 0 && results.length < pageSize
            tryGetFullPage(limit, results[results.length - 1], deferred, results);
          }
        })
        ['catch'](deferred.reject);

        return deferred.promise;
      }

      return tryGetFullPage(pageSize, lastKeep);
    }

    var feedLazyLoader = new Paginator(feedSource, 10, Paginator.DONE_WHEN_RESPONSE_IS_EMPTY);

    $scope.feed = [];

    var me = profileService.me;
    $scope.isAdmin = profileService.isAdmin();
    var orgs = me.orgs || [];

    $scope.feedFilter = {};
    var orgFilterOptions = orgs.map(function(org) {
      return { value: 'org', id: org.id, text: org.name, handle: org.handle };
    });

    var canSeeMessageFilters = me.experiments && me.experiments.indexOf('discussion_feed_filters') !== -1;
    var messageFilters = [
      { value: 'all', text: 'All Discussions'},
      { value: 'unread', text: 'Unread' },
      { value: 'sent', text: 'Sent' }
    ];

    $scope.feedFilter.options = [
      { value: '', text: 'Your Stream' },
      { value: 'own', text: 'Your Keeps' }
    ].concat(canSeeMessageFilters ? messageFilters : []).concat(orgFilterOptions);

    function getFilterFromUrl() {
      return $scope.feedFilter.options.filter(function (filter) {
        return filter.value === $stateParams.filter || ($stateParams.filter === 'team' && $stateParams.handle === filter.handle);
      })[0];
    }
    $scope.feedFilter.selected = getFilterFromUrl() || $scope.feedFilter.options[0]; // assumes 'Your Stream' is first

    $scope.scrollDistance = '0';
    $scope.hasMoreKeeps = function () {
      return feedLazyLoader.hasMore();
    };
    $scope.isLoading = function () {
      return !feedLazyLoader.hasLoaded();
    };
    $scope.fetchKeeps = function (fetchSize) {
      return feedLazyLoader
      .fetch(null, null, fetchSize)
      .then(function (keeps) {
        $scope.feed = keeps;
      })
      ['catch'](modalService.openGenericErrorModal);
    };

    $scope.addKeeps = function () {
      modalService.open({
        template: 'keeps/addKeepModal.tpl.html',
        modalData: {}
      });
    };

    $scope.keepClick = function(keep, event) {
      var eventAction = event.currentTarget.getAttribute('click-action');
      $analytics.eventTrack('user_clicked_page', { type: 'homeFeed', action: eventAction });

      if (eventAction === 'clickedArticleTitle' || eventAction === 'clickedArticleImage') {
        $analytics.eventTrack('user_viewed_content', { source: 'feed', contentType: 'keep', keep: keep.id,
          libraryId: keep.library && keep.library.id, orgId: keep.organization && keep.organization.id });
      }
    };

    $scope.updateFeedFilter = function() {
      $scope.feed = [];
      feedLazyLoader.reset();
      $scope.fetchKeeps();
      if ($scope.feedFilter.selected.value !== 'org') {
        $location.search('filter', $scope.feedFilter.selected.value || null); // sets query param or removes if falsy
        $location.search('handle', null);
      } else {
        $location.search('filter', 'team');
        $location.search('handle', $scope.feedFilter.selected.handle);
      }
    };

    $scope.$on('keepRemoved', function (e, keepData) {
      var matches = $scope.feed.filter(function (f) {
        return f.url === keepData.url;
      });
      var unkeptFeedItem = matches && matches[0];
      var removedIndex;

      if (unkeptFeedItem.libraries.length <= 1) {
        removedIndex = $scope.feed.indexOf(unkeptFeedItem);

        if (removedIndex !== -1) {
          $scope.feed.splice(removedIndex, 1);
        }
      }
    });

    [
      $rootScope.$on('keepAdded', function (e, keeps) {
        keeps.forEach(function (keep) {
          // only add the keep to the feed if it is not already present
          var duplicates = $scope.feed.filter(function isDuplicate(f) {
            return f.id === keep.id;
          });

          if (duplicates.length === 0) {
            $scope.feed.unshift(keep);
          }
        });
      })
    ].forEach(function (deregister) {
      $scope.$on('$destroy', deregister);
    });

    $scope.suggestedSearches = [
      { title: 'Startups', query: 'startups' },
      { title: 'Productivity', query: 'productivity' },
      { title: 'Developer', query: 'developer' },
      { title: 'Collaboration', query: 'collaboration' },
      { title: 'Creativity', query: 'creativity' },
      { title: 'Coffee', query: 'coffee' },
      { title: 'Recruiting', query: 'recruiting' },
      { title: 'Data Science', query: 'data%20science' },
      { title: 'Brain Development', query: '%22brain%20development%22' },
      { title: 'Apps', query: 'apps' }
    ];

    $scope.featuredLibrariesRoute = routeService.featuredLibraries();

    // Initialize
    $window.document.title = 'Kifi • Your stream';
    profileService.fetchPrefs().then(function (prefs) {
      var FIRST_FETCH_SIZE = prefs.use_minimal_keep_card ? 6 : 3;
      $scope.fetchKeeps(FIRST_FETCH_SIZE).then(function() { // populate the visible keeps fast, then fetch the rest of the page
        $scope.fetchKeeps();
      });
    });
  }
]);
