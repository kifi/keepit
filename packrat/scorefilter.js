/**
 * scoreFilter is for searching a list using a query string.
 * Results are ordered by descending match score.
 */

(this.exports || this).scoreFilter = (function () {
  'use strict';

  var WORD_DELIMITER = /[\s,.]+/;
  var WORD_DELIMITER_CAPTURING = /([\s,.]+)/;
  var LOCALE_COMPARE_OPTIONS = {usage: 'search', sensitivity: 'base'};
  var COLLATOR = typeof Intl === 'undefined' ? null :
    new Intl.Collator(typeof navigator === 'undefined' ? 'en-US' : navigator.language, LOCALE_COMPARE_OPTIONS);

  /**
   * Tests whether the first string starts with the second string in locale comparison.
   */
  var localeStartsWith = COLLATOR ?
    function (str1, str2) {
      return COLLATOR.compare(str1.substr(0, str2.length), str2) === 0;
    } :
    function (str1, str2) {
      return str1.substr(0, str2.length).localeCompare(str2, void 0, LOCALE_COMPARE_OPTIONS) === 0;
    };

  /**
   * Scores how well a candidate string matches a filter query.
   *
   * @param {string[]} queryTerms - An array of search/filter query parts (lowercased words/terms)
   * @param {string} candidate - The match candidate
   * @param {number[]} matches - An optional array to which the start index (in candidate) of the
   *   best match for each query term will be inserted
   *
   * @return {Number} A match score (zero if the candidate does not match the query)
   */
  function scoreCandidate(queryTerms, candidate, matches) {
    var score = 0;
    var candidateParts = candidate.toLowerCase().split(WORD_DELIMITER_CAPTURING);

    for (var qi = 0, ql = queryTerms.length; qi < ql; qi++) {
      var qTerm = queryTerms[qi];
      var qTermLen = qTerm.length;
      var qTermScore = 0;
      var qTermMatchPartIdx;
      var qTermMatchCharIdx;

      for (var ci = 0, cl = candidateParts.length; ci < cl; ci += 2) {
        var cPart = candidateParts[ci];
        if (cPart.length < qTermLen) {
          continue;
        }

        var cPartScore = 0;
        var cPartMatchIdx = 0;
        var addIndexScore = true;

        if (cPart === qTerm) {
          cPartScore += 4;
        } else if (cPart.lastIndexOf(qTerm, 0) === 0) {  // startsWith
          cPartScore += 2;
        } else if (localeStartsWith(cPart, qTerm)) {
          if (cPart.length === qTermLen) {
            cPartScore += 3;
          }
        } else {
          cPartMatchIdx = cPart.indexOf(qTerm, 1);
          if (cPartMatchIdx === -1) {
            continue;
          }

          cPartScore += 1;
          addIndexScore = false;
        }

        if (addIndexScore) {
          cPartScore += qi === ci ? 5 : qi < ci ? 3 : 2;
        }

        if (qTermScore < cPartScore) {
          qTermScore = cPartScore;
          qTermMatchPartIdx = ci;
          qTermMatchCharIdx = cPartMatchIdx;
        }
      }

      if (qTermScore) {
        score += qTermScore;
        if (matches) {
          for (var cj = 0; cj < qTermMatchPartIdx; cj++) {
            qTermMatchCharIdx += candidateParts[cj].length;
          }
          matches[qi] = qTermMatchCharIdx;
        }
      } else {
        return 0;
      }
    }

    return score;
  }

  function scoreComparator(a, b) {
    return b.score - a.score;
  }

  function intComparator(a, b) {
    return a - b;
  }

  function getCandidate(o) {
    return o.candidate;
  }

  return {
    filter: function (query, candidates, extract) {
      var queryTerms = query.trim().toLowerCase().split(WORD_DELIMITER);
      var vals = extract ? candidates.map(extract) : candidates;
      var results = [];
      for (var i = 0, n = vals.length; i < n; i++) {
        var score = scoreCandidate(queryTerms, vals[i]);
        if (score) {
          results.push({candidate: candidates[i], score: score});
        }
      }
      results.sort(scoreComparator);
      return results.map(getCandidate);
    },

    /**
     * Returns the candidate string split into adjacent substrings on match boundaries. The substrings
     * alternate between matching and non-matching regions, beginning with a non-matching region
     * (which may be empty). This can be used to highlight matches.
     */
    splitOnMatches: function (query, candidate, extract) {
      var queryTerms = query.trim().toLowerCase().split(WORD_DELIMITER);
      var val = extract ? extract(candidate) : candidate;
      var starts = [];
      scoreCandidate(queryTerms, val, starts);
      var ends = starts.map(function (start, i) {
        return start + queryTerms[i].length;
      });
      // sorting starts and ends to deal with overlapping matches correctly
      starts.sort(intComparator);
      ends.sort(intComparator);
      var parts = [];
      var partsTotalLen = 0;
      for (var i = 0; i < starts.length; i++) {
        var start = starts[i];
        var end = ends[i];
        if (start > partsTotalLen) {
          parts.push(val.substring(partsTotalLen, start), val.substring(start, end));
        } else if (start === partsTotalLen) {
          parts.push('', val.substring(start, end));
        } else {
          parts[parts.length - 1] += val.substring(partsTotalLen, end);
        }
        partsTotalLen = end;
      }
      if (partsTotalLen < val.length) {
        parts.push(val.substr(partsTotalLen));
      }
      return parts;
    }
  };
}());
