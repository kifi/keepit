'use strict';

angular.module('kifi.inviteService', ['util', 'kifi.clutch'])

.factory('inviteService', [
  '$http', 'env', '$q', 'routeService', 'util', 'Clutch',
  function ($http, env, $q, routeService, util, Clutch) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */
    var whoToInviteList = [],
        inviteList = [], // used for typeahead dropdown for invite search
        selected,
        lastSearch;

    var friendlyNetworks = {'facebook': 'Facebook', 'linkedin': 'LinkedIn'};
    var socialSearchService = new Clutch(function (name) {
      if (!name || !name.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.socialSearch(name)).then(function (res) {
        var results = res.data;
        _.forEach(results, augmentSocialResult);
        return results;
      });
    });

    var customEmail = {
      custom: 'email',
      iconStyle: 'kf-email-icon-micro',
      networkType: 'email',
      status: ''
    };

    function augmentSocialResult(result) {
      result.socialId = result.value.split('/').splice(1).join('');
      var trimmedLabel = result.label.trim();
      result.label = trimmedLabel ? trimmedLabel : result.socialId;
      result.network = result.networkType === 'email' ? result.socialId : friendlyNetworks[result.networkType] || result.networkType;
      result.iconStyle = 'kf-' + result.networkType + '-icon-micro';
      if (result.networkType === 'fortytwo' || result.networkType === 'fortytwoNF') {
        result.image = routeService.formatPicUrl(result.socialId, result.image);
      }
      return result;
    }

    function populateWithCustomEmail(name, results) {
      if (name.indexOf('@') > 0) {
        var last = results[results.length - 1];
        if (last && last.custom) {
          if (last.label === name) {
            return;
          } else {
            results.pop();
          }
        }
        // They're typing in an email address
        var resultInside = _.find(results, function (elem) {
          return elem.networkType === 'email' && elem.value.split('/').splice(1).join('') === name;
        });
        if (!resultInside) {
          customEmail.socialId = name;
          customEmail.label = name;
          customEmail.network = name;
          customEmail.value = 'email/' + name;
          results.push(augmentSocialResult(customEmail));
        }
      }

    }

    var api = {

      socialSearch: function (name) {
        lastSearch = name;
        populateWithCustomEmail(name, inviteList);

        return socialSearchService.get(name).then(function (results) {

          populateWithCustomEmail(lastSearch, results);
          util.replaceArrayInPlace(inviteList, results);

          // find which was selected, if not:
          if (results.length === 0) {
            selected = null;
          } else {
            selected = results[0].value;
          }
          return results;
        });
      },

      inviteList: inviteList,

      socialSelected: selected,

      invite: function (platform, identifier) {

        socialSearchService.expireAll();

        return platform + identifier; // todo!
      },

      getWhoToInvite: function () {
        // use $http if request is needed
        return $q.when(whoToInviteList); // todo!
      }

    };

    return api;
  }
]);
