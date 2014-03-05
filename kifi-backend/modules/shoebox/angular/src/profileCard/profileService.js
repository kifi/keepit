'use strict';

angular.module('kifi.profileService', [])

.factory('profileService', [
  '$http', 'env', '$q', 'util',
  function ($http, env, $q, util) {
    var me = {
      seqNum: 0
    };
    var addressBooks = [];

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    function fetchMe() {
      var url = env.xhrBase + '/user/me';
      return $http.get(url).then(function (res) {
        angular.forEach(res.data, function (val, key) {
          me[key] = val;
        });
        me.picUrl = formatPicUrl(me.id, me.pictureName);
        me.seqNum++;
        return me;
      });
    }

    function getMe() {
      // todo: add mechanism to combine concurrent outgoing requests. useful here.
      return me.seqNum > 0 ? $q.when(me) : fetchMe();
    }

    function fetchAddressBooks() {
      return $http.get(env.xhrBase + '/user/abooks').then(function (res) {
        util.replaceArrayInPlace(addressBooks, res.data);
        return addressBooks;
      });
    }

    function getAddressBooks() {
      return addressBooks.length > 0 ? $q.when(addressBooks) : fetchAddressBooks();
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe,
      getAddressBooks: getAddressBooks
    };
  }
]);
