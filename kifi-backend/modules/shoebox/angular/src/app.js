'use strict';

angular.module('kifi', [
	'ngCookies',
	'ngResource',
	'ngRoute',
	'kifi.templates',
	'kifi.layout.leftCol',
	'kifi.layout.main',
	'kifi.layout.rightCol'
])

.config([
	'$routeProvider', '$locationProvider',
	function ($routeProvider, $locationProvider) {
		$locationProvider
		.html5Mode(true)
		.hashPrefix('!');

		$routeProvider.otherwise({
			redirectTo: '/'
		});
	}
])

.controller('AppCtrl', [
	function () {
	}
]);
