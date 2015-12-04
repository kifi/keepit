'use strict';

angular.module('kifi')

  .factory('messageFormattingService', ['emojiService',
    function (emojiService) {

      var entityMap = {
        '&': '&amp;',
        '<': '&lt;',
        '>': '&gt;',
        '"': '&quot;',
        "'": '&#39;', // jshint ignore:line
        '/': '&#x2F;',
        '`': '&#x60;',
        '=': '&#x3D;'
      };

      function escapeHtml (string) {
        return String(string).replace(/[&<>"'`=\/]/g, function fromEntityMap (s) {
          return entityMap[s];
        });
      }

      var kifiSelMarkdownToLinkRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:((?:\\\)|[^)])*)\)/;
      //var kifiSelMarkdownToTextRe = /\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:(?:\\\)|[^)])*\)/;
      var escapedBackslashOrRightParenRe = /\\([\)\\])/g;
      var escapedBackslashOrRightBracketRe = /\\([\]\\])/g;
      var emailAddrRe = /(?:\b|^)([a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+)(?:\b|$)/; // jshint ignore:line
      var uriRe = /(?:\b|^)((?:(?:(https?|ftp):\/\/|www\d{0,3}[.])?(?:[a-z0-9](?:[-a-z0-9]*[a-z0-9])?\.)+(?:com|edu|biz|gov|in(?:t|fo)|mil|net|org|name|coop|aero|museum|[a-z][a-z]\b))(?::[0-9]{1,5})?(?:\/(?:[^\s()<>]*[^\s`!\[\]{};:.'",<>?«»()“”‘’]|\((?:[^\s()<>]+|(?:\([^\s()<>]+\)))*\))*|\b))(?=[\s`!()\[\]{};:.'",<>?«»“”‘’]|$)/; // jshint ignore:line

      var imageUrlRe = /^[^?#]*\.(?:gif|jpg|jpeg|png)$/i;
      //var lineBreaksRe = /\n([ \t\r]*\n)?(?:[ \t\r]*\n)*/g;

      var stringIsEmpty = function(str) {
        return !str || str.length === 0;
      };

      var format = function(text) {
        if (stringIsEmpty(text)) {
          return [];
        }

        var parts = text.split(kifiSelMarkdownToLinkRe);

        // message items
        var items = [];

        // the first item will always be a non markdown string
        // if there are groupings that are found, they will be
        // found as: [parts[i + 1][(parts[i + 2])]
        for (var i = 0; i < parts.length; i += 3) {

          // process the text part
          var textPart = parts[i];
          items = items.concat(processPlainText(textPart));

          // if there is kifi markdown to process
          if (i + 2 < parts.length) {
            // process the kifi markdown part
            items = items.concat(processKifiMarkdown({
              presented: parts[i + 1],
              payload: parts[i + 2]
            }));
          }
        }

        //for (i = items.length - 1; i >= 0; i--) {
        //  var item = items[i];
        //  if (item.data && item.data.text) {
        //    item.data.text = item.data.text.trim();
        //    if (item.data.text.length === 0) {
        //      items.splice(i, 1);
        //    }
        //  }
        //
        //}

        // now we want to combine
        return items;

      };

      var processKifiMarkdown = function(markdownData) {
        var selector = markdownData.payload.replace(escapedBackslashOrRightParenRe, '$1');
        return [
          {
            type: 'LOOK_HERE',
            data: {
              link: 'x-kifi-sel:' + escapeHtml(selector),
              title: escapeHtml(formatKifiSelRangeText(selector)),
              text: markdownData.presented.replace(escapedBackslashOrRightBracketRe, '$1')
            }
          }
        ];
      };

      var processEmail = function(text) {
        if (stringIsEmpty(text)) {
          return [];
        }

        var items = [];

        if (~text.indexOf('@', 1)) { // jshint ignore:line
          var parts = text.split(emailAddrRe);
          for (var i = 0; i < parts.length; i += 2) {
            items = items.concat(processPlainTextForEmojis(parts[i]));
            if (i + 1 < parts.length) {
              var escapedAddr = escapeHtml(parts[i + 1]);
              var data = {
                text: escapedAddr
              };
              items.push({
                type: 'EMAIL',
                data: data
              });
            }

          }
        } else {
          items = items.concat(processPlainTextForEmojis(text));
        }
        return items;
      };

      var processPlainText = function(text) {
        if (stringIsEmpty(text)) {
          return [];
        }

        var items = [];
        var parts = text.split(uriRe);
        //console.log('URI');
        //console.log(parts);
        //console.log('\n');
        for (var i = 0; i < parts.length; i += 3) {
          // first item is actually plain text no matter what
          // format emoji
          var uri = parts[i + 1];
          var scheme = parts[i + 2];
          if (!scheme && uri && uri.indexOf('/') < 0 || parts[i].slice(-1) === '@') {
            var ambiguous = parts[i] + uri;
            var processed = processEmail(ambiguous);
            var filter = function(item) {
              return item.type === 'EMAIL';
            };
            if (processed.filter(filter).length > 0) {
              items = items.concat(processed);
              continue;
            }
          }

          // add plain text
          items = items.concat(processPlainTextForEmojis(parts[i]));
          if (uri) {
            //var escapedUri = escapeHtml(uri);
            //var escapedUrl = (scheme ? '' : 'http://') + escapedUri;
            var basicUrl = (scheme ? '' : 'http://') + uri;
            var item;
            if (imageUrlRe.test(uri)) {
              item = {
                type: 'IMAGE',
                data: {
                  src: basicUrl
                }
              };
            } else {
              item = {
                type: 'LINK',
                data: {
                  link: basicUrl,
                  text: basicUrl
                }
              };
            }
            items.push(item);
          }

        }

        return items;
      };

      var processPlainTextForEmojis = function(text) {
        if (stringIsEmpty(text)) {
          return [];
        }

        var finalText = null;
        if (emojiService) {
          finalText = escapeHtml(emojiService.supported() ? emojiService.decode(text) : text);
        } else {
          finalText = escapeHtml(text);
        }

        return [
          {
            type: 'TEXT_PLAIN',
            data: {
              text: finalText
            }
          }
        ];
      };

      var formatKifiSelRangeText = (function () {
        var replaceRe = /[\u001e\u001f]/g;
        var replacements = {'\u001e': '\n\n', '\u001f': ''};
        function replace(s) {
          return replacements[s];
        }
        // var decodeURIComponent = function(t) {return t;};
        return function (selector) {
          return decodeURIComponent(selector.split('|')[1]).replace(replaceRe, replace);
        };
      }());

      return {
        full: format
      };
    }
  ]);