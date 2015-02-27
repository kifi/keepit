'use strict';

angular.module('kifi')

.filter('titlecase', function () {
  return function (input) {
    return input.slice(0, 1).toUpperCase() + input.slice(1).toLowerCase();
  };
})

.filter('pic', [
  'routeService',
  function (routeService) {
    return function (user, width) {
      return user ?
        routeService.formatPicUrl(user.id, user.pictureName, width > 100 ? 200 : 100) :
        '//www.kifi.com/assets/img/ghost.200.png';
    };
  }
])

.filter('profileUrl', function () {
  return function (user) {
    return user ? '/' + user.username : '';
  };
});
