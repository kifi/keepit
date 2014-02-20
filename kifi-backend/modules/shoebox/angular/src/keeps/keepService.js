'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
	'$http', 'env', '$q', '$timeout', '$document',
	function ($http, env, $q, $timeout, $document) {

		var list = [],
			selected = {},
			before = null,
			end = false,
			previewed = null,
			limit = 30,
			isDetailOpen = false,
			singleKeepBeingPreviewed = false,
			previewUrls = {},
			doc = $document[0];

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

			isDetailOpen: function () {
				return isDetailOpen;
			},

			isSingleKeep: function () {
				return singleKeepBeingPreviewed;
			},

			getPreviewed: function () {
				return previewed || null;
			},

			isPreviewed: function (keep) {
				return !!previewed && previewed === keep;
			},

			preview: function (keep) {
				if (keep == null) {
					singleKeepBeingPreviewed = false;
					isDetailOpen = false;
				}
				else {
					singleKeepBeingPreviewed = true;
					isDetailOpen = true;
				}
				previewed = keep;
				return keep;
			},

			togglePreview: function (keep) {
				if (api.isPreviewed(keep)) {
					return api.preview(null);
				}
				return api.preview(keep);
			},

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
					isDetailOpen = true;
					selected[id] = true;
					if (_.size(selected) === 1) {
						api.preview(keep);
					}
					else {
						previewed = null;
						singleKeepBeingPreviewed = false;
					}
					return true;
				}
				return false;
			},

			unselect: function (keep) {
				var id = getKeepId(keep);
				if (id) {
					delete selected[id];
					var countSelected = _.size(selected);
					if (countSelected === 0 && isDetailOpen === true) {
						api.preview(keep);
					}
					else if (countSelected === 1 && isDetailOpen === true) {
						api.preview(_.keys(selected)[0]);
					}
					else {
						previewed = null;
						singleKeepBeingPreviewed = false;
					}
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
				if (list.length === 0) {
					api.clearState();
				}
				else if (list.length === 1) {
					api.preview(list[0]);
				}
				else {
					previewed = null;
					isDetailOpen = true;
					singleKeepBeingPreviewed = false;
				}
			},

			unselectAll: function () {
				selected = {};
				api.clearState();
			},

			clearState: function () {
				previewed = null;
				isDetailOpen = false;
				singleKeepBeingPreviewed = false;
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

				if (end) {
					return $q.when([]);
				}

				return $http.get(url, config).then(function (res) {
					var data = res.data,
						keeps = data.keeps || [];
					if (!keeps.length) {
						end = true;
					}

					if (!data.before) {
						list.length = 0;
					}

					list.push.apply(list, keeps);
					before = list.length ? list[list.length - 1].id : null;

					_.forEach(keeps, function (keep) {
						keep.isMyBookmark = true;
					});

					return keeps;
				}).then(function (list) {
					api.fetchScreenshotUrls(list).then(function (urls) {
						$timeout(function () {
							api.prefetchImages(urls);
						});
						_.forEach(list, function (keep) {
							keep.screenshot = urls[keep.url];
						});
					});
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
			},

			fetchScreenshotUrls: function (keeps) {
				if (keeps && keeps.length) {
					var url = env.xhrBase + '/keeps/screenshot';
					return $http.post(url, {
						urls: _.pluck(keeps, 'url')
					}).then(function (res) {
						return res.data.urls;
					});
				}
				return $q.when([]);
			},

			prefetchImages: function (urls) {
				_.forEach(urls, function (imgUrl, key) {
					if (!(key in previewUrls)) {
						previewUrls[key] = imgUrl;
						doc.createElement('img').src = imgUrl;
					}
				});
			}
		};

		return api;
	}
]);
