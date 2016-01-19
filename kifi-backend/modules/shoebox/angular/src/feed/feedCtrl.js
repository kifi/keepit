'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$window', '$rootScope', '$scope', '$q', '$timeout', '$analytics', 'feedService', 'Paginator', 'routeService', 'modalService', 'profileService',
  function($window, $rootScope, $scope, $q, $timeout, $analytics, feedService, Paginator, routeService, modalService, profileService) {
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
      return { value: 'org', id: org.id, text: org.name };
    });
    $scope.feedFilter.options = [
      { value: '', text: 'Your Stream' },
      { value: 'own', text: 'Your Keeps' },
      { value: 'unread', text: 'Unread'},
      { value: 'sent', text: 'Sent'}
    ].concat(orgFilterOptions);

    // assumes this is the default setting's index ('All Keeps' 1/4/16, could be stored in prefs later on)
    $scope.feedFilter.selected = $scope.feedFilter.options[0];

    $scope.scrollDistance = '0';
    $scope.hasMoreKeeps = function () {
      return feedLazyLoader.hasMore();
    };
    $scope.isLoading = function () {
      return !feedLazyLoader.hasLoaded();
    };
    $scope.fetchKeeps = function () {
      return feedLazyLoader
      .fetch()
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
    $scope.fetchKeeps();
  }
]);
