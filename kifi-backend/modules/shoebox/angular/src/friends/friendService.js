'use strict';

angular.module('kifi')

.factory('friendService', [
  '$analytics', '$http', '$location', '$q', '$timeout', 'env', 'Clutch', 'routeService', 'util',
  function ($analytics, $http, $location, $q, $timeout, env, Clutch, routeService, util) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var friends = [];
    var requests = [];
    var friendsHasRequested = false;
    var hasMoreFriends = true;
    var friendsPageSize = 20;
    var currentPage = 0;
    var totalFriends = 0;

    var clutchParams = {
      cacheDuration: 20000
    };

    var kifiFriendsService = new Clutch(function (page) {
      return $http.get(routeService.friends(page, friendsPageSize)).then(function (res) {
        if (page === 0) {
          friends.length = 0;
        }
        hasMoreFriends = res.data.friends.length >= friendsPageSize;
        friends.push.apply(friends, _.filter(res.data.friends, function (friend) {
          return !friend.unfriended;
        }));
        friendsHasRequested = true;
        totalFriends = res.data.total;
        return friends;
      });
    }, clutchParams);

    var kifiFriendRequestsService = new Clutch(function () {
      return $http.get(routeService.incomingFriendRequests).then(function (res) {
        util.replaceArrayInPlace(requests, res.data);

        return requests;
      });
    }, clutchParams);

    var kifiPeopleYouMayKnowService = new Clutch(function (offset, limit) {
      return $http.get(routeService.peopleYouMayKnow(offset, limit)).then(function (res) {
        return (res && res.data && res.data.users) ? res.data.users : [];
      });
    }, clutchParams);

    var api = {

      getMore: function () {
        return api.getKifiFriends(++currentPage);
      },

      getKifiFriends: function (page) {
        return kifiFriendsService.get(page || 0);
      },

      getRequests: function () {
        return kifiFriendRequestsService.get();
      },

      friends: friends,
      totalFriends: function () {
        return totalFriends;
      },

      hasMore: function () {
        return hasMoreFriends;
      },

      friendsHasRequested: friendsHasRequested,

      requests: requests,

      unSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/exclude', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'hideFriendInSearch',
            'path': $location.path()
          });
        });
      },

      reSearchFriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/include', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unHideFriendInSearch',
            'path': $location.path()
          });
        });
      },

      acceptRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/friend', {}).then(function () {
          kifiFriendsService.expireAll();
          kifiFriendRequestsService.expireAll();
          api.getRequests();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'acceptRequest',
            'path': $location.path()
          });
        });
      },

      ignoreRequest: function (extId) {
        return $http.post(env.xhrBase + '/user/' + extId + '/ignoreRequest', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getRequests();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'ignoreRequest',
            'path': $location.path()
          });
        });
      },

      unfriend: function (userExtId) {
        return $http.post(env.xhrBase + '/user/' + userExtId + '/unfriend', {}).then(function () {
          kifiFriendsService.expireAll();
          api.getKifiFriends();
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unFriend',
            'path': $location.path()
          });
        });
      },

      getPictureUrlForUser: function (user) {
        return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/200/' + user.pictureName;
      },

      getPeopleYouMayKnow: function (offset, limit) {
        var pymkDeferred = $q.defer();

        // If the people-you-may-know endpoint does not return a good result,
        // retry up to 2 times.
        var pymkWaitTimes = [0, 5 * 1000, 10 * 1000];
        function getPymkWithRetries(numRetries) {
          numRetries = numRetries || 0;
          kifiPeopleYouMayKnowService.get(offset || 0, limit || 10).then(function (people) {
            if (people.length > 0) {
              pymkDeferred.resolve(people);
            } else {
              if (numRetries === 2) {
                pymkDeferred.resolve([]);
              } else {
                numRetries++;
                $timeout(function () {
                  getPymkWithRetries(numRetries);
                }, pymkWaitTimes[numRetries]);
              }
            }
          });
        }

        getPymkWithRetries();

        return pymkDeferred.promise;
      },

      hidePeopleYouMayKnow: function (id) {
        return $http.post(routeService.hideUserRecommendation(id));
      }
    };

    return api;
  }
]);
