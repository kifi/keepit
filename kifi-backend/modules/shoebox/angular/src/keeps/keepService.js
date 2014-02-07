'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
	'$http', 'env',
	function ($http, env) {

		var list = [],
			selected = {},
			before = null,
			limit = 30;

		function getKeepId(keep) {
			if (keep) {
				if (typeof keep === 'string') {
					return keep;
				}
				return keep.id || null;
			}
			return null;
		}

		var api = {
			list: list,

			isSelected: function (keep) {
				var id = getKeepId(keep);
				if (id) {
					return selected.hasOwnProperty(id);
				}
				return false;
			},

			select: function (keep) {
				var id = getKeepId(keep);
				if (id) {
					selected[id] = true;
					return true;
				}
				return false;
			},

			unselect: function (keep) {
				var id = getKeepId(keep);
				if (id) {
					delete selected[id];
					return true;
				}
				return false;
			},

			toggleSelect: function (keep) {
				if (api.isSelected(keep)) {
					return api.unselect(keep);
				}
				return api.select(keep);
			},

			getFirstSelected: function () {
				var id = _.keys(selected)[0];
				if (!id) {
					return null;
				}

				for (var i = 0, l = list.length, keep; i < l; i++) {
					keep = list[i];
					if (keep.id === id) {
						return keep;
					}
				}

				return null;
			},

			getSelectedLength: function () {
				return _.keys(selected).length;
			},

			getSelected: function () {
				return list.filter(function (keep) {
					return keep.id in selected;
				});
			},

			selectAll: function () {
				selected = _.reduce(list, function (map, keep) {
					map[keep.id] = true;
					return map;
				}, {});
			},

			unselectAll: function () {
				selected = {};
			},

			isSelectedAll: function () {
				return list.length && list.length === api.getSelectedLength();
			},

			toggleSelectAll: function () {
				if (api.isSelectedAll()) {
					return api.unselectAll();
				}
				return api.selectAll();
			},

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
			},

			joinTags: function (keeps, tags) {
				var idMap = _.reduce(tags, function (map, tag) {
					map[tag.id] = tag;
					return map;
				}, {});

				_.forEach(keeps, function (keep) {
					keep.tagList = _.map(keep.collections || keep.tags, function (tagId) {
						return idMap[tagId] || null;
					});
				});
			}
		};

		return api;
	}
]);
