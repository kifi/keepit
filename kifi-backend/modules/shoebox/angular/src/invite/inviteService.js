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
        platformFilter;

    var socialSearchService = new Clutch(function (name) {
      /*
      { "label":"Adam Cornett",
        "score":0,
        "networkType":"facebook",
        "image":"https://graph.facebook.com/44302919/picture?width=75&height=75",
        "value":"facebook/44302919",
        "status":"",
        "id":"44302919",
        "network":"facebook" }
      */
      if (!name || !name.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.socialSearch(name)).then(function (res) {
        var results = res.data;
        _.forEach(results, function (result) {
          result.id = result.value.split('/').splice(1).join('');
          var trimmedLabel = result.label.trim();
          result.label = trimmedLabel ? trimmedLabel : result.id;
          result.network = result.networkType === 'email' ? result.id : result.networkType;
          result.iconStyle = 'kf-' + result.networkType + '-icon-micro';
        });
        return results;
      });
    });

    function populateWithEmail(name) {
      var alreadyHasElem = inviteList[inviteList.length - 1] && inviteList[inviteList.length - 1].custom;
      if (name.indexOf('@') > 0 && !alreadyHasElem) {
        // They're typing in an email address
        console.log('adding elem', name);
        var resultInside = _.find(inviteList, function (elem) {
          return elem.networkType === 'email' && elem.value.split('/').splice(1).join('') === name;
        });
        if (!resultInside) {
          inviteList.push({
            label: name,
            networkType: 'email',
            value: 'email/' + name,
            status: '',
            custom: true
          });
        }
      }
    }

    var api = {

      socialSearch: function (name) {
        populateWithEmail(name);
        return socialSearchService.get(name).then(function (results) {
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
        return null; // todo!
      },

      getWhoToInvite: function () {
        // use $http if request is needed
        return $q.when(whoToInviteList); // todo!
      },

      find: function (query, platform) {
        if (platform === undefined) {
          // handle no platform, which means search everywhere
        }
        return null; // todo!
      }

    };

    return api;
  }
]);
