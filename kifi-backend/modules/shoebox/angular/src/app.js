'use strict';

angular.module('kifi', [
  'ngCookies',
  'ngResource',
  'ngRoute',
  'ngSanitize',
  'ngAnimate',
  'ui.bootstrap',
  //'ui.router',
  'util',
  'dom',
  'antiscroll',
  'jun.smartScroll',
  'angularMoment',
  'kifi.home',
  'kifi.search',
  'kifi.focus',
  'kifi.youtube',
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

.run(['$route', angular.noop])

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
