'use strict';

angular.module('kifi.keepWhoService', [])

.factory('keepWhoService', [
  function () {
    var api = {
      getPicUrl: function (user, width) {
        width = width > 100 ? 200 : 100;
        if (user && user.id && user.pictureName) {
          return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/' + width + '/' + user.pictureName;
        }
        return '';
      },

      getName: function (user) {
        if (!user) {
          return '';
        }
        if (user.firstName && user.lastName) {
          return user.firstName + ' ' + user.lastName;
        }
        return user.firstName || user.lastName || '';
      }
    };

    return api;
  }
]);
