/**
 * ---------------
 *      Util
 * ---------------
 *
 * Contains utility functions
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-07-2013
 */

var util = this.util = (function () {
	'use strict';

	return {
		/**
		 * Returns an array of values of an object.
		 * Same as Object.keys except that it retrieves values not keys.
		 *
		 * @param {Array|Object} obj - An object or an array to get values from
		 * @param {string[]} [keys] - Optional keys to iterate over
		 *
		 * @return {*} an array of values
		 */
		values: function (obj, keys) {
			if (!keys) {
				keys = Object.keys(obj);
			}
			var vals = [];
			for (var i = 0, l = keys.length; i < l; i++) {
				vals.push(obj[keys[i]]);
			}
			return vals;
		},

		/**
		 * Removes a value from an array if exists.
		 * Returns true if removed, false otherwise.
		 *
		 * @param {Array} Array to remove from
		 * @param {Array} A value to remove
		 *
		 * @return {boolean} Whether the value was found and removed
		 */
		remove: function (arr, val) {
			var i = arr.indexOf(arr, val);
			if (i !== -1) {
				arr.splice(i, 1);
				return true;
			}
			return false;
		},

		/**
		 * Returns a new array of values of the second array
		 * that are not present in the first array.
		 *
		 * @param {Array} first array
		 * @param {Array} second array
		 *
		 * @return {Array} new array
		 */
		getNewValues: (function () {
			function uniqueFilter(val) {
				return this.indexOf(val) === -1;
			}

			return function (arr, list) {
				return list.filter(uniqueFilter, arr);
			};
		})(),

		/**
		 * Adds values from the second array to the first array
		 * that are not present in the first array.
		 * It mutates the first array and returns the number of values added.
		 *
		 * @param {Array} An array to add to
		 * @param {Array} An array of values to add
		 *
		 * @return {number} how many were added
		 */
		addUnique: function (arr, list) {
			var prevLen = arr.length;
			for (var i = 0, len = list.length, val; i < len; i++) {
				val = list[i];
				if (arr.indexOf(val) === -1) {
					arr.push(val);
				}
			}
			return arr.length - prevLen;
		},

		/**
		 * Prepends values from the second array to the first array
		 * that are not present in the first array.
		 * It mutates the first array and returns the number of values added.
		 *
		 * @param {Array} An array to prepend to
		 * @param {Array} An array of values to prepend
		 *
		 * @return {number} how many were added
		 */
		prependUnique: function (arr, list) {
			var prevLen = arr.length;
			for (var i = 0, len = list.length, val; i < len; i++) {
				val = list[i];
				if (arr.indexOf(val) === -1) {
					arr.unshift(val);
				}
			}
			return arr.length - prevLen;
		},

		/**
		 * Removes the second array values from the first array.
		 * It mutates the first array and returns an array of values removed.
		 *
		 * @param {Array} An array to remove from
		 * @param {Array} An array of values to remove
		 *
		 * @return {Array} An array of values removed
		 */
		removeList: function (arr, list) {
			var indices = [];
			for (var i = 0, len = list.length, index; i < len; i++) {
				index = arr.indexOf(list[i]);
				if (index !== -1) {
					indices.push(index);
				}
			}

			return util.removeIndices(arr, indices, true);
		},

		removeIndices: function (arr, indices, sorted) {
			var removedList = [];
			if (indices && indices.length) {
				if (!sorted) {
					indices.sort();
				}

				for (var i = indices.length - 1; i >= 0; i--) {
					removedList.push(arr.splice(indices[i], 1)[0]);
				}
			}
			return removedList;
		},

		indicesOf: function (arr, fn, that) {
			var indices = [];
			for (var i = 0, len = arr.length; i < len; i++) {
				if ((i in arr) && fn.call(that, arr[i], i, arr)) {
					indices.push(i);
				}
			}
			return indices;
		},

		/**
		 * Iterates over an array or an object and calls the callback function for each element.
		 *
		 * @param {Array|Object} obj - An object or an array to iterate over
		 * @param {function} fn - A callback function
		 * @param {*} [that] - 'this' inside the function
		 */
		each: function (obj, fn, that) {
			if (Array.isArray(obj)) {
				return obj.forEach(fn, that);
			}

			var keys = Object.keys(obj);
			for (var i = 0, l = keys.length, key; i < l; i++) {
				key = keys[i];
				fn.call(that, obj[key], key, obj);
			}
		},

		/**
		 * Iterates over an array or an object and calls the callback function for each element and returns the results.
		 *
		 * @param {Array|Object} obj - An array or an object to iterate over
		 * @param {function} fn - A callback function
		 * @param {*} [that] - 'this' inside the function
		 *
		 * @return {Array|Object} Results for all iterations
		 */
		map: function (obj, fn, that) {
			if (Array.isArray(obj)) {
				return obj.map(fn, that);
			}

			var keys = Object.keys(obj),
				res = {};
			for (var i = 0, l = keys.length, key; i < l; i++) {
				key = keys[i];
				res[key] = fn.call(that, obj[key], key, obj);
			}

			return res;
		},

		/**
		 * Iterates over an array or an object and extracts a list or a map of property values.
		 *
		 * @param {Array|Object} obj - An array or an object to iterate over
		 * @param {string} name - A property name to extract
		 *
		 * @return {Array|Object} Results for all iterations
		 */
		pluck: function (obj, name) {
			return util.map(obj, function (val) {
				return val == null ? void 0 : val[name];
			});
		},

		/**
		 * Limits the value by min and/or max and returns the value.
		 *
		 * @param {number} val - A value to limit
		 * @param {number} [min] - A minimum value. ignored if null
		 * @param {number} [max] - A maximum value. ignored if null
		 *
		 * @return {number} limited value
		 */
		minMax: function (val, min, max) {
			if (min != null && val <= min) {
				return min;
			}
			else if (max != null && val >= max) {
				return max;
			}
			return val;
		},

		/**
		 * Returns a new object where its keys and values are flipped from original object.
		 *
		 * @param {Array|Object} obj - An array or an object to flip its keys and values
		 *
		 * @return {Array|Object} A new flipped object
		 */
		flip: function (obj) {
			var keys = Object.keys(obj),
				res = {};
			for (var i = 0, l = keys.length, key; i < l; i++) {
				key = keys[i];
				res[obj[key]] = key;
			}
			return res;
		},

		/**
		 * Returns a new object where its keys are values of original object.
		 *
		 * @param {Array|Object} obj - An array or an object to get values from
		 * @param {*} [val] - A value to set for every keys
		 *
		 * @return {Array|Object} A new object
		 */
		toKeys: function (obj, val) {
			var keys = Object.keys(obj),
				res = {};
			for (var i = 0, l = keys.length; i < l; i++) {
				res[obj[keys[i]]] = val;
			}
			return res;
		},

		/**
		 * Finds and returns the first key where the callback returns truthy value.
		 * Returns -1 if not found.
		 *
		 * @param {Array|Object} obj - An array or an object to iterate over
		 * @param {function} fn - A callback function
		 * @param {*} [that] - 'this' inside the function
		 *
		 * @return {string|number} The first key that callback returns truthy value. -1 if not found.
		 */
		keyOf: function (obj, fn, that) {
			var i, l;
			if (Array.isArray(obj)) {
				for (i = 0, l = obj.length; i < l; i++) {
					if ((i in obj) && fn.call(that, obj[i], i, obj)) {
						return i;
					}
				}
			}
			else {
				var keys = Object.keys(obj),
					key;
				for (i = 0, l = keys.length; i < l; i++) {
					key = keys[i];
					if (fn.call(that, obj[key], key, obj)) {
						return key;
					}
				}
			}
			return -1;
		},

		/**
		 * Returns the number of keys in an object.
		 *
		 * @param {Object} obj - An object to get the size of
		 *
		 * @return {number} The number of keys
		 */
		size: function (obj) {
			return obj == null ? 0 : Object.keys(obj).length;
		},

		/**
		 * Returns true if the first string starts with the second string.
		 *
		 * @param {string} str - A string to search from
		 * @param {string} prefix - A prefix string
		 * @return {boolean} Whether a string starts with the specified prefix
		 */
		startsWith: function (str, prefix) {
			return str.lastIndexOf(prefix, 0) === 0;
		},

		/**
		 * Returns true if the first string ends with the second string.
		 *
		 * @param {string} str - A string to search from
		 * @param {string} suffix - A suffix string
		 * @return {boolean} Whether a string ends with the specified suffix
		 */
		endsWith: function (str, suffix) {
			return str.indexOf(suffix, str.length - suffix.length) !== -1;
		},

		/**
		 * Returns a simple string representation of a DOM node as a combination of a tag name and class names.
		 * e.g. "TAG_NAME.className1.className2..." for a DOM node.
		 *
		 * @param {DOMNode} node - a DOM node
		 *
		 * @return {string} a simple string representation of a DOM node
		 */
		DOMtoString: (function () {
			function reduceClassName(res, name) {
				return res + '.' + name;
			}

			function normalizeClass(className) {
				if (className) {
					className = className.trim().split(/\s+/).reduce(reduceClassName, '');
				}
				return className || '';
			}

			return function (el) {
				return el ? (el.nodeName || '') + normalizeClass(el.className) : '';
			};
		})()

	};

})();
