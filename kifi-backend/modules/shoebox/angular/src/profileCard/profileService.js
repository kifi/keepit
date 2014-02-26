'use strict';

angular.module('kifi.profileService', [])

.factory('profileService', [
  '$http', 'env',
  function ($http, env) {
    var me = {};

    function formatPicUrl(userId, pictureName, size) {
      return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
    }

    return {
      me: me,

      fetchMe: function () {
        var url = env.xhrBase + '/user/me';
        return $http.get(url).then(function (res) {
          angular.forEach(res.data, function (val, key) {
            me[key] = val;
          });
          me.picUrl = formatPicUrl(me.id, me.pictureName);
          return me;
        });
      }

    };
  }
]);
