'use strict';

angular.module('kifi.keeps', ['kifi.profileService', 'kifi.keepService', 'kifi.tagService'])

.controller('KeepsCtrl', [
	'$scope', 'profileService', 'keepService', 'tagService', '$q',
	function ($scope, profileService, keepService, tagService, $q) {
		$scope.me = profileService.me;
		$scope.keeps = keepService.list;

		$scope.$watch('keeps.length', function () {
			$scope.refreshScroll();
		});

		$scope.loadingKeeps = true;
		var promise = keepService.getList().then(function (list) {
			$scope.loadingKeeps = false;
			return list;
		});

		$q.all([promise, tagService.fetchAll()]).then(function () {
			$scope.loadingKeeps = false;
			$scope.refreshScroll();
			keepService.joinTags(keepService.list, tagService.list);
		});

		$scope.getNextKeeps = function () {
			if ($scope.loadingKeeps) {
				return $q.when([]);
			}

			$scope.loadingKeeps = true;

			return keepService.getList().then(function (list) {
				$scope.loadingKeeps = false;
				$scope.refreshScroll();
				return list;
			});
		};

		$scope.selectKeep = keepService.select;
		$scope.unselectKeep = keepService.unselect;
		$scope.isSelectedKeep = keepService.isSelected;
		$scope.toggleSelectKeep = keepService.toggleSelect;

		$scope.toggleSelectAll = keepService.toggleSelectAll;
		$scope.isSelectedAll = keepService.isSelectedAll;

		$scope.isPreviewedKeep = keepService.isPreviewed;
		$scope.togglePreviewKeep = keepService.togglePreview;
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

				scope.onScrollNext = function () {
					scope.getNextKeeps();
				};

				scope.isScrollDisabled = function () {
					return scope.loadingKeeps;
				};

				scope.scrollDistance = '100%';
			}
		};
	}
]);
