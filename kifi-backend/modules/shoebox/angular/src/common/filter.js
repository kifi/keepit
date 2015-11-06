'use strict';

angular.module('kifi')

.filter('titlecase', function () {
  return function (input) {
    return input.slice(0, 1).toUpperCase() + input.slice(1).toLowerCase();
  };
})

.filter('name', function () {
  return function (o) {
    return o ? o.name || ((o.firstName || '') + ' ' + (o.lastName || '')) : '';
  };
})

.filter('slug', function () {
  return function (o) {
    return o.handle || o.username;
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
    if (_.startsWith(tag, '#')) {
      tag = tag.slice(1);
    }
    return '/find?q=tag:' + encodeURIComponent(tag.indexOf(' ') >= 0 ? '"' + tag + '"' : tag);
  };
})

.filter('searchTerm', function () {
  return function (term) {
    var q;
    if (_.startsWith(term, '#')) {
      term = term.slice(1);
      q = 'tag:' + encodeURIComponent(term.indexOf(' ') >= 0 ? '"' + term + '"' : term);
    } else {
      q = encodeURIComponent(term);
    }
    return '/find?q=' + q;
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

.filter('money', [
  '$filter',
  function ($filter) {
    var currencyFilter = $filter('currency');

    return function (moneyData) {
      var type = typeof moneyData;
      var unit;
      var amount;

      if (type === 'object') {
        unit = Object.keys(moneyData)[0];
        amount = moneyData[unit];
      } else if (type === 'number') {
        unit = 'cents';
        amount = moneyData;
      } else {
        // treat it like a string
        unit = null;
        amount = moneyData + '';
      }

      switch (unit) {
        case 'cents':
          return currencyFilter(amount / 100);
        default:
          return amount;
      }
    };
  }
])

.filter('moneyDelta', [
  '$filter',
  function ($filter) {
    var moneyFilter = $filter('money');
    var isPositiveMoneyFilter = $filter('isPositiveMoney');
    var isNegativeMoneyFilter = $filter('isNegativeMoney');

    return function (money) {
      var moneyString = moneyFilter(money);

      if (isPositiveMoneyFilter(money)) {
        return '+' + moneyString;
      } else if (isNegativeMoneyFilter(money)) {
        return (moneyString[0] === '-' ? '' : '-') + moneyString;
      } else {
        return moneyString;
      }
    };
  }
])

.filter('moneyUnwrap', function () {
  return function (amount, unit) {
    if (typeof amount === 'object') {
      if (typeof unit === 'undefined') {
        unit = Object.keys(amount)[0];
      }
      return amount[unit];
    } else {
      return amount;
    }
  };
})

.filter('isPositiveMoney', [
  '$filter',
  function ($filter) {
    var moneyFilter = $filter('money');
    var isZeroMoneyFilter = $filter('isZeroMoney');

    return function (arg) {
      var money = moneyFilter(arg);
      return money && money[0] !== '-' && !isZeroMoneyFilter(arg);
    };
  }
])

.filter('isNegativeMoney', [
  '$filter',
  function ($filter) {
    var moneyFilter = $filter('money');

    return function (arg) {
      var money = moneyFilter(arg);
      return money && money[0] === '-';
    };
  }
])

.filter('isZeroMoney', [
  '$filter',
  function ($filter) {
    var moneyFilter = $filter('money');
    var notNonZeroDigitsRegex = /[^1-9]/g;

    // If all of the digits are zero, then the amount must be zero.
    // Likewise, if we remove all characters that aren't 1-9, it must be zero.
    return function (arg) {
      var money = moneyFilter(arg);
      return money.replace(notNonZeroDigitsRegex, '') === '';
    };
  }
])

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
