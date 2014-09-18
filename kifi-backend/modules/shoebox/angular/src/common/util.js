'use strict';

angular.module('util', [])

.factory('util', [
  '$document', '$window',
  function ($document, $window) {
    return {
      startsWith: function (str, prefix) {
        return str === prefix || str.lastIndexOf(prefix, 0) === 0;
      },
      endsWith: function (str, suffix) {
        return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
      },
      trimInput: function (input) {
        return input ? input.trim().replace(/\s+/g, ' ') : '';
      },
      // TODO(yiping): conform this to the test being used by the extension.
      // This one is valid for abc@example and does not need the '.' while that for the extension does.
      validateEmail: function (input) {
        // var emailAddrRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/; // jshint ignore:line
        var emailAddrRe = /(?:\b|^)([a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*)(?:\b|$)/;
        return emailAddrRe.test(input);
      },
      replaceArrayInPlace: function (oldArray, newArray) {
        // empties oldArray, loads newArray values into it, keeping the same reference.
        oldArray = oldArray || [];
        oldArray.length = 0;
        // returning the array in case it was undefined before
        Array.prototype.push.apply(oldArray, newArray);
        return oldArray;
      },
      completeObjectInPlace: function (oldObj, newObj) {
        _.forOwn(newObj || {}, function (num, key) {
          oldObj[key] = newObj[key];
        });
      },
      replaceObjectInPlace: function (oldObj, newObj) {
        // empties oldObj, loads newObj key/values into it, keeping the same reference.
        _.forOwn(oldObj || {}, function (num, key) {
          delete oldObj[key];
        });
        _.forOwn(newObj || {}, function (num, key) {
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
      isIE: function () {
        // Feature detection should be preferred to browser detection, so this function should be avoided.
        return (
          (navigator.appName === 'Microsoft Internet Explorer') ||
          ((navigator.appName === 'Netscape') &&
           (/Trident/.exec(navigator.userAgent) != null))
        );
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
      joinTags: function (keeps, tags) {
        var idMap = _.reduce(tags, function (map, tag) {
          if (tag && tag.id) {
            map[tag.id] = tag;
          }
          return map;
        }, {});

        var that = this;
        _.forEach(keeps, function (keep) {
          var newTagList = _.map(_.union(keep.collections, keep.tags), function (tagId) {
            return idMap[tagId] || null;
          }).filter(function (tag) {
            return tag != null;
          });
          keep.tagList = that.replaceArrayInPlace(keep.tagList, newTagList);
        });
      },
      validateUrl: function (keepUrl) {
        // Extremely simple for now, can be developed in the future
        return keepUrl.indexOf('.') !== -1;
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
