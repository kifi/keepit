'use strict';

angular.module('kifi.tagService', [])

.factory('tagService', [
	'$http', 'env', '$q', '$rootScope',
	function ($http, env, $q, $rootScope) {
		var list = [],
			fetchAllPromise = null;

		function indexById(id) {
			for (var i = 0, l = list.length; i < l; i++) {
				if (list[i].id === id) {
					return i;
				}
			}
			return -1;
		}

		return {
			list: list,

			fetchAll: function (force) {
				if (!force && fetchAllPromise) {
					return fetchAllPromise;
				}

				var url = env.xhrBase + '/collections/all';
				var config = {
					params: {
						sort: 'user',
						_: Date.now().toString(36)
					}
				};

				fetchAllPromise = $http.get(url, config).then(function (res) {
					var tags = res.data && res.data.collections || [];
					list.length = 0;
					list.push.apply(list, tags);
					return list;
				});

				return fetchAllPromise;
			},

			create: function (name) {
				var url = env.xhrBase + '/collections/create';

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
					var index = indexById(id);
					if (index !== -1) {
						list.splice(index, 1);
					}
				}

				var url = env.xhrBase + '/collections/' + tagId + '/delete';
				return $http.post(url).then(function () {
					removeTag(tagId);
					$rootScope.$emit('tags.remove', tagId);
					return tagId;
				});
			},

			rename: function (tagId, name) {
				function renameTag(id, name) {
					var index = indexById(id);
					if (index !== -1) {
						var tag = list[index];
						tag.name = name;
						return tag;
					}
					return null;
				}

				var url = env.xhrBase + '/collections/' + tagId + '/update';
				return $http.post(url, {
					name: name
				}).then(function (res) {
					var tag = res.data;
					return renameTag(tag.id, tag.name);
				});
			},

			removeKeepsFromTag: function (tagId, keepIds) {
				var url = env.xhrBase + '/collections/' + tagId + '/removeKeeps';
				$http.post(url, keepIds).then(function (res) {
					// handle stuff
					keepIds.forEach(function (keepId) {
						$rootScope.$emit('tags.removeFromKeep', {tagId: tagId, keepId: keepId});
					});
					return res;
				});
			}
		};
	}
]);
