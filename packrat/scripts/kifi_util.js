// @require scripts/lib/q.min.js

/**
 * ---------------
 *    Kifi Util
 * ---------------
 *
 * Contains utility functions for Kifi APIs
 *
 * @author Joon Ho Cho <joon@42go.com>
 * @date 10-17-2013
 */

this.kifiUtil = (function () {
	'use strict';

	return {
		/**
		 * Makes a request to the server and returns a deferred promise object.
		 *
		 * @param {string} name - A request name
		 * @param {*} data - A request payload
		 * @param {string} errorMsg - An error message
		 *
		 * @return {Object} A deferred promise object
		 */
		request: function (name, data, errorMsg) {
			var deferred = Q.defer();
			api.port.emit(name, data, function (result) {
				log(name + '.result', result);
				if (result && result.success) {
					deferred.resolve(result.response);
				}
				else {
					deferred.reject(new Error(errorMsg));
				}
			});
			return deferred.promise;
		}

	};

})();
