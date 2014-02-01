'use strict';

angular.module('kifi.keeps', ['util', 'dom', 'kifi.keepService'])

.controller('KeepCtrl', [
	'$scope', '$timeout', 'keepService',
	function ($scope, $timeout, keepService) {}
])

.directive('kfKeeps', [
	'$timeout', '$location', 'util', 'dom', 'keepService',
	function ($timeout, $location, util, dom, keepService) {
		return {
			restrict: 'A',
			scope: {},
			controller: 'KeepCtrl',
			templateUrl: 'keeps/keeps.tpl.html',
			link: function (scope, element, attrs) {
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

				scope.isSelected = function (type) {
					return scope.filter.type === type;
				};

				scope.getFilterUrl = function (type) {
					var count;
					switch (type) {
					case 'm':
						count = scope.results.myTotal;
						break;
					case 'f':
						count = scope.results.friendsTotal;
						break;
					case 'a':
						count = scope.results.othersTotal;
						break;
					}

					if (count) {
						return '/find?q=' + (scope.results.query || '') + '&f=' + type + '&maxHits=30';
					}
					return '';
				};

				scope.toggleCheck = function () {
					scope.checked = !scope.checked;
				};

				scope.setLoading();
			}
		};
	}
]);
