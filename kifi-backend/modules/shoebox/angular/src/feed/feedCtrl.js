'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$rootScope', '$scope', '$q', '$analytics', 'feedService', 'Paginator', 'routeService', 'modalService', 'profileService',
  function($rootScope, $scope, $q, $analytics, feedService, Paginator, routeService, modalService, profileService) {
    function feedSource(pageNumber, pageSize) {
      var lastKeep = $scope.feed[$scope.feed.length - 1];

      function tryGetFullPage(limit, streamEnd, deferred, results) {
        deferred = deferred || $q.defer();
        results = results || [];

        feedService.getFeed(limit, streamEnd && streamEnd.id).then(function (keepData) {
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

    $scope.scrollDistance = '0';
    $scope.hasMoreKeeps = function () {
      return feedLazyLoader.hasMore();
    };
    $scope.isLoading = function () {
      return !feedLazyLoader.hasLoaded();
    };
    $scope.fetchKeeps = function () {
      feedLazyLoader
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

    $scope.keepClick = function(keep) {
      var eventType = profileService.userLoggedIn() ? 'user_viewed_content' : 'visitor_viewed_content';
      $analytics.eventTrack(eventType, { source: 'feed', contentType: 'keep', keepId: keep.id,
        libraryId: keep.library && keep.library.id, orgId: keep.organization && keep.organization.id });
    };

    $scope.$on('keepRemoved', function (e, keepData) {
      var url = keepData.url;

      var matches = $scope.feed.filter(function (f) {
        return f.url === url;
      });
      var removedFeedItem = matches && matches[0];
      var removedIndex = $scope.feed.indexOf(removedFeedItem);

      if (removedIndex !== -1) {
        $scope.feed.splice(removedIndex, 1);
      }
    });

    [
      $rootScope.$on('keepAdded', function (e, keeps) {
        $scope.feed.unshift(keeps[0]);
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
    $scope.fetchKeeps();
  }
]);
