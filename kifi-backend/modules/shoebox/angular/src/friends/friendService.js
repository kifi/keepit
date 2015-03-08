'use strict';

angular.module('kifi')

.factory('friendService', [
  '$analytics', '$http', '$location', '$q', '$timeout', 'env', 'Clutch', 'routeService',
  function ($analytics, $http, $location, $q, $timeout, env, Clutch, routeService) {

    var clutchParams = {
      cacheDuration: 20000
    };

    var kifiPeopleYouMayKnowService = new Clutch(function (offset, limit) {
      return $http.get(routeService.peopleYouMayKnow(offset, limit)).then(function (res) {
        return (res && res.data && res.data.users) ? res.data.users : [];
      });
    }, clutchParams);

    var api = {
      unSearchFriend: function (userId) {
        return $http.post(env.xhrBase + '/user/' + userId + '/exclude', {}).then(function () {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'hideFriendInSearch',
            'path': $location.path()
          });
        });
      },

      reSearchFriend: function (userId) {
        return $http.post(env.xhrBase + '/user/' + userId + '/include', {}).then(function () {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unHideFriendInSearch',
            'path': $location.path()
          });
        });
      },

      acceptRequest: function (userId) {
        return $http.post(env.xhrBase + '/user/' + userId + '/friend', {}).then(function () {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'acceptRequest',
            'path': $location.path()
          });
        });
      },

      ignoreRequest: function (userId) {
        return $http.post(env.xhrBase + '/user/' + userId + '/ignoreRequest', {}).then(function () {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'ignoreRequest',
            'path': $location.path()
          });
        });
      },

      unfriend: function (userId) {
        return $http.post(env.xhrBase + '/user/' + userId + '/unfriend', {}).then(function () {
          $analytics.eventTrack('user_clicked_page', {
            'action': 'unFriend',
            'path': $location.path()
          });
        });
      },

      getPeopleYouMayKnow: function (offset, limit) {
        var deferred = $q.defer();

        // If the people-you-may-know endpoint does not return a good result,
        // retry up to 2 times.
        var waitTimes = [5 * 1000, 10 * 1000];
        function getPymkWithRetries() {
          kifiPeopleYouMayKnowService.get(offset || 0, limit || 10).then(function (people) {
            if (people.length > 0) {
              deferred.resolve(people);
            } else {
              if (waitTimes.length === 0) {
                deferred.resolve([]);
              } else {
                $timeout(function () {
                  getPymkWithRetries();
                }, waitTimes.shift());
              }
            }
          });
        }

        getPymkWithRetries();

        return deferred.promise;
      },

      hidePeopleYouMayKnow: function (id) {
        return $http.post(routeService.hideUserRecommendation(id));
      }
    };

    return api;
  }
]);
