// @require scripts/util.js

/**
 * --------------------
 *     Score Filter
 * --------------------
 *
 * Filters an array by search input.
 * Sorts the result by relevance score.
 */

this.scorefilter = (function () {
	'use strict';

	var DEFAULT_PRE = '<b>',
		DEFAULT_POST = '</b>',
		DEFAULT_DELIMITER = /[\s+,.]/,
		LOCALE_COMPARE_OPTIONS = {
			usage: 'search',
			sensitivity: 'base'
		},
		PRE,
		POST;

	/**
	 * Returns a highlighted string (html) of the matched text.
	 *
	 * @param {string} str - A candidate string to highlight its matched part
	 * @param {number} index - A start index to highlight
	 * @param {number} length - A length of highlighted part of the text
	 *
	 * @return {string} A highlighted text
	 */

	function highlight(str, index, length) {
		return str.substr(0, index) + PRE + str.substr(index, length) + POST + str.substr(index + length);
	}

	/**
	 * Tests whether the first string starts with the second string in locale comparison.
	 *
	 * @param {string} str1 - A string to test
	 * @param {string} str2 - A prefix string to match
	 *
	 * @return {boolean} Whether the first string starts with the second string in locale comparison
	 */

	function localeStartsWith(str1, str2) {
		return localeEquals(str1.substring(0, str2.length), str2);
	}

	/**
	 * Tests whether the first string and the second string are equal in locale comparison.
	 *
	 * @param {string} str1 - A string
	 * @param {string} str2 - Another string
	 * @param {Object} [options] - An option for locale compare
	 *
	 * @return {boolean} Whether the first string and the second string are equal in locale comparison
	 */

	function localeEquals(str1, str2, options) {
		return str1.localeCompare(str2, void 0, options || LOCALE_COMPARE_OPTIONS) === 0;
	}

	/**
	 * Returns a score based on an index value.
	 *
	 * @param {number} ti - A candidate part index
	 * @param {number} ni - An input part index
	 *
	 * @return {number} A score
	 */

	function getIndexScore(ti, ni) {
		if (ti === ni) {
			return 5;
		}
		if (ti < ni) {
			return 3;
		}
		return 2;
	}

	/**
	 * Calculates and returns an object containing score and formatted (highlighted) text
	 * for the current candidate.
	 * Returns null if the candidate is not a match.
	 *
	 * @param {string[]} textParts - An array of candidate parts
	 * @param {string[]} valParts - An array of input text parts
	 * @param {boolean} formatResult - Whether to format the matched string
	 * @param {boolean} caseSensitive - Whether to match in case sensitive way
	 *
	 * @return {Object} A match result object
	 */

	function scoreCandidate(textParts, valParts, formatResult, caseSensitive) {
		formatResult = formatResult ? true : false;

		var score = 0,
			formatted = formatResult && valParts.slice(),
			lowerNames = caseSensitive ? valParts : valParts.map(toLowerCase),
			startsWith = util.startsWith;

		for (var ti = 0, tl = textParts.length, term, termLen; ti < tl; ti++) {
			var maxTScore = 0,
				tHighlight,
				highlightedNIdx;

			term = textParts[ti];
			termLen = term.length;

			for (var ni = 0, nl = valParts.length, name; ni < nl; ni++) {
				name = lowerNames[ni];
				if (name.length < termLen) {
					continue;
				}

				var tScore = 0,
					nMatchIdx = 0,
					addIndexScore = true;

				if (name === term) {
					tScore += 4;
				}
				else if (startsWith(name, term)) {
					tScore += 2;
				}
				else if (localeStartsWith(name, term)) {
					if (name.length === termLen) {
						tScore += 3;
					}
				}
				else {
					nMatchIdx = name.indexOf(term, 1);
					if (nMatchIdx === -1) {
						continue;
					}

					tScore += 1;
					addIndexScore = false;
				}

				if (addIndexScore) {
					tScore += getIndexScore(ti, ni);
				}

				if (tScore > maxTScore) {
					maxTScore = tScore;
					if (formatResult) {
						highlightedNIdx = ni;
						tHighlight = highlight(valParts[ni], nMatchIdx, termLen);
					}
				}
			}

			if (maxTScore === 0) {
				return null;
			}

			score += maxTScore;

			if (formatResult && tHighlight) {
				formatted[highlightedNIdx] = tHighlight;
			}
		}

		return {
			score: score,
			formatted: formatted.join(' ')
		};
	}

	function getTextValues(list, extract) {
		switch (typeof extract) {
		case 'function':
			return util.map(list, extract);
		case 'string':
		case 'number':
			return util.pluck(list, extract);
		default:
			return list;
		}
	}

	function toLowerCase(str) {
		return str && str.toLowerCase();
	}

	function trim(str) {
		return str ? str.trim() : '';
	}

	function scoreComparator(a, b) {
		return b.score - a.score;
	}

	return {
		scoreCandidate: scoreCandidate,
		filter: function (text, list, options) {
			PRE = options && options.pre || '';
			POST = options && options.post || '';

			var extract = options && options.extract,
				delimiter = options && options.delimiter || DEFAULT_DELIMITER,
				caseSensitive = options && options.caseSensitive ? true : false,

				formatResult = (PRE || POST) ? true : false,
				vals = getTextValues(list, extract).map(trim),
				textVal = trim(text);

			if (!caseSensitive) {
				textVal = textVal.toLowerCase();
			}

			var textParts = textVal.split(delimiter),
				results = [],
				result;
			for (var i = 0, l = vals.length, val; i < l; i++) {
				val = vals[i];
				result = scoreCandidate(textParts, val.split(delimiter), formatResult, caseSensitive);
				if (result) {
					results.push({
						string: result.formatted,
						score: result.score,
						original: list[i]
					});
				}
			}

			results.sort(scoreComparator);

			PRE = DEFAULT_PRE;
			POST = DEFAULT_POST;

			return results;
		}
	};

})(this);
