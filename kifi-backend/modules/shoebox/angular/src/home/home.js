'use strict';

angular.module('kifi.home', ['util', 'kifi.keepService'])

.config([
	'$routeProvider',
	function ($routeProvider) {
	}
])

.controller('HomeCtrl', [
	'$scope', 'keepService',
	function ($scope, keepService) {
		console.log('home ctrl', keepService);
	}
]);
