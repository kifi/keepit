'use strict';

angular.module('kifi.keepWhoService', [])

.factory('keepWhoService', [
  function () {
    var api = {
      getPicUrl: function (user) {
        if (user && user.id && user.pictureName) {
          return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
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
