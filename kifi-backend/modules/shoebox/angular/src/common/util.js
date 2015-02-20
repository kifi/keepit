'use strict';

angular.module('util', [])

.factory('util', [
  '$document', '$window', '$location',
  function ($document, $window, $location) {
    var HTML_ESCAPE_CHARS = /[&<>"'\/]/g;
    var HTML_ESCAPES = {
      '&': '&amp;',
      '<': '&lt;',
      '>': '&gt;',
      '"': '&quot;',
      '\'': '&#x27;',
      '/': '&#x2F;'
    };
    var youtubeVideoUrlRe = /^(?:https?:\/\/)?(?:youtu\.be|(?:www\.)?youtube(?:-nocookie)?\.com)\/(?:|user\/[^\/?#]+)?(?:|.*?[\/=])([a-zA-Z0-9_-]{11})\b/;
    var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|a[cdegilmoqrstuz]|b[abefghimnrtyz]|c[acdfghiklmnoruxyz]|d[ejko]|e[cegst]|f[ijkmor]|g[befghilmnpqrstu]|h[kmnru]|i[delmnorst]|j[eop]|k[eghrwyz]|l[bciktuvy]|m[cdghkmnoqrstuwxyz]|n[acfilouz]|om|p[aeghklmnrty]|qa|r[eouw]|s[abcdeghikmnotuvz]|t[cdfhjmnoprtvwz]|u[agkmsyz]|v[eginu]|wf|y[t|u]|z[amrw]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;  // jshint ignore:line

    var queryStringHelper = {$$path: '', $$compose: $location.$$compose};

    return {
      startsWith: function (str, prefix) {
        return str === prefix || str.lastIndexOf(prefix, 0) === 0;
      },
      startsWithCaseInsensitive: function (str, prefix) {
        return this.startsWith(str.toLowerCase(), prefix.toLowerCase());
      },
      endsWith: function (str, suffix) {
        return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
      },
      trimInput: function (input) {
        return input ? input.trim().replace(/\s+/g, ' ') : '';
      },
      validateEmail: function (input) {
        var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/; // jshint ignore:line
        return emailAddrRe.test(input);
      },
      replaceArrayInPlace: function (oldArray, newArray) {
        // empties oldArray, loads newArray values into it, keeping the same reference.
        oldArray.length = 0;
        oldArray.push.apply(oldArray, newArray);
      },
      replaceObjectInPlace: function (oldObj, newObj) {
        // empties oldObj, loads newObj key/values into it, keeping the same reference.
        _.forOwn(oldObj, function (num, key) {
          delete oldObj[key];
        });
        _.forOwn(newObj, function (num, key) {
          oldObj[key] = newObj[key];
        });
      },
      /* see http://cvmlrobotics.blogspot.com/2013/03/angularjs-get-element-offset-position.html */
      offset: function (elm) {
        try { return elm.offset(); } catch (e) {}
        var rawDom = elm[0];
        var body = $document.documentElement || $document.body;
        var scrollX = $window.pageXOffset || body.scrollLeft;
        var scrollY = $window.pageYOffset || body.scrollTop;
        var _x = rawDom.getBoundingClientRect().left + scrollX;
        var _y = rawDom.getBoundingClientRect().top + scrollY;
        return { left: _x, top: _y };
      },
      $debounce: function (scope, f, ms, opts) {
        return _.debounce(function () {
          var phase = scope.$root.$$phase;
          if (phase === '$apply' || phase === '$digest') {
            f();
          } else {
            scope.$apply(f);
          }
        }, ms, opts);
      },
      getYoutubeIdFromUrl: function (url) {
        var match = url.match(youtubeVideoUrlRe);
        return match && match[1];
      },
      formatTitleFromUrl: function (url) {
        var aUrlParser = document.createElement('a');
        var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
        var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
        var fileNameToSpaceRe = /[\/._-]/g;

        aUrlParser.href = url;

        var domain = aUrlParser.hostname;
        var domainIdx = url.indexOf(domain);
        var domainMatch = domain.match(secLevDomainRe);
        if (domainMatch) {
          domainIdx += domainMatch.index;
          domain = domainMatch[0];
        }

        var fileName = aUrlParser.pathname;
        var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
        var fileNameMatch = fileName.match(fileNameRe);
        if (fileNameMatch) {
          fileNameIdx += fileNameMatch.index;
          fileName = fileNameMatch[0];
        }
        fileName = fileName.replace(fileNameToSpaceRe, ' ').trim();

        return domain + (fileName ? ' · ' + fileName : '');
      },
      validateUrl: function (keepUrl) {
        // Extremely simple for now, can be developed in the future
        return keepUrl.indexOf('.') !== -1;
      },
      toCamelCase: function (strings) { // given a list of strings, return a single camel cased string
        var str = '';
        for (var i=0; i < strings.length; i++) {
          if (i === 0) {
            str += strings[i].toLowerCase();
          } else {
            str += strings[i].charAt(0).toUpperCase() + strings[i].substr(1).toLowerCase();
          }
        }
        return str;
      },
      htmlEscape: function (text) {
        function htmlEscapeReplace(ch) {
          return HTML_ESCAPES[ch];
        }

        return text == null ? '' : String(text).replace(HTML_ESCAPE_CHARS, htmlEscapeReplace);
      },
      /**
       * Behaves like $location.search({...}). Keys whose values are an empty array will be omitted entirely.
       * https://docs.angularjs.org/api/ng/service/$location#search
       */
      formatQueryString: function (params) {
        queryStringHelper.$$search = params;
        queryStringHelper.$$compose();
        return queryStringHelper.$$url;
      },
      generateSlug: function (name) {
        return name.toLowerCase().replace(/[^\w\s-]|_/g, '').replace(/\s+/g, '-').replace(/^-/, '').substr(0, 50).replace(/-$/, '');
      },
      processUrls: function (text) {
        var parts = (text || '').split(uriRe);

        for (var i = 1; i < parts.length; i += 3) {
          var uri = parts[i];
          var scheme = parts[i+1];
          var url = (scheme ? '' : 'http://') + this.htmlEscape(uri);

          parts[i] = '<a target="_blank" href="' + url + '">' + url;
          parts[i+1] = '</a>';
          parts[i-1] = this.htmlEscape(parts[i-1]);
        }
        parts[parts.length-1] = this.htmlEscape(parts[parts.length-1]);

        return parts.join('');
      },
      chooseTreatment: function (salt, treatments) {
        // To generate the salt for an experiment:
        // [0,0,0,0,0,0].map(function () { return Math.floor(Math.random() * 32).toString(32) }).join('')
        try {
          var id = $window.mixpanel.cookie.props.distinct_id;
          var sourceDigits = (id.id || id).replace(/[^\da-f]/gi, '');
          var chosenDigits = salt.split('').map(function (ch) {
            return sourceDigits[parseInt(ch, 32)];
          }).join('');
          var treatmentIndex = parseInt(chosenDigits, 16);
          return treatments[treatmentIndex % treatments.length];
        } catch (e) {
          return null;
        }
      }
    };
  }
])

.directive('postRepeatDirective', [
  '$timeout', '$window',
  function ($timeout, $window) {
    return function (scope) {
      if (scope.$first) {
        if ($window.console && $window.console.time) {
          $window.console.time('postRepeatDirective');
        }
      }

      if (scope.$last) {
        $timeout(function () {
          if ($window.console && $window.console.time) {
            $window.console.time('postRepeatDirective');
            $window.console.timeEnd('postRepeatDirective');
          }
        });
      }
    };
  }
])

.constant('keyIndices', {
  KEY_UP: 38,
  KEY_DOWN: 40,
  KEY_ENTER: 13,
  KEY_ESC: 27,
  KEY_TAB: 9,
  KEY_DEL: 46,
  KEY_F2: 113,
  KEY_SPACE: 32
});
