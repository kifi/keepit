/**
 * Angular directive to fit 1-3 lines of text in a container by adjusting
 * font-size and line-height. Also attempts to optimize wrapping aesthetics
 * by inserting line breaks.
 *
 * To use, specify [font-size, line-height] pairs in pixels:
 * <div kf-fit 1="[[42,54],...]" 2="[[24,30],...]" 3="[[16,20],...]">Consider the Octopus</div>
 */
'use strict';
angular.module('kifi')

.directive('kfFit', ['$document', '$timeout', function ($document, $timeout) {
  return {
    restrict: 'A',
    scope: {},
    compile: function (elem, attr) {
      function fontSizeAsc(p1, p2) {
        return p1[0] - p2[0];
      }

      function fontSizeDesc(p1, p2) {
        return p2[0] - p1[0];
      }

      function span(text) {
        var el = $document[0].createElement('span');
        el.textContent = text;
        return el;
      }

      function textNode(text) {
        return $document[0].createTextNode(text);
      }

      function br() {
        return $document[0].createElement('br');
      }

      function sizeTo(element, pair) {
        element.css({'font-size': pair[0] + 'px', 'line-height': pair[1] + 'px'});
      }

      function sizeToUntil(element, pairs, numLines) {
        for (var i = 0; i < pairs.length; i++) {
          var pair = pairs[i];
          sizeTo(element, pair);
          if (Math.round(element[0].clientHeight / pair[1]) <= numLines) {
            return true;
          }
        }
      }

      function balanceTwoLines(element, words, boxWidth) {
        var n = words.length;
        var rects = measureWords(element, words);
        var best, leastError = Infinity;
        for (var i = 1; i < n; i++) {
          var line1Width = rects[i - 1].right - rects[0].left;
          var line2Width = rects[n - 1].right - rects[i].left;
          if (line1Width <= boxWidth && line2Width <= boxWidth) {
            var error = Math.abs(line1Width - line2Width);
            if (error < leastError) {
              leastError = error;
              best = i;
            }
          }
        }
        if (best) {
          element.empty().append(
            textNode(words.slice(0, best).join(' ')), br(),
            textNode(words.slice(best).join(' ')));
        }
      }

      function balanceThreeLines(element, words, boxWidth) {
        var n = words.length;
        var rects = measureWords(element, words);
        var best, leastError = Infinity;
        for (var i = 1; i < n - 1; i++) {
          var line1Width = rects[i - 1].right - rects[0].left;
          if (line1Width <= boxWidth) {
            for (var j = i + 1; j < n; j++) {
              var line2Width = rects[j - 1].right - rects[i].left;
              var line3Width = rects[n - 1].right - rects[j].left;
              if (line2Width <= boxWidth && line3Width <= boxWidth) {  // jshint ignore:line
                var error =
                  Math.abs(line1Width - line2Width) +
                  Math.abs(line2Width - line3Width) +
                  Math.abs(line3Width - line1Width);
                if (error < leastError) {
                  leastError = error;
                  best = [i, j];
                }
              }
            }
          }
        }
        if (best) {
          element.empty().append(
            textNode(words.slice(0, best[0]).join(' ')), br(),
            textNode(words.slice(best[0], best[1]).join(' ')), br(),
            textNode(words.slice(best[1]).join(' ')));
        }
      }

      var measuringCss = {
        'position': 'absolute',
        'top': '-99999px',
        'left': '-99999px',
        'right': 'auto',
        'bottom': 'auto',
        'width': 'auto',
        'height': 'auto',
        'visibility': 'hidden'
      };

      function measureWidth(element, text) {
        var clone = element.clone().text(text).css(measuringCss).insertAfter(element);
        var width = clone[0].getBoundingClientRect().width;
        clone.remove();
        return width;
      }

      function measureWords(element, words) {
        var wordEls = words.map(span);
        var nodes = [wordEls[0]];
        for (var i = 1; i < wordEls.length; i++) {
          nodes.push(textNode(' '), wordEls[i]);
        }
        var clone = element.clone().empty().append(nodes).css(measuringCss).insertAfter(element);
        var rects = wordEls.map(function (el) { return el.getBoundingClientRect(); });
        clone.remove();
        return rects;
      }

      function fit(element) {
        var boxWidth = element[0].clientWidth;
        var boxHeight = element[0].clientHeight;
        var text = element.text().trim();
        var words = text.split(' ').filter(function (word) { return word; });
        if (words.length) {
          var lineHeight = parseFloat(element.css('line-height'), 10);
          if (Math.round(element[0].clientHeight / lineHeight) === 1) { // one line
            var widthPct = measureWidth(element, text) / boxWidth;
            if (widthPct <= 0.75) {
              sizeTo(element, sizes[1][widthPct <= 0.6 ? 1 : 0]);
            } else if (words.length > 1) {
              balanceTwoLines(element, words, boxWidth);
            }
          } else if (words.length > 1) {  // multiple words, multiple lines
            // TODO: shrink any long words to container width?
            if (Math.round(boxHeight / lineHeight) === 2 || sizeToUntil(element, sizes[2], 2)) {
              balanceTwoLines(element, words, boxWidth);
            } else if (sizeToUntil(element, sizes[3], 3) && words.length > 2) {
              balanceThreeLines(element, words, boxWidth);
            }
          }
        }
      }

      var sizes = {
        1: angular.fromJson(attr[1]).sort(fontSizeAsc),
        2: angular.fromJson(attr[2]).sort(fontSizeDesc),
        3: angular.fromJson(attr[3]).sort(fontSizeDesc)
      };
      elem.removeAttr('1 2 3').css('visibility', 'hidden');

      return function postLink(scope, element) {
        $timeout(function () {  // allowing binding/interpolation to complete
          if (element.attr('kf-fit') !== 'false') {
            fit(element);
          }
          element.css('visibility', '');
        });
      };
    }
  };
}]);
