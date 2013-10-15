// @require scripts/util.js

/**
 * --------------------
 *     Score Filter
 * --------------------
 *
 * Filters an array by search input.
 * Sorts the result by relevance score.
 *
 * @author Andrew Conner <andrew@42go.com>
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-11-2013
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

	function highlight(name, index, termLen) {
		return name.substr(0, index) + PRE + name.substr(index, termLen) + POST + name.substr(index + termLen);
	}

	function localeStartsWith(str1, str2) {
		return localeEquals(str1.substring(0, str2.length), str2);
	}

	function localeEquals(str1, str2) {
		return str1.localeCompare(str2, void 0, LOCALE_COMPARE_OPTIONS) === 0;
	}

	function getIndexScore(ti, ni) {
		if (ti === ni) {
			return 5;
		}
		if (ti < ni) {
			return 3;
		}
		return 2;
	}

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
