'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
	'$http', 'env',
	function ($http, env) {

		var list = [],
			before = null,
			limit = 30;

		return {
			list: list,

			resetList: function () {
				before = null;
				list.length = 0;
			},

			getList: function (params) {
				var url = env.xhrBase + '/keeps/all';
				params = params || {};
				params.count = params.count || limit;
				params.before = before || void 0;

				var config = {
					params: params
				};

				return $http.get(url, config).then(function (res) {
					var data = res.data,
						keeps = data.keeps;
					if (!data.before) {
						list.length = 0;
					}
					list.push.apply(list, keeps);
					return list;
				});
			}
		};
	}
]);
