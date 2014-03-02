'use strict';

angular.module('kifi.profileService', [])

.factory('profileService', [
  '$http', 'env', '$q',
  function ($http, env, $q) {
    var me = {
      seqNum: 0
    };

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
      return me.seqNum > 0 ? $q.when(me) : fetchMe();
    }

    return {
      me: me, // when mutated, you MUST increment me.seqNum
      fetchMe: fetchMe,
      getMe: getMe
    };
  }
]);
