'use strict';

angular.module('kifi')

.factory('keepWhoService', [
  'routeService',
  function (routeService) {
    var api = {
      getPicUrl: function (user, width) {
        width = width > 100 ? 200 : 100;
        if (user && user.id && user.pictureName) {
          return routeService.formatPicUrl(user.id, user.pictureName, width);
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
