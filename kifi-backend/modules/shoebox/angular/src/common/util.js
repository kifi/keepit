'use strict';

angular.module('util', [])

.constant('util', (function () {
    var youtubeVideoUrlRe = /^(?:https?:\/\/)?(?:youtu\.be|(?:www\.)?youtube(?:-nocookie)?\.com)\/(?:|.*?[\/=])([a-zA-Z0-9_-]{11})\b/;
    var emailAddrValidateRe = /^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+$/; // jshint ignore:line
    var notOneSpaceWhitespaceRe = /(?: \s+|[\t\r\n\f]\s*)/g;
    var hyphensToMakeNonBreakingRe = /( \S{1,10})-(?=\S{1,10} )/g;
    var punctuationRe = /[!-\/:-@[-`{-~]/;

    var RESERVED_SLUGS = ['libraries', 'connections', 'followers', 'keeps', 'tags', 'members', 'settings'];

    var util = {
      startsWith: function (str, prefix) {
        return str === prefix || str.lastIndexOf(prefix, 0) === 0;
      },
      startsWithCaseInsensitive: function (str, prefix) {
        return util.startsWith(str.toLowerCase(), prefix.toLowerCase());
      },
      endsWith: function (str, suffix) {
        return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
      },
      trimInput: function (input) {
        return input ? input.trim().replace(/\s+/g, ' ') : '';
      },
      validateEmail: function (input) {
        return emailAddrValidateRe.test(input);
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
        return /^(https?):\/\/(((([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(%[\da-f]{2})|[!\$&'\(\)\*\+,;=]|:)*@)?(((\d|[1-9]\d|1\d\d|2[0-4]\d|25[0-5])\.(\d|[1-9]\d|1\d\d|2[0-4]\d|25[0-5])\.(\d|[1-9]\d|1\d\d|2[0-4]\d|25[0-5])\.(\d|[1-9]\d|1\d\d|2[0-4]\d|25[0-5]))|((([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|\d|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))\.)+(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])*([a-z]|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])))\.?)(:\d*)?)(\/((([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(%[\da-f]{2})|[!\$&'\(\)\*\+,;=]|:|@)+(\/(([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(%[\da-f]{2})|[!\$&'\(\)\*\+,;=]|:|@)*)*)?)?(\?((([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(%[\da-f]{2})|[!\$&'\(\)\*\+,;=]|:|@)|[\uE000-\uF8FF]|\/|\?)*)?(\#((([a-z]|\d|-|\.|_|~|[\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF])|(%[\da-f]{2})|[!\$&'\(\)\*\+,;=]|:|@)|\/|\?)*)?$/i.test(keepUrl);   // jshint ignore:line
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
      preventOrphans: function (text, minChars, maxFraction) {
        text = text.trim().replace(notOneSpaceWhitespaceRe, ' ').replace(hyphensToMakeNonBreakingRe, '$1\u2011');
        var n = text.length;
        var i = text.indexOf(' ', n - Math.floor(n * maxFraction) - 1);
        if (i >= 0) {
          i = text.indexOf(' ', Math.max(i + 2, n - minChars));
          if (i > 0 && text.charAt(i - 2) === ' ' && punctuationRe.test(text.charAt(i - 1))) {
            i = text.indexOf(' ', i + 2);
          }
          while (i > 0) {
            text = text.substr(0, i) + '\u00a0' + text.substr(i + 1);
            i = text.indexOf(' ', i + 2);
          }
        }
        return text;
      },
      generateSlug: function (name) {
        var slug = name.toLowerCase()
          .replace(/[^0-9a-z\u00A0-\uD7FF\uF900-\uFDCF\uFDF0-\uFFEF\s-]|_/g, '')
          .replace(/\s+/g, '-')
          .replace(/--+/g, '-')
          .replace(/^-/, '')
          .substr(0, 50)
          .replace(/-$/, '');
        return RESERVED_SLUGS.indexOf(slug) >= 0 ? slug + '-' : slug;
      },

      retrofitKeepFormat: function(newKeep, page) {
        var oldKeep = _.clone(newKeep);

        var oldUsers = newKeep.recipients.users.map(function(user) {
          return { user: user };
        });
        var oldLibraries = newKeep.recipients.libraries.map(function(library) {
          return { library: library };
        });
        var members = { users: oldUsers, libraries: oldLibraries, emails: newKeep.recipients.emails };

        _.extend(oldKeep, {
          createdAt: newKeep.keptAt,
          discussion: {
            locator: '/messages/' + newKeep.id,
            messages: [],
            numMessages: 0
          },
          hashtags: [],
          keepers: page.context && page.context.keepers,
          keepersOmitted: page.context && (page.context.numTotalKeepers - page.context.numVisibleKeepers),
          keepersTotal: page.context && page.context.numTotalKeepers,
          keeps: [],
          libraries: page.context && page.context.libraries,
          librariesOmitted: page.context && (page.context.numTotalLibraries - page.context.numVisibleLibraries),
          members: members,
          participants: newKeep.recipients.users,
          permissions: newKeep.viewer.permissions,
          pubId: newKeep.id,
          siteName: page.content && page.content.summary.siteName,
          sourceAttribution:
            newKeep.source.kifi ? { kifi: newKeep.source.kifi.keptBy } :
            newKeep.source.twitter ? { twitter: newKeep.source.twitter } :
            newKeep.source.slack ? { slack: newKeep.source.slack } : null,
          sources: page.context && page.context.sources,
          summary: {
            description: page.content && page.content.description,
            hasContent: true,
            imageHeight: newKeep.image && newKeep.image.dimensions.height,
            imageUrl: newKeep.image && newKeep.image.url,
            imageWidth: newKeep.image && newKeep.image.dimensions.width,
            title: newKeep.title,
            wordCount: page.content && page.content.wordCount
          }
        });

    	  return oldKeep;
      }
    };

    return util;
}()))

// Uncomment and add kf-time-ng-repeat to an ng-repeat element to see timing info.
//
// .directive('kfTimeNgRepeat', [
//   '$timeout', '$window',
//   function ($timeout, $window) {
//     return function (scope) {
//       if (scope.$first) {
//         if ($window.console && $window.console.time) {
//           $window.console.time('kfTimeNgRepeat');
//         }
//       }
//
//       if (scope.$last) {
//         $timeout(function () {
//           if ($window.console && $window.console.time) {
//             $window.console.time('kfTimeNgRepeat');
//             $window.console.timeEnd('kfTimeNgRepeat');
//           }
//         });
//       }
//     };
//   }
// ])

.constant('HTML', (function () {
  var allAmpAndLt = /[&<]/g;
  var entities = {
    '&': '&amp;',
    '<': '&lt;'
  };
  function entityFor(ch) {
    return entities[ch];
  }
  function escapeElementContent(text) {
    // www.owasp.org/index.php/XSS_Experimental_Minimal_Encoding_Rules
    return text == null ? '' : String(text).replace(allAmpAndLt, entityFor);
  }

  var allQuotes = /"/g;
  function escapeDoubleQuotedAttr(text) {
    // Rule #2 at www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet states
    // "Properly quoted attributes can only be escaped with the corresponding quote."
    // Caller must ensure text is safe in its specific attribute context (e.g. href, onload).
    return text == null ? '' : String(text).replace(allQuotes, '&quot;');
  }

  var allLineBreakTags = /<(?:div[^>]*>(?:<br[^>]*><\/div>)?|br[^>]*>|\/div><div[^>]*>)/gi;
  var allDivEndTags = /<\/div>/gi;
  function replaceLineBreakTagsWithChars(html) {
    return html.replace(allLineBreakTags, '\n').replace(allDivEndTags, '');
  }

  return {
    escapeElementContent: escapeElementContent,
    escapeDoubleQuotedAttr: escapeDoubleQuotedAttr,
    replaceLineBreakTagsWithChars: replaceLineBreakTagsWithChars
  };
}()))


.factory('URI', [
  '$location',
  (function () {
    return _.once(function ($location) {
      var helper = {$$path: '', $$compose: $location.$$compose};
      return {
        // Behaves like $location.search({...}). Keys whose values are an empty array will be omitted entirely.
        // https://docs.angularjs.org/api/ng/service/$location#search
        formatQueryString: function (params) {
          helper.$$search = params;
          helper.$$compose();
          return helper.$$url;
        }
      };
    });
  }())
])


// Takes plain text and returns it formatted as HTML with URLs and email addresses auto-linkified.
.factory('linkify', [
  'HTML',
  (function () {
    return _.once(function (HTML) {
      var emailAddrDetectRe = /(?:\b|^)([a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)(?:\b|$)/;  // jshint ignore:line
      var uriDetectRe = /(?:\b|^)((?:(https?|ftp):\/\/[^\s!"#%&'()*,\/:;?@[\\\]_{}\u00A1\u00A7\u00AB\u00B6\u00B7\u00BB\u00BF\u037E\u0387\u055A-\u055F\u0589\u058A\u05BE\u05C0\u05C3\u05C6\u05F3\u05F4\u0609\u060A\u060C\u060D\u061B\u061E\u061F\u066A-\u066D\u06D4\u0700-\u070D\u07F7-\u07F9\u0830-\u083E\u085E\u0964\u0965\u0970\u0AF0\u0DF4\u0E4F\u0E5A\u0E5B\u0F04-\u0F12\u0F14\u0F3A-\u0F3D\u0F85\u0FD0-\u0FD4\u0FD9\u0FDA\u104A-\u104F\u10FB\u1360-\u1368\u1400\u166D\u166E\u169B\u169C\u16EB-\u16ED\u1735\u1736\u17D4-\u17D6\u17D8-\u17DA\u1800-\u180A\u1944\u1945\u1A1E\u1A1F\u1AA0-\u1AA6\u1AA8-\u1AAD\u1B5A-\u1B60\u1BFC-\u1BFF\u1C3B-\u1C3F\u1C7E\u1C7F\u1CC0-\u1CC7\u1CD3\u2010-\u2027\u2030-\u2043\u2045-\u2051\u2053-\u205E\u207D\u207E\u208D\u208E\u2329\u232A\u2768-\u2775\u27C5\u27C6\u27E6-\u27EF\u2983-\u2998\u29D8-\u29DB\u29FC\u29FD\u2CF9-\u2CFC\u2CFE\u2CFF\u2D70\u2E00-\u2E2E\u2E30-\u2E3B\u3001-\u3003\u3008-\u3011\u3014-\u301F\u3030\u303D\u30A0\u30FB\uA4FE\uA4FF\uA60D-\uA60F\uA673\uA67E\uA6F2-\uA6F7\uA874-\uA877\uA8CE\uA8CF\uA8F8-\uA8FA\uA92E\uA92F\uA95F\uA9C1-\uA9CD\uA9DE\uA9DF\uAA5C-\uAA5F\uAADE\uAADF\uAAF0\uAAF1\uABEB\uFD3E\uFD3F\uFE10-\uFE19\uFE30-\uFE52\uFE54-\uFE61\uFE63\uFE68\uFE6A\uFE6B\uFF01-\uFF03\uFF05-\uFF0A\uFF0C-\uFF0F\uFF1A\uFF1B\uFF1F\uFF20\uFF3B-\uFF3D\uFF3F\uFF5B\uFF5D\uFF5F-\uFF65]{4,}|(?:(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:a[c-gil-oq-uwxz]|b[abd-jmnoq-tvwyz]|c[acdf-ik-orsu-z]|d[dejkmoz]|e[ceghr-u]|f[ijkmor]|g[abd-ilmnp-uwy]|h[kmnrtu]|i[del-oq-t]|j[emop]|k[eghimnprwyz]|l[abcikr-vy]|m[acdeghk-z]|n[acefgilopruz]|om|p[ae-hk-nrstwy]|qa|r[eosuw]|s[a-eg-or-vxyz]|t[cdfghj-prtvwz]|u[agksyz]|v[aceginu]|w[fs]|y[etu]|z[amrw]|app|bi[doz]|boo|cab|ceo|com|da[dy]|eat|edu|esq|fly|foo|go[pv]|hiv|how|in[gkt]|kim|mil|mo[ev]|ne[tw]|ngo|on[gl]|ooo|org|pro|pub|re[dn]|rip|tax|tel|soy|top|uno|vet|wed|wtf|xxx|xyz|[a-z]{4,20})))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b|$))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/;  // jshint ignore:line

      function processEmailAddressesThen(process, text) {
        if (text.indexOf('@', 1)) {
          var parts = text.split(emailAddrDetectRe);
          for (var i = 1; i < parts.length; i += 2) {
            var addr = parts[i];
            parts[i] = '<a href="mailto:' + HTML.escapeDoubleQuotedAttr(addr) + '">' + HTML.escapeElementContent(addr) + '</a>';
          }
          for (var j = 0; j < parts.length; j += 2) {
            parts[j] = process(parts[j]);
          }
          return parts.join('');
        } else {
          return process(text);
        }
      }

      function processUrlsThen(process, text) {
        var parts = text.split(uriDetectRe);
        for (var i = 1; i < parts.length; i += 3) {
          var uri = parts[i];
          var scheme = parts[i+1];
          if (!scheme && uri.indexOf('/') < 0 || parts[i-1].slice(-1) === '@') {
            var ambiguous = parts[i-1] + uri;
            var ambiguousProcessed = process(ambiguous);
            if (ambiguousProcessed.indexOf('</a>', ambiguousProcessed.length - 4) > 0) {
              parts[i] = ambiguousProcessed;
              parts[i-1] = parts[i+1] = '';
              continue;
            }
          }
          parts[i] = '<a target="_blank" rel="nofollow" href="' + (scheme ? '' : 'http://') + HTML.escapeDoubleQuotedAttr(uri) + '">' +
            HTML.escapeElementContent(uri);
          parts[i+1] = '</a>';
        }
        for (i = 0; i < parts.length; i += 3) {
          parts[i] = process(parts[i]);
        }
        return parts.join('');
      }

      return angular.bind(null, processUrlsThen, angular.bind(null, processEmailAddressesThen, HTML.escapeElementContent));
    });
  }())
])

.factory('AB', [
  '$window',
  (function () {
    return _.once(function ($window) {
      return {
        // To generate the salt for an experiment:
        // [0,0,0,0,0,0].map(function () { return Math.floor(Math.random() * 32).toString(32) }).join('')
        chooseTreatment: function (salt, treatments) {
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
    });
  }())
])

.constant('KEY', {
  UP: 38,
  DOWN: 40,
  ENTER: 13,
  ESC: 27,
  TAB: 9,
  DEL: 46,
  F2: 113,
  SPACE: 32,
  BSPACE: 8
});
