'use strict';

angular.module('kifi.keepWhoService', [])

.factory('keepWhoService', [
  function () {
    var api = {
      getPicUrl: function(user) {
        if (user) {
          return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
        }
        return '';
      },

      getName: function (user) {
        return (user.firstName || '') + ' ' + (user.lastName || '');
      }
    };

    return api;
  }
]);
