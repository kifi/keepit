'use strict';

angular.module('kifi')

.factory('keepWhoService', [
  function () {
    var api = {
      getPicUrl: function (user, width) {
        width = width > 100 ? 200 : 100;
        if (user && user.id && user.pictureName) {
          return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/' + width + '/' + user.pictureName;
        }
        return '//www.kifi.com/assets/img/ghost.200.png';
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
