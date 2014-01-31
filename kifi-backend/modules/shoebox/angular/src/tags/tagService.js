'use strict';

angular.module('kifi.tagService', [])

.factory('tagService', [
	'$http', 'env',
	function ($http, env) {
		return {
			getTags: function () {
				var url = env.xhrBase + '/collections/all?sort=user&_=' + Date.now().toString(36);
				return $http.get(url).then(function (res) {
					return res.data && res.data.collections || [];
				});
			}
		};
	}
]);
