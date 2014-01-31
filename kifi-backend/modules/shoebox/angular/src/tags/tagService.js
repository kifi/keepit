'use strict';

angular.module('kifi.tagService', [])

.factory('tagService', [
	'$http', 'env', '$q',
	function ($http, env, $q) {
		var list = [];

		return {
			list: list,

			fetchAll: function () {
				var url = env.xhrBase + '/collections/all?sort=user&_=' + Date.now().toString(36);
				return $http.get(url).then(function (res) {
					var tags = res.data && res.data.collections || [];
					list.length = 0;
					list.push.apply(list, tags);
					return list;
				});
			},

			create: function (name) {
				var url = env.xhrBase + '/collections/create';
				if (env.dev) {
					var deferred = $q.defer();
					var tag = {
						id: name + Date.now() + Math.floor(1000000 * Math.random()),
						name: name,
						keeps: 0
					};
					deferred.resolve(tag);
					list.unshift(tag);
					return deferred.promise;
				}

				return $http.post(url, {
					name: name
				}).then(function (res) {
					var tag = res.data;
					tag.keeps = tag.keeps || 0;
					list.unshift(tag);
					return tag;
				});
			},

			remove: function (tagId) {
				function removeTag(id) {
					for (var i = 0, l = list.length, tag; i < l; i++) {
						tag = list[i];
						if (tag.id === id) {
							return list.splice(i, 1);
						}
					}
				}

				if (env.dev) {
					var deferred = $q.defer();
					removeTag(tagId);
					deferred.resolve(true);
					return deferred.promise;
				}

				var url = env.xhrBase + '/collections/' + tagId + '/delete';
				return $http.post(url).then(function () {
					removeTag(tagId);
					return true;
				});
			}
		};
	}
]);
