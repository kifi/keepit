'use strict';

angular.module('kifi', [
	'ngCookies',
	'ngResource',
	'ngRoute',
	'ngSanitize',
	'ngAnimate',
	'ui.bootstrap',
	'util',
	'dom',
	'antiscroll',
	'infinite-scroll',
	'angularMoment',
	'focusWhen',
	'kifi.templates',
	'kifi.profileCard',
	'kifi.profileService',
	'kifi.detail',
	'kifi.tags',
	'kifi.keeps',
	'kifi.keep',
	'kifi.layout.leftCol',
	'kifi.layout.main',
	'kifi.layout.nav',
	'kifi.layout.rightCol'
])

.config([
	'$routeProvider', '$locationProvider', '$httpProvider',
	function ($routeProvider, $locationProvider, $httpProvider) {
		$locationProvider
			.html5Mode(true)
			.hashPrefix('!');

		$routeProvider.otherwise({
			redirectTo: '/'
		});

		$httpProvider.defaults.withCredentials = true;
	}
])

.factory('env', [
	'$location',
	function ($location) {
		var host = $location.host(),
			dev = /^dev\.ezkeep\.com|localhost$/.test(host),
			local = $location.port() === '9000',
			origin = local ? $location.protocol() + '//' + host : 'https://www.kifi.com';

		return {
			local: local,
			dev: dev,
			production: !dev,
			xhrBase: origin + '/site',
			xhrBaseEliza: origin.replace('www', 'eliza') + '/eliza/site',
			xhrBaseSearch: origin.replace('www', 'search') + '/search',
			picBase: (local ? '//d1scct5mnc9d9m' : '//djty7jcqog9qu') + '.cloudfront.net'
		};
	}
])

.controller('AppCtrl', [

	function () {}
]);

'use strict';

angular.module('antiscroll', [])

.directive('antiscroll', [
	'$timeout',
	function ($timeout) {
		function toDash(str) {
			return str.replace(/([a-z])([A-Z])/g, '$1-$2').toLowerCase();
		}

		return {
			restrict: 'A',
			transclude: true,
			link: function (scope, element, attrs) {
				var options;
				if (attrs.antiscroll) {
					options = scope.$eval(attrs.antiscroll);
				}
				scope.scroller = element.antiscroll(options).data('antiscroll');

				scope.refreshScroll = function () {
					return $timeout(function () {
						if (scope.scroller) {
							scope.scroller.refresh();
						}
					});
				};

				scope.refreshScroll();
			},
			template: function (element, attrs) {
				var tmp = '<div class="antiscroll-inner"';
				if ('antiInfiniteScroll' in attrs) {
					angular.forEach(['antiInfiniteScroll', 'antiInfiniteScrollDistance', 'antiInfiniteScrollDisabled', 'antiInfiniteScrollImmediateCheck'], function (name) {
						if (name in attrs) {
							tmp += ' ' + toDash(name).substring(5) + '="' + attrs[name] + '"';
						}
					});
				}
				tmp += ' ng-transclude></div>';
				return tmp;
			}
		};
	}
]);

'use strict';

angular.module('focusWhen', [])

.directive('focusWhen', [
	'$timeout',
	function ($timeout) {
		return {
			restrict: 'A',
			scope: {
				focusWhen: '='
			},
			link: function (scope, element /*, attrs*/ ) {

				function focus() {
					element.focus();
					scope.focusWhen = false;
				}

				scope.$watch('focusWhen', function (val) {
					if (val) {
						$timeout(focus);
					}
				});
			}
		};
	}
]);

'use strict';

angular.module('dom', [])

.value('dom', {
	scrollIntoViewLazy: function (el, padding) {
		var view;
		if (!(el && (view = el.offsetParent))) {
			return;
		}

		var viewTop = view.scrollTop,
			viewHeight = view.clientHeight,
			viewBottom = viewTop + viewHeight,
			elemTop = el.offsetTop,
			elemBottom = elemTop + el.offsetHeight;

		if (elemBottom > viewBottom) {
			view.scrollTop = elemBottom + (padding || 0) - viewHeight;
		}
		else if (elemTop < viewTop) {
			view.scrollTop = elemTop - (padding || 0);
		}
	}
});

'use strict';

angular.module('kifi.me', [])

.service('me', function () {
	return {
		replace: true,
		restrict: 'A',
		templateUrl: 'profileCard/profileCard.tpl.html',
		link: function (scope /*, element, attrs*/ ) {
			scope.firstName = 'Joon Ho';
			scope.lastName = 'Cho';
			scope.description = 'Porting to Angular.js';
		}
	};
});

'use strict';

angular.module('util', [])

.value('util', {
	startsWith: function (str, prefix) {
		return str === prefix || str.lastIndexOf(prefix, 0) === 0;
	},
	endsWith: function (str, suffix) {
		return str === suffix || str.indexOf(suffix, str.length - suffix.length) !== -1;
	}
});

'use strict';

angular.module('kifi.detail', ['kifi.keepService'])

.directive('kfDetail', [
	'keepService',
	function (keepService) {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/detail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.getLength = function () {
					return keepService.getSelectedLength();
				};

				scope.showSingleKeep = function () {
					return keepService.getPreviewed() && keepService.getSelectedLength() <= 1;
				};

				scope.getTitleText = function () {
					var len = keepService.getSelectedLength();
					if (len === 1) {
						return keepService.getFirstSelected().title;
					}
					return len + ' Keeps selected';
				};

				scope.getPreviewed = function () {
					return keepService.getPreviewed();
				};

				scope.getSelected = function () {
					return keepService.getSelected();
				};
			}
		};
	}
])

.directive('kfKeepDetail', [

	function () {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'detail/keepDetail.tpl.html',
			link: function (scope /*, element, attrs*/ ) {}
		};
	}
]);

'use strict';

angular.module('kifi.keep', [])

.controller('KeepCtrl', [
	'$scope',
	function () {}
])

.directive('kfKeep', [
	'$document',
	function ($document) {
		return {
			restrict: 'A',
			scope: true,
			controller: 'KeepCtrl',
			templateUrl: 'keep/keep.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.isMine = function () {
					return scope.keep.isMyBookmark || false;
				};

				scope.isPrivate = function () {
					return scope.keep.isPrivate || false;
				};

				function hasExampleTag(tags) {
					if (tags && tags.length) {
						for (var i = 0, l = tags.length; i < l; i++) {
							if (scope.isExampleTag(tags[i])) {
								return true;
							}
						}
					}
					return false;
				}

				scope.isExampleTag = function (tag) {
					return (tag && tag.name && tag.name.toLowerCase()) === 'example keep';
				};

				scope.getTags = function () {
					return scope.keep.tagList;
				};

				scope.isExample = function () {
					var keep = scope.keep;
					if (keep.isExample == null) {
						keep.isExample = hasExampleTag(scope.getTags());
					}
					return keep.isExample;
				};

				var aUrlParser = $document[0].createElement('a');
				var secLevDomainRe = /[^.\/]+(?:\.[^.\/]{1,3})?\.[^.\/]+$/;
				var fileNameRe = /[^\/]+?(?=(?:\.[a-zA-Z0-9]{1,6}|\/|)$)/;
				var fileNameToSpaceRe = /[\/._-]/g;

				function formatTitleFromUrl(url, matches) {
					aUrlParser.href = url;

					var domain = aUrlParser.hostname;
					var domainIdx = url.indexOf(domain);
					var domainMatch = domain.match(secLevDomainRe);
					if (domainMatch) {
						domainIdx += domainMatch.index;
						domain = domainMatch[0];
					}

					var fileName = aUrlParser.pathname;
					var fileNameIdx = url.indexOf(fileName, domainIdx + domain.length);
					var fileNameMatch = fileName.match(fileNameRe);
					if (fileNameMatch) {
						fileNameIdx += fileNameMatch.index;
						fileName = fileNameMatch[0];
					}
					fileName = fileName.replace(fileNameToSpaceRe, ' ').trimRight();

					for (var i = matches && matches.length; i--;) {
						var match = matches[i];
						var start = match[0],
							len = match[1];
						if (start >= fileNameIdx && start < fileNameIdx + fileName.length) {
							fileName = bolded(fileName, start - fileNameIdx, len);
						}
						else if (start >= domainIdx && start < domainIdx + domain.length) {
							domain = bolded(domain, start - domainIdx, len);
						}
					}
					fileName = fileName.trimLeft();

					return domain + (fileName ? ' · ' + fileName : '');
				}

				function bolded(text, start, len) {
					return text.substr(0, start) + '<b>' + text.substr(start, len) + '</b>' + text.substr(start + len);
				}

				function toTitleHtml(keep) {
					return keep.title || formatTitleFromUrl(keep.url);
				}

				var strippedSchemeRe = /^https?:\/\//;
				var domainTrailingSlashRe = /^([^\/]*)\/$/;

				function formatDesc(url, matches) {
					var strippedSchemeLen = (url.match(strippedSchemeRe) || [''])[0].length;
					url = url.substr(strippedSchemeLen).replace(domainTrailingSlashRe, '$1');
					for (var i = matches && matches.length; i--;) {
						matches[i][0] -= strippedSchemeLen;
					}
					return boldSearchTerms(url, matches);
				}

				function boldSearchTerms(text, matches) {
					for (var i = matches && matches.length; i--;) {
						var match = matches[i];
						var start = match[0];
						if (start >= 0) {
							text = bolded(text, start, match[1]);
						}
					}
					return text;
				}

				function updateTitleHtml() {
					scope.keep.titleHtml = toTitleHtml(scope.keep);
				}

				function updateDescHtml() {
					scope.keep.descHtml = formatDesc(scope.keep.url);
				}

				updateTitleHtml();
				updateDescHtml();

				scope.$watch('keep.title', function () {
					updateTitleHtml();
				});

				scope.$watch('keep.url', function () {
					updateTitleHtml();
					updateDescHtml();
				});

				scope.getTitle = function () {
					var keep = scope.keep;
					return keep.title || keep.url;
				};

				scope.getPicUrl = function (user) {
					return '//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName;
				};

				scope.getName = function (user) {
					return (user.firstName || '') + ' ' + (user.lastName || '');
				};

				scope.hasKeepers = function () {
					var keep = scope.keep;
					return !!(keep.keepers && keep.keepers.length);
				};

				scope.showOthers = function () {
					return !scope.hasKeepers() && !! scope.keep.others;
				};

				scope.getFriendText = function () {
					var keepers = scope.keep.keepers,
						len = keepers && keepers.length || 0;
					if (keepers.length === 1) {
						return '1 friend';
					}
					return len + ' friends';
				};

				scope.getOthersText = function () {
					var others = scope.keep.others || 0;
					if (others === 1) {
						return '1 other';
					}
					return others + ' others';
				};

				scope.isOnlyMine = function () {
					return !scope.hasKeepers() && !scope.keep.others;
				};

				scope.isSelected = function () {
					return scope.isSelectedKeep(scope.keep);
				};

				scope.isPreviewed = function () {
					return scope.isPreviewedKeep(scope.keep);
				};

				scope.onCheck = function (e) {
					// needed to prevent previewing
					e.stopPropagation();
					return scope.toggleSelectKeep(scope.keep);
				};
			}
		};
	}
]);

'use strict';

angular.module('kifi.keepService', [])

.factory('keepService', [
	'$http', 'env',
	function ($http, env) {

		var list = [],
			selected = {},
			before = null,
			previewed = null,
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

			getPreviewed: function () {
				return previewed || null;
			},

			isPreviewed: function (keep) {
				return !!previewed && previewed === keep;
			},

			preview: function (keep) {
				previewed = keep || null;
				return previewed;
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

'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
	'$scope', 'profileService', 'keepService', 'tagService', '$q',
	function ($scope, profileService, keepService, tagService, $q) {
		$scope.me = profileService.me;
		$scope.keeps = keepService.list;

		$scope.$watch('keeps', function () {
			$scope.refreshScroll();
		});

		var promise = keepService.getList();
		$q.all([promise, tagService.fetchAll()]).then(function () {
			keepService.joinTags(keepService.list, tagService.list);
		});

		$scope.previewing = null;

		$scope.$watch(function () {
			return keepService.previewing;
		}, function (val) {
			$scope.previewing = val;
		});

		$scope.selectKeep = function (keep) {
			return keepService.select(keep);
		};

		$scope.unselectKeep = function (keep) {
			return keepService.unselect(keep);
		};

		$scope.isSelectedKeep = function (keep) {
			return keepService.isSelected(keep);
		};

		$scope.toggleSelectKeep = function (keep) {
			return keepService.toggleSelect(keep);
		};

		$scope.toggleSelectAll = function () {
			return keepService.toggleSelectAll();
		};

		$scope.isSelectedAll = function () {
			return keepService.isSelectedAll();
		};

		$scope.isPreviewedKeep = function (keep) {
			return keepService.isPreviewed(keep);
		};

		$scope.togglePreviewKeep = function (keep) {
			return keepService.togglePreview(keep);
		};
	}
])

.directive('kfKeeps', [

	function () {
		return {
			restrict: 'A',
			scope: {},
			controller: 'KeepsCtrl',
			templateUrl: 'keeps/keeps.tpl.html',
			link: function (scope /*, element, attrs*/ ) {

				scope.page = {
					title: 'Browse your Keeps'
				};

				scope.results = {
					numShown: 0,
					myTotal: 300,
					friendsTotal: 0,
					othersTotal: 12342
				};

				scope.filter = {
					type: 'm'
				};

				scope.selectedKeep = null;

				scope.checkEnabled = true;

				scope.getSubtitle = function () {
					var subtitle = scope.subtitle;
					var numShown = scope.results.numShown;
					switch (subtitle.type) {
					case 'query':
						switch (numShown) {
						case 0:
							return 'Sorry, no results found for &#x201c;' + (scope.results.query || '') + '&#x202c;';
						case 1:
							return '1 result found';
						}
						return 'Top ' + numShown + ' results';
					case 'tag':
						switch (numShown) {
						case 0:
							return 'No Keeps in this tag';
						case 1:
							return 'Showing the only Keep in this tag';
						case 2:
							return 'Showing both Keeps in this tag';
						}
						if (numShown === scope.results.numTotal) {
							return 'Showing all ' + numShown + ' Keeps in this tag';
						}
						return 'Showing the ' + numShown + ' latest Keeps in this tag';
					case 'keeps':
						switch (numShown) {
						case 0:
							return 'You have no Keeps';
						case 1:
							return 'Showing your only Keep';
						case 2:
							return 'Showing both of your Keeps';
						}
						if (numShown === scope.results.numTotal) {
							return 'Showing all ' + numShown + ' of your Keeps';
						}
						return 'Showing your ' + numShown + ' latest Keeps';
					}
					return subtitle.text;
				};

				scope.setSearching = function () {
					scope.subtitle = {
						text: 'Searching...'
					};
				};

				scope.setLoading = function () {
					scope.subtitle = {
						text: 'Loading...'
					};
				};

				scope.isFilterSelected = function (type) {
					return scope.filter.type === type;
				};

				function getFilterCount(type) {
					switch (type) {
					case 'm':
						return scope.results.myTotal;
					case 'f':
						return scope.results.friendsTotal;
					case 'a':
						return scope.results.othersTotal;
					}
				}

				scope.isEnabled = function (type) {
					if (scope.isFilterSelected(type)) {
						return false;
					}
					return !!getFilterCount(type);
				};

				scope.getFilterUrl = function (type) {
					if (scope.isEnabled(type)) {
						var count = getFilterCount(type);
						if (count) {
							return '/find?q=' + (scope.results.query || '') + '&f=' + type + '&maxHits=30';
						}
					}
					return '';
				};

				scope.setLoading();

				scope.preview = function (keep, $event) {
					if ($event.target.tagName !== 'A') {
						scope.togglePreviewKeep(keep);
					}
				};
			}
		};
	}
]);

'use strict';

angular.module('kifi.layout.leftCol', [])

.controller('LeftColCtrl', [
	'$scope',
	function ($scope) {
		console.log('left');
	}
]);


'use strict';

angular.module('kifi.layout.main', [])

.controller('MainCtrl', [
	'$scope',
	function ($scope) {
		var KEY_ESC = 27;

		$scope.search = {};

		$scope.isEmpty = function () {
			return !$scope.search.text;
		};

		$scope.onKeydown = function (e) {
			if (e.keyCode === KEY_ESC) {
				$scope.clear();
			}
		};

		$scope.onFocus = function () {
			$scope.focus = true;
		};

		$scope.onBlur = function () {
			$scope.focus = false;
		};

		$scope.clear = function () {
			$scope.search.text = '';
		};

		$scope.undoAction = {
			message: 'hi'
		};

		$scope.undo = function () {
			$scope.undoAction = null;
		};
	}
]);

'use strict';

angular.module('kifi.layout.nav', ['util'])

.directive('kfNav', [
	'$location', 'util',
	function ($location, util) {
		return {
			//replace: true,
			restrict: 'A',
			templateUrl: 'layout/nav/nav.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.counts = {
					keepCount: 483,
					friendsNotiConut: 18
				};

				scope.isActive = function (path) {
					var loc = $location.path();
					return loc === path || util.startsWith(loc, path + '/');
				};
			}
		};
	}
]);

'use strict';

angular.module('kifi.layout.rightCol', [])

.controller('RightColCtrl', [
	'$scope',
	function ($scope) {
		console.log('right');
	}
]);


'use strict';

angular.module('kifi.profileCard', ['kifi.profileService'])

.directive('kfProfileCard', [
	'profileService',
	function (profileService) {
		return {
			replace: true,
			restrict: 'A',
			templateUrl: 'profileCard/profileCard.tpl.html',
			link: function (scope /*, element, attrs*/ ) {
				scope.me = profileService.me;
				profileService.fetchMe();
			}
		};
	}
]);

'use strict';

angular.module('kifi.profileService', [])

.factory('profileService', [
	'$http', 'env',
	function ($http, env) {
		var me = {};

		function formatPicUrl(userId, pictureName, size) {
			return env.picBase + '/users/' + userId + '/pics/' + (size || 200) + '/' + pictureName;
		}

		return {
			me: me,

			fetchMe: function () {
				var url = env.xhrBase + '/user/me';
				return $http.get(url).then(function (res) {
					angular.forEach(res.data, function (val, key) {
						me[key] = val;
					});
					me.picUrl = formatPicUrl(me.id, me.pictureName);
					return me;
				});
			}

		};
	}
]);

'use strict';

angular.module('kifi.tagService', [])

.factory('tagService', [
	'$http', 'env', '$q',
	function ($http, env, $q) {
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
					var index = indexById(id);
					if (index !== -1) {
						list.splice(index, 1);
					}
				}

				if (env.dev) {
					var deferred = $q.defer();
					removeTag(tagId);
					deferred.resolve(tagId);
					return deferred.promise;
				}

				var url = env.xhrBase + '/collections/' + tagId + '/delete';
				return $http.post(url).then(function () {
					removeTag(tagId);
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

				if (env.dev) {
					var deferred = $q.defer();
					deferred.resolve(renameTag(tagId, name));
					return deferred.promise;
				}

				var url = env.xhrBase + '/collections/' + tagId + '/update';
				return $http.post(url, {
					name: name
				}).then(function (res) {
					var tag = res.data;
					return renameTag(tag.id, tag.name);
				});
			}
		};
	}
]);

'use strict';

angular.module('kifi.tags', ['util', 'dom', 'kifi.tagService'])

.controller('TagCtrl', [
	'$scope', '$timeout', 'tagService',
	function ($scope, $timeout, tagService) {
		$scope.create = function (name) {
			if (name) {
				return tagService.create(name)
					.then(function (tag) {
						tag.isNew = true;
						$scope.clearFilter();

						$timeout(function () {
							delete tag.isNew;
						}, 3000);

						return tag;
					});
			}
		};

		$scope.rename = function (tag) {
			if (tag) {
				$scope.lastHighlight = $scope.highlight;
				$scope.renameTag = {
					value: tag.name
				};
				$scope.renaming = tag;
			}
		};

		$scope.remove = function (tag) {
			if (tag && tag.id) {
				return tagService.remove(tag.id);
			}
		};

		$scope.fetchNext = function () {
			console.log('hi');
		};
	}
])

.directive('kfTags', [
	'$timeout', '$location', 'util', 'dom', 'tagService',
	function ($timeout, $location, util, dom, tagService) {
		var KEY_UP = 38,
			KEY_DOWN = 40,
			KEY_ENTER = 13,
			KEY_ESC = 27,
			//KEY_TAB = 9,
			KEY_DEL = 46,
			KEY_F2 = 113;

		return {
			restrict: 'A',
			templateUrl: 'tags/tags.tpl.html',
			scope: {},
			controller: 'TagCtrl',
			link: function (scope, element /*, attrs*/ ) {
				scope.tags = tagService.list;

				scope.clearFilter = function (focus) {
					scope.filter.name = '';
					if (focus) {
						scope.focusFilter = true;
					}
				};

				scope.isRenaming = function (tag) {
					return scope.renaming === tag;
				};

				scope.onRenameKeydown = function (e) {
					switch (e.keyCode) {
					case KEY_ENTER:
						scope.submitRename();
						break;
					case KEY_ESC:
						scope.cancelRename();
						break;
					}
				};

				function rehighlight() {
					if (scope.lastHighlight && !scope.highlight) {
						scope.highlight = scope.lastHighlight;
					}
					scope.lastHighlight = null;
				}

				scope.submitRename = function () {
					// different scope
					var newName = scope.renameTag.value,
						tag = scope.renaming;
					if (newName && newName !== tag.name) {
						return tagService.rename(tag.id, newName).then(function (tag) {
							scope.cancelRename();
							return tag;
						});
					}
					return scope.cancelRename();
				};

				scope.cancelRename = function () {
					scope.renaming = null;
					scope.focusFilter = true;
					rehighlight();
				};

				function getFilterValue() {
					return scope.filter && scope.filter.name || '';
				}

				scope.showAddTag = function () {
					var name = getFilterValue(),
						res = false;
					if (name) {
						name = name.toLowerCase();
						res = !scope.tags.some(function (tag) {
							return tag.name.toLowerCase() === name;
						});
					}
					scope.isAddTagShown = res;
					return res;
				};

				scope.isActiveTag = function (tag) {
					return util.startsWith($location.path(), '/tag/' + tag.id);
				};

				scope.getShownTags = function () {
					var child = scope.$$childHead;
					while (child) {
						if (child.shownTags) {
							return child.shownTags;
						}
						child = child.$$nextSibling;
					}
					return scope.tags || [];
				};

				function indexOfTag(tag) {
					if (tag) {
						return scope.getShownTags().indexOf(tag);
					}
					return -1;
				}

				scope.viewTag = function (tag) {
					if (tag) {
						return $location.path('/tag/' + tag.id);
					}
				};

				scope.select = function () {
					if (scope.highlight) {
						return scope.viewTag(scope.highlight);
					}
					return scope.create(getFilterValue());
				};

				scope.onKeydown = function (e) {
					switch (e.keyCode) {
					case KEY_UP:
						scope.highlightPrev();
						break;
					case KEY_DOWN:
						scope.highlightNext();
						break;
					case KEY_ENTER:
						scope.select();
						break;
					case KEY_ESC:
						if (scope.highlight) {
							scope.dehighlight();
						}
						else {
							scope.clearFilter();
						}
						break;
					case KEY_DEL:
						scope.remove(scope.highlight);
						break;
					case KEY_F2:
						scope.rename(scope.highlight);
						break;
					}
				};

				scope.refreshHighlight = function () {
					var shownTags = scope.getShownTags();
					var highlight = scope.highlight;
					if (highlight) {
						var index = shownTags.indexOf(highlight);
						if (index !== -1) {
							// might scroll
							return scope.highlightAt(index);
						}
					}

					if (getFilterValue() && shownTags.length) {
						return scope.highlightFirst();
					}

					return scope.dehighlight();
				};

				scope.isHighlight = function (tag) {
					return scope.highlight === tag;
				};

				scope.isHighlightNew = function () {
					return !scope.highlight && !! getFilterValue();
				};

				scope.dehighlight = function () {
					scope.highlight = null;
					if (scope.isAddTagShown) {
						dom.scrollIntoViewLazy(element.find('.kf-tag-new')[0]);
					}
					return null;
				};

				scope.highlightAt = function (index) {
					if (index == null) {
						return scope.dehighlight();
					}

					var tags = scope.getShownTags(),
						len = tags.length;
					if (!len) {
						return scope.dehighlight();
					}

					index = ((index % len) + len) % len;
					var tag = tags[index];
					scope.highlight = tag;
					dom.scrollIntoViewLazy(element.find('.kf-tag')[index]);
					return tag;
				};

				scope.highlightFirst = function () {
					return scope.highlightAt(0);
				};

				scope.highlightLast = function () {
					return scope.highlightAt(-1);
				};

				scope.highlightNext = function () {
					if (scope.isHighlightNew()) {
						// new tag is highlighted
						// highlight the first
						return scope.highlightFirst();
					}

					var index = indexOfTag(scope.highlight);
					if (index === -1) {
						// no highlight
						// highlight the first
						return scope.highlightFirst();
					}

					if (index === scope.getShownTags().length - 1) {
						// last item on the list

						if (scope.isAddTagShown) {
							// highlight the new tag if available
							return scope.dehighlight();
						}

						// the first, otherwise
						return scope.highlightFirst();
					}

					// highlight the next item
					return scope.highlightAt(index + 1);
				};

				scope.highlightPrev = function () {
					if (scope.isHighlightNew()) {
						// new tag is highlighted
						// highlight the last
						return scope.highlightLast();
					}

					var index = indexOfTag(scope.highlight);
					if (index === -1) {
						// no highlight
						// highlight the last
						return scope.highlightLast();
					}

					if (index === 0) {
						// first item on the list

						if (scope.isAddTagShown) {
							// highlight the new tag if available
							return scope.dehighlight();
						}

						// the last, otherwise
						return scope.highlightLast();
					}

					// highlight the prev item
					return scope.highlightAt(index - 1);
				};

				var list = element.find('.kf-tag-list');
				list.css({
					position: 'absolute',
					top: list.position().top,
					bottom: 0
				});

				scope.$watch('filter.name', function () {
					$timeout(scope.refreshHighlight);
					scope.refreshScroll();
				});
				scope.$watch('tags', function () {
					scope.refreshScroll();
				});

				tagService.fetchAll();
			}
		};
	}
]);

angular.module('kifi.templates', ['detail/detail.tpl.html', 'detail/keepDetail.tpl.html', 'keep/keep.tpl.html', 'keeps/keeps.tpl.html', 'layout/footer/footer.tpl.html', 'layout/leftCol/leftCol.tpl.html', 'layout/main/main.tpl.html', 'layout/nav/nav.tpl.html', 'layout/rightCol/rightCol.tpl.html', 'profileCard/profileCard.tpl.html', 'tags/tags.tpl.html']);

angular.module('detail/detail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/detail.tpl.html',
    '<div class="kf-detail-pane" ng-show="getPreviewed()"><div class="kf-detail-scroll" antiscroll="{ autoHide: false }"><div class="kf-detail-inner {{howKept}}"><a class="kf-detail-x" href="javascript:"></a><div class="kf-page-meta" data-n="{{getLength()}}"><h2 class="kf-detail-header page-title" ng-bind="getTitleText()"></h2><div kf-keep-detail="" ng-show="showSingleKeep()"></div><a class="kf-page-keep" href="javascript:"></a> <a class="kf-page-priv" href="javascript:"></a></div><div ng-show="showSingleKeep()"><div class="kf-page-who"><h2 class="kf-detail-header">Who kept this:</h2><div class="kf-page-who-pics"></div><div class="kf-page-who-text"></div></div><div class="kf-page-chatter"><h2 class="kf-detail-header">Talking about this Keep:</h2><a class="kf-page-chatter-messages" href="{{url}}" target="_blank" data-n="0" data-locator="/messages">Private Conversation</a></div></div><div class="page-colls" ng-class="{ some: collections.length }"><h2 class="kf-detail-header">Tags:</h2><ul class="page-coll-list"><li class="page-coll" data-template="" data-id="{{id}}"><a class="page-coll-a long-text" href="tag/{{id}}">{{name}}</a> <a class="page-coll-x" href="javascript:"></a></li><li class="page-coll-new" data-after-template=""><a class="page-coll-add" href="javascript:"></a> <span class="page-coll-sizer" style="display:none"></span><input class="page-coll-input" style="display:none" type="text" placeholder="tag name"><ul class="page-coll-opts" style="display:none"><li class="page-coll-opt long-text" data-template="" data-id="{{id}}">{{name}}</li></ul></li></ul></div></div></div></div>');
}]);

angular.module('detail/keepDetail.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('detail/keepDetail.tpl.html',
    '<div class="kf-keep-detail"><a class="kf-keep-detail-url long-text" ng-href="{{keep.url}}" target="_blank">{{keep.url}}</a><div class="kf-page-pic-wrap"><div class="kf-page-pic-special"></div><a class="kf-page-pic" ng-href="{{keep.url}}" target="_blank"><div class="kf-page-pic-1"><div class="kf-page-pic-2"><div class="kf-page-pic-3"><div class="kf-page-pic-soon">Preview of this page<br>not yet available</div><span class="kf-page-pic-tip">Visit page</span></div></div></div></a><div class="kf-page-how {{keep.howKept}}"><div class="kf-page-how-0"><div class="kf-page-how-pub"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Public</div></div></div></div><div class="kf-page-how-pri"><div class="kf-page-how-1"><div class="kf-page-how-2"><div class="kf-page-how-3">Private</div></div></div></div></div></div></div></div>');
}]);

angular.module('keep/keep.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keep/keep.tpl.html',
    '<li class="kf-keep" ng-class="{ mine: isMine(), example: isExample(), private: isPrivate(), detailed: isPreviewed(), selected: isSelected() }"><time class="kf-keep-time" ng-attr-datetime="{{keep.createdAt}}" ng-show="keep.createdAt" am-time-ago="keep.createdAt"></time><div class="kf-keep-handle"><div class="kf-keep-checkbox" ng-click="onCheck($event)"></div></div><div class="kf-keep-title"><span class="kf-example-tag">Example keep</span> <a class="kf-keep-title-link" ng-href="{{keep.url}}" target="_blank" ng-attr-title="{{getTitle()}}" ng-bind-html="keep.titleHtml"></a></div><div class="kf-keep-url" ng-attr-title="{{keep.url}}" ng-bind-html="keep.descHtml"></div><div class="kf-keep-tags"><span class="kf-keep-tag" ng-class="{ example: isExampleTag(tag) }" ng-repeat="tag in getTags()"><a class="kf-keep-tag-link" ng-href="/tag/{{tag.id}}">{{tag.name}}</a></span></div><div class="kf-keep-who"><a class="kf-keep-who-pic me" title="You! You look great!" ng-attr-style="background-image: url({{getPicUrl(me)}})"></a> <a class="kf-keep-who-pic" href="javascript:" data-id="{{id}}" data-name="{{getName(keeper)}}" ng-attr-style="background-image: url({{getPicUrl(keeper)}})" ng-repeat="keeper in keep.keepers | limitTo: 9"></a> <span class="kf-keep-who-others" ng-if="showOthers()"></span> <span class="kf-keep-who-text"><span class="kf-keep-you">You <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-if="hasKeepers()">Private</span></span> <span class="kf-keep-friends" ng-if="hasKeepers()">{{getFriendText()}}</span> <span class="kf-keep-others" ng-if="hasOthers()">{{getOthersText()}}</span> <span class="kf-keep-kept-this">kept this</span> <span class="kf-keep-private" ng-class="{ on: isPrivate() }" ng-if="isOnlyMine()">Private</span></span></div><div class="kf-keep-arrow-1"><div class="kf-keep-arrow-2"><div class="kf-keep-arrow-3"><div class="kf-keep-arrow-4"></div></div></div></div><div class="kf-keep-hover-button-wrapper"><div class="kf-keep-hover-button-table"><div class="kf-keep-hover-button-cell"><div class="kf-keep-hover-button"><span class="kf-keep-button-preview">Preview</span> <span class="kf-keep-button-close">Close</span></div></div></div></div></li>');
}]);

angular.module('keeps/keeps.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('keeps/keeps.tpl.html',
    '<div><div class="kf-main-head"><h1 class="kf-main-title">{{page.title}}</h1><div class="kf-subtitle"><span class="kf-check-all" ng-class="{ enabled: checkEnabled, checked: isSelectedAll() }" ng-click="toggleSelectAll()"></span> <span class="kf-subtitle-text">{{getSubtitle()}}</span> <span class="kf-search-filters" ng-if="subtitle.type === \'query\' || true"><a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'m\'), enabled: isEnabled(\'m\') }" ng-href="{{getFilterUrl(\'m\')}}">You ({{results.myTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'f\'), enabled: isEnabled(\'f\') }" ng-href="{{getFilterUrl(\'f\')}}">Friends ({{results.friendsTotal}})</a> <a class="kf-search-filter" ng-class="{ selected: isFilterSelected(\'a\'), enabled: isEnabled(\'a\') }" ng-href="{{getFilterUrl(\'a\')}}">All ({{results.myTotal + results.friendsTotal + results.othersTotal}})</a></span></div></div><div class="kf-main-keeps" antiscroll="{ autoHide: false }"><ol id="kf-search-results"></ol><ol id="kf-my-keeps"><div kf-keep="" ng-repeat="keep in keeps" ng-click="preview(keep, $event)"></div></ol><div class="kf-keep-group-title-fixed"></div><img class="kf-keeps-loading" src="/assets/img/wait.gif"> <a class="kf-keeps-load-more hidden" href="javascript:">Show more</a></div></div>');
}]);

angular.module('layout/footer/footer.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/footer/footer.tpl.html',
    '<div class="kf-footer" antiscroll="{ autoHide: false }"><h2 class="kf-footer-header">Getting Started with kifi</h2><ul class="kf-footer-list about-kifi"><li><a class="kf-footer-item-link kifi-tutorial" href="#"><span class="kf-footer-item-icon kf-footer-item-icon-tutorial"></span> Quick guide to get started</a></li><li class="install-kifi"><a class="kf-footer-item-link" href="/install"><span class="kf-footer-item-icon kf-footer-item-icon-install"></span> Install the extension</a></li><li><a class="kf-footer-item-link" href="friends/invite"><span class="kf-footer-item-icon kf-footer-item-icon-friends"></span> Find friends</a></li></ul><h2 class="kf-footer-header">kifi Support and Updates</h2><ul class="kf-footer-list about-us"><li><a class="kf-footer-item-link support-center" href="http://support.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-support"></span> Support center</a></li><li><a class="kf-footer-item-link contact-us" href="http://support.kifi.com/customer/portal/emails/new"><span class="kf-footer-item-icon kf-footer-item-icon-contact"></span> Contact us</a></li><li><a class="kf-footer-item-link updates-features" href="http://blog.kifi.com"><span class="kf-footer-item-icon kf-footer-item-icon-blog"></span> kifi blog</a></li></ul><div class="kf-footer-more"><a href="/privacy">Privacy</a> <span class="kf-footer-sep">|</span> <a href="/terms">Terms</a> <span class="kf-footer-sep">|</span> <a href="/about">About kifi</a> <span class="kf-footer-sep">|</span> <a href="http://www.42go.com">About FortyTwo</a></div></div>');
}]);

angular.module('layout/leftCol/leftCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/leftCol/leftCol.tpl.html',
    '<div class="kf-col-inner" ng-controller="LeftColCtrl"><div class="kf-header-left"><a class="kf-header-logo" href="/"></a></div><div kf-profile-card=""></div><div kf-nav=""></div><div kf-tags=""></div></div>');
}]);

angular.module('layout/main/main.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/main/main.tpl.html',
    '<div class="kf-col-inner" ng-controller="MainCtrl"><div class="kf-query-wrap" ng-class="{ empty: isEmpty(), focus: focus }"><input class="kf-query" type="text" placeholder="Find anything..." ng-model="search.text" ng-keydown="onKeydown($event)" ng-focus="onFocus()" ng-blur="onBlur()"><span class="kf-query-icon"><b class="kf-query-mag"></b> <a class="kf-query-x" href="javascript:" ng-click="clear()"></a></span><div class="kf-undo" ng-if="undoAction"><span class="kf-undo-box"><span class="kf-undo-message">{{undoAction.message}}</span> <a class="kf-undo-link" href="javascript:" ng-click="undo()">Undo</a> <span></span></span></div></div><div kf-keeps=""></div></div>');
}]);

angular.module('layout/nav/nav.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/nav/nav.tpl.html',
    '<div class="kf-nav-group-name">You</div><ul class="kf-nav-group"><li class="kf-nav-item kf-nav-keeps" ng-class="{ active: isActive(\'/\') }"><a class="kf-nav-link" href="/"><span class="kf-nav-name">Your Keeps</span> <span class="kf-nav-count" ng-class="{ empty: !counts.keepCount }">{{counts.keepCount}}</span></a></li><li class="kf-nav-item kf-nav-friends" ng-class="{ active: isActive(\'/friends\') }"><a class="kf-nav-link" href="/friends"><span class="kf-nav-name">Your Friends</span> <span class="kf-nav-count" ng-class="{ empty: !counts.friendsNotiConut }">{{counts.friendsNotiConut}}</span></a></li></ul>');
}]);

angular.module('layout/rightCol/rightCol.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('layout/rightCol/rightCol.tpl.html',
    '<div class="kf-col-inner" ng-controller="RightColCtrl"><nav class="kf-top-right-nav"><a href="/logout">Log out</a></nav><div ng-include="\'layout/footer/footer.tpl.html\'"></div><div kf-detail=""></div></div>');
}]);

angular.module('profileCard/profileCard.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('profileCard/profileCard.tpl.html',
    '<div class="kf-my-identity"><a class="kf-my-settings-wrapper" href="/profile"><span class="kf-my-pic" style="background-image: url({{me.picUrl}})"></span> <span class="kf-my-settings-shade"></span> <span class="kf-my-settings"><span class="kf-my-settings-icon"></span> <span class="kf-my-settings-text">Settings</span></span></a><div class="kf-my-name">{{me.firstName}} {{me.lastName}}</div><div class="kf-my-description">{{me.description}}</div></div>');
}]);

angular.module('tags/tags.tpl.html', []).run(['$templateCache', function($templateCache) {
  'use strict';
  $templateCache.put('tags/tags.tpl.html',
    '<div class="kf-tags"><div class="kf-nav-group-name">Tags</div><label class="kf-nav-item kf-tag-input-box"><input name="filterName" placeholder="Find or add a tag..." ng-model="filter.name" focus-when="focusFilter" ng-keydown="onKeydown($event)" ng-blur="dehighlight()"><a class="kf-tag-input-clear" href="javascript:" ng-click="clearFilter(true)" ng-show="filter.name">&times;</a></label></div><ul class="kf-tag-list" antiscroll="{ autoHide: false }" anti-infinite-scroll="fetchNext()"><li class="kf-nav-item kf-tag" ng-class="{ active: isActiveTag(tag), highlight: isHighlight(tag), new: tag.isNew, renaming: isRenaming(tag) }" ng-repeat="tag in shownTags = (tags | filter: filter)"><a class="kf-nav-link" ng-href="/tag/{{tag.id}}"><span class="kf-tag-icon"></span> <span class="kf-tag-name">{{tag.name}}</span> <span class="kf-tag-count">{{tag.keeps}}</span></a><input class="kf-tag-rename" type="text" placeholder="Type new tag name" ng-model="renameTag.value" ng-keydown="onRenameKeydown($event)" ng-if="isRenaming(tag)" autofocus><div class="dropdown"><a class="dropdown-toggle"></a><ul class="dropdown-menu"><li><a href="javascript:" ng-click="rename(tag)">Rename</a></li><li><a href="javascript:" ng-click="remove(tag)">Remove</a></li></ul></div></li><li class="kf-nav-item kf-tag kf-tag-new" ng-class="{ highlight: isHighlightNew() }" ng-show="showAddTag()"><a href="javascript:" ng-click="create(filter.name)"><span class="kf-tag-icon kf-tag-icon-create"></span> <span class="kf-tag-caption">Add a new tag:</span> <span class="kf-tag-name">{{filter.name}}</span></a></li></ul>');
}]);
