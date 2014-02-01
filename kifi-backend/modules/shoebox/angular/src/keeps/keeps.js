'use strict';

angular.module('kifi.keeps', ['util', 'dom', 'kifi.keepService'])

.controller('KeepCtrl', [
	'$scope', '$timeout', 'keepService',
	function ($scope, $timeout, keepService) {
	}
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
			}
		};
	}
]);
