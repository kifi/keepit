'use strict';

angular.module('kifi', [
	'ngCookies',
	'ngResource',
	'ngRoute'
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
