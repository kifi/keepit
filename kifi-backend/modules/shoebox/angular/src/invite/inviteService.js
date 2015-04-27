'use strict';

angular.module('kifi')

.factory('inviteService', [
  '$http', '$q', 'routeService', 'userService', 'util', 'Clutch', '$window', '$log', '$analytics', '$location', '$FB',
  function ($http, $q, routeService, userService, util, Clutch, $window, $log, $analytics, $location, $FB) {
    /* Naming convention:
     *  - Kifi Friend is an existing connection on Kifi
     *  - Kifi User is a user of Kifi, may not be a friend.
     */

    var inviteList = [], // used for typeahead dropdown for invite search
        selected,
        lastSearch;

    var friendlyNetworks = {'facebook': 'Facebook', 'twitter': 'Twitter'};
    var socialSearchService = new Clutch(function (name) {
      if (!name || !name.trim()) {
        return $q.when([]);
      }
      return $http.get(routeService.socialSearch(name)).then(function (res) {
        var results = _.filter(res.data, function(res){
          return res.networkType !== 'twitter';
        });
        _.forEach(results, augmentSocialResult);
        $analytics.eventTrack('user_clicked_page', {
          'action': 'searchContacts',
          'path': $location.path()
        });
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

        userService.getBasicUserInfo(result.socialId, false).then(function (res) {
          result.username = res.data.username;
        });
      }
      if (result.status === 'invited') {
        var sendText = $window.moment(new Date(result.inviteLastSentAt)).fromNow();
        result.inviteText = sendText;
      }
      return result;
    }

    function populateWithCustomEmail(name, results) {
      if (/^[^@\s]+@[^@\s]+$/.test(name)) {
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

      expireSocialSearch: function () {
        socialSearchService.expireAll();
      },

      inviteList: inviteList,

      socialSelected: selected,

      invite: function (platform, identifier) {

        socialSearchService.expireAll();

        var deferred = $q.defer();

        function doInvite() {
          $http.post(routeService.invite, {
            id: platform + '/' + identifier
          }).then(function (res) {
            $analytics.eventTrack('user_clicked_page', {
              'action': 'inviteFriend',
              'platform': platform,
              'path': $location.path()
            });
            if (res.data.url && platform === 'facebook') {
              $FB.ui({
                method: 'send',
                link: $window.unescape(res.data.url),
                to: identifier
              });
              deferred.resolve('');
            } else if (res.data.error) {
              $log.log(res.data.error);
                deferred.reject('generic_error');
            } else {
              deferred.resolve('');
            }
          }, function (err) {
            $log.log(err);
            throw err;
          });
        }

        //login if needed
        if (platform === 'facebook' && $FB.FB) {
          if ($FB.FB.getAuthResponse()) {
            doInvite();
          } else {
            $FB.FB.login(function (response) {
              if (response.authResponse) {
                doInvite();
              }
              //else user cancelled login. Do nothing further.
            });
          }
        } else {
          if (platform === 'fortytwoNF') {
            platform = 'fortytwo';
          }
          doInvite();
        }

        return deferred.promise;
      },

      friendRequest: function (id) {
        $analytics.eventTrack('user_clicked_page', {
          'action': 'addFriend',
          'path': $location.path()
        });

        return $http.post(routeService.friendRequest(id)).then(function (res) {
          return res.data;
        });
      }

    };

    return api;
  }
]);
