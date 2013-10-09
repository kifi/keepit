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
		}

	};

})();
