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

      function sum(arr, i, j) {
        var total = 0;
        for (var k = i; k < j; k++) {
          total += arr[k];
        }
        return total;
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

      function balanceTwoLines(element, words) {
        var widths = measure(element, words);
        var diff = widths.total - 2 * widths.words[0] - widths.space;
        for (var i = 1; i < words.length && diff > 0; i++) {
          var delta = widths.words[i] + widths.space;
          if (delta < 0.9 * diff) {  // .9 to favor bottom-heavy titles
            diff -= 2 * delta;
          } else {
            break;
          }
        }
        if (i < words.length) {
          element.empty().append(
            textNode(words.slice(0, i).join(' ')), br(),
            textNode(words.slice(i).join(' ')));
        }
      }

      function balanceThreeLines(element, words) {
        var numWords = words.length;
        var widths = measure(element, words);
        var ideal = (widths.total - 2 * widths.space) / 3;
        var best = [0, 0], leastError = Infinity;
        for (var i = 1; i < numWords - 1; i++) {
          var line1Error = Math.abs(sum(widths.words, 0, i) - ideal);
          for (var j = i + 1; j < numWords; j++) {
            var line2Error = Math.abs(sum(widths.words, i, j) - ideal);
            var line3Error = Math.abs(sum(widths.words, j, numWords) - ideal);
            var error = line1Error + line2Error + line3Error;
            if (error < leastError) {
              leastError = error;
              best = [i, j];
            }
          }
        }
        element.empty().append(
          textNode(words.slice(0, best[0]).join(' ')), br(),
          textNode(words.slice(best[0], best[1]).join(' ')), br(),
          textNode(words.slice(best[1]).join(' ')));
      }

      function measure(element, words) {
        var wordEls = words.map(span);
        var clone = element.clone().empty().append(wordEls[0]);
        for (var i = 1; i < wordEls.length; i++) {
          clone.append(textNode(' '), wordEls[i]);
        }
        clone
          .css({
            'position': 'absolute',
            'top': '-99999px',
            'left': '-99999px',
            'right': 'auto',
            'bottom': 'auto',
            'width': 'auto',
            'height': 'auto',
            'visibility': 'hidden'
          })
          .insertAfter(element);
        var totalWidth = clone[0].getBoundingClientRect().width;
        var wordWidths = wordEls.map(function (el) { return el.getBoundingClientRect().width; });
        clone.remove();
        return {
          words: wordWidths,
          space: wordWidths.reduce(function (t, w) { return t - w; }, totalWidth) / (words.length - 1),
          total: totalWidth
        };
      }

      function fit(element) {
        var boxWidth = element[0].clientWidth;
        var boxHeight = element[0].clientHeight;
        var text = element.text().trim();
        var words = text.split(' ').filter(function (word) { return word; });
        if (words.length) {
          var lineHeight = parseFloat(element.css('line-height'), 10);
          if (Math.round(element[0].clientHeight / lineHeight) === 1) { // one line
            var widthPct = measure(element, words).total / boxWidth;
            if (widthPct <= 0.75) {
              sizeTo(element, sizes[1][widthPct <= 0.6 ? 1 : 0]);
            } else if (words.length > 1) {
              balanceTwoLines(element, words);
            }
          } else if (words.length > 1) {  // multiple words, multiple lines
            // TODO: shrink any long words to container width?
            if (Math.round(boxHeight / lineHeight) === 2 || sizeToUntil(element, sizes[2], 2)) {
              balanceTwoLines(element, words);
            } else {
              sizeToUntil(element, sizes[3], 3);
              balanceThreeLines(element, words);
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
