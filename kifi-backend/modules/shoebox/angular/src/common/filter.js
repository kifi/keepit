'use strict';

angular.module('kifi')

.filter('titlecase', function () {
  return function (input) {
    return input.slice(0, 1).toUpperCase() + input.slice(1).toLowerCase();
  };
})

.filter('name', function () {
  return function (o) {
    return o ? (o.firstName || '') + ' ' + (o.lastName || '') : '';
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
  return function (user, sub) {
    if (user) {
      return '/' + user.username + (sub && sub !== 'libraries' ? '/' + sub : '');
    }
  };
})

.filter('bgImageAndPos', [
  'env',
  function (env) {
    return function (image) {
      return image ? ['background-image:url(', env.picBase, '/', image.path, ');background-position:', image.x, '% ', image.y, '%'].join('') : '';
    };
  }
])

.filter('shadedBackground', [
  'env',
  function (env) {
    return function (o, imgGradientOpacityTop, imgGradientOpacityBottom, colorGradientOpacityTop) {
      if (o.image) {
        return [
          'background-image:',
          'linear-gradient(rgba(0,0,0,', imgGradientOpacityTop, '),rgba(0,0,0,', imgGradientOpacityBottom, ')),',
          'url(', env.picBase, '/', o.image.path, ');',
          'background-position:0,', o.image.x, '% ', o.image.y, '%'
        ].join('');
      }
      return [
        'background-color:', o.color, ';',
        'background-image:linear-gradient(rgba(0,0,0,', colorGradientOpacityTop, '),rgba(0,0,0,0))'
      ].join('');
    };
  }
])

.filter('shadedBackgroundImage', function () {
  return function (o, gradientOpacityTop, gradientOpacityBottom) {
    return o ? [
      'background-image:',
      'linear-gradient(rgba(0,0,0,', gradientOpacityTop, '),rgba(0,0,0,', gradientOpacityBottom, ')),',
      'url(', o.url, ');',
      'background-position:0,', o.x, '% ', o.y, '%'
    ].join('') : '';
  };
})

.filter('num', function () {
  return function (n) {
    if (n < 1000) {
      return n == null ? '' : String(n);
    }
    var hundreds = String(n).slice(0, -2);
    return hundreds.slice(-1) === '0' ? hundreds.slice(0, -1) + 'K' : hundreds.replace(/(\d)$/, '.$1') + 'K';
  };
})

.filter('domain', function () {
  var re = /^\w+:\/\/(?:www\.)?([^\/]+)/;
  return function (url) {
    var match = re.exec(url);
    return match && match[1];
  };
});
