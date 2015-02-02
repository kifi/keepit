'use strict';

angular.module('kifi')

.factory('userProfileActionService', [
  '$http', '$q', 'routeService', 'Clutch', '$analytics',
  function ($http, $q, routeService, Clutch, $analytics) {
    var clutchParams = {
      cacheDuration: 10000
    };

    function getData(res) {
      return res.data;
    }

    var userProfileService = new Clutch(function (username) {
      return $http.get(routeService.getUserProfile(username)).then(getData);
    }, clutchParams);
    var userLibrariesService = new Clutch(function (username, filter, page, size) {
      return $http.get(routeService.getUserLibraries(username, filter, page, size)).then(getData);
    }, clutchParams);

    var api = {
      getProfile: function (username) {
        return userProfileService.get(username);
      },
      getLibraries: function (username, filter, page, size) {
        return userLibrariesService.get(username, filter, page, size);
      },
      trackEvent: function (eventName, profileUserId, attributes) {
        var defaultAttributes = {
          profileOwnerUserId: profileUserId
        };
        attributes = _.extend(defaultAttributes, attributes || {});
        $analytics.eventTrack(eventName, attributes);
      }
    };

    return api;
  }
]);
