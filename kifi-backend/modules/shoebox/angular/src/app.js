'use strict';

angular.module('kifi', [
	'ngCookies',
	'ngResource',
	'ngRoute',
	'ui.bootstrap',
	'antiscroll',
	'kifi.templates',
	'kifi.profileCard',
	'kifi.tags',
	'kifi.layout.leftCol',
	'kifi.layout.main',
	'kifi.layout.nav',
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
