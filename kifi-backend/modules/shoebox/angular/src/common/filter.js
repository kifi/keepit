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
    return function (entity, width) { // entity is a catchall for users and orgs
      if (entity) {
        if (entity.pictureName) {
          return routeService.formatUserPicUrl(entity.id, entity.pictureName, width > 100 ? 200 : 100);
        } else if (entity.avatarPath) {
          return routeService.formatOrgPicUrl(entity.avatarPath);
        } else {
          return '//www.kifi.com/assets/img/ghost.200.png';
        }
      }
    };
  }
])

.filter('tagUrl', function () {
  return function (tag) {
    return '/find?q=tag:' + encodeURIComponent(tag.indexOf(' ') >= 0 ? '"' + tag + '"' : tag);
  };
})

.filter('profileUrl', function () {
  return function (userOrOrg, sub) {
    if (userOrOrg) {
      var handle = userOrOrg.username || userOrOrg.handle;
      return '/' + handle + (sub && sub !== 'libraries' ? '/' + sub : '');
    }
  };
})

.filter('absProfileUrl', [
  'env',
  function (env) {
    return function (user) {
      return user ? env.origin + '/' + user.username : null;
    };
  }
])

.filter('libImageUrl', [
  'env',
  function (env) {
    return function (image) {
      return env.picBase + '/' + image.path;
    };
  }
])

.filter('bgImageAndPos', [
  'env',
  function (env) {
    return function (image) {
      return image ? ['background-image:url(', env.picBase, '/', image.path, ');background-position:', image.x, '% ', image.y, '%'].join('') : '';
    };
  }
])

.filter('shadedBackgroundImage', [
  'env',
  function (env) {
    return function (o, gradientOpacityTop, gradientOpacityBottom) {
      return o ? [
        'background-image:',
        'linear-gradient(rgba(0,0,0,', gradientOpacityTop, '),rgba(0,0,0,', gradientOpacityBottom, ')),',
        'url(', o.url || env.picBase + '/' + o.path, ');',
        'background-position:0,', o.x, '% ', o.y, '%'
      ].join('') : '';
    };
  }
])

.filter('num', function () {
  return function (n) {
    if (n < 1000) {
      return n == null ? '' : String(n);
    }
    var hundreds = String(n).slice(0, -2);
    return hundreds.slice(-1) === '0' ? hundreds.slice(0, -1) + 'K' : hundreds.replace(/(\d)$/, '.$1') + 'K';
  };
})

.filter('timeToRead', function () {
  var roundedMinutes = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 15, 20, 30, 60];
  return function (numWords) {
    var minutes = numWords / 250;
    minutes = _.find(roundedMinutes, function (n) { return minutes < n; });
    return minutes ? minutes + ' min' : '1h';
  };
})

.filter('preventOrphans', [
  'util',
  function (util) {
    return util.preventOrphans;
  }
])

.filter('domain', function () {
  var re = /^\w+:\/\/(?:www\.)?([^\/]+)/;
  return function (url) {
    var match = re.exec(url);
    return match && match[1];
  };
})

.filter('localTime', function () {
  return Date;
})

.filter('noteHtml', [
  'HTML', '$filter',
  function (HTML, $filter) {
    var multipleBlankLinesRe = /\n(?:\s*\n)+/g;
    var hashTagMarkdownRe = /\[#((?:\\.|[^\]])*)\]/g;
    var escapedLeftBracketHashOrAtRe = /\[\\([#@])/g;
    var backslashUnescapeRe = /\\(.)/g;
    var tagUrl = $filter('tagUrl');
    return function noteTextToHtml(text) {  // keep in sync with extension
      if (!text) {
        return '';
      }
      var parts = text.replace(multipleBlankLinesRe, '\n\n').split(hashTagMarkdownRe);
      for (var i = 1; i < parts.length; i += 2) {
        var tag = parts[i].replace(backslashUnescapeRe, '$1');
        parts[i] = '<a class="kf-keep-note-hashtag" href="' + HTML.escapeDoubleQuotedAttr(tagUrl(tag)) +
          '">#' + HTML.escapeElementContent(tag) + '</a>';
      }
      for (i = 0; i < parts.length; i += 2) {
        parts[i] = HTML.escapeElementContent(parts[i].replace(escapedLeftBracketHashOrAtRe, '[$1'));
      }
      return parts.join('');
    };
  }
]);
