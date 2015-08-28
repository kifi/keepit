'use strict';

angular.module('kifi')

.controller('FeedCtrl', [
  '$rootScope', '$scope', 'feedService', 'Paginator', 'routeService', 'modalService',
  function($rootScope, $scope, feedService, Paginator, routeService, modalService) {
    function feedSource(pageNumber, pageSize) {
      var lastKeep = $scope.feed[(pageNumber * pageSize) - 1] || $scope.feed[$scope.feed.length - 1];

      return feedService.getFeed(pageSize, lastKeep && lastKeep.id)
      .then(function(keepData) {
        return keepData.keeps;
      });
    }

    function libraryKeptTo (keep) {
      if (keep.libraries.length) {
        var libraryAndUser = keep.libraries.filter(function(libraryAndUser) {
          return libraryAndUser[0].id === keep.libraryId;
        })[0] || keep.libraries[0];
        return libraryAndUser[0];
      }
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
        var updatedKeeps = keeps.map(function(keep) {
          keep.library = libraryKeptTo(keep);
          return keep;
        });
        $scope.feed = updatedKeeps;
      })
      ['catch'](modalService.openGenericErrorModal);
    };

    $scope.addKeeps = function () {
      modalService.open({
        template: 'keeps/addKeepModal.tpl.html',
        modalData: {}
      });
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
        $scope.feed.push(keeps[0]);
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
