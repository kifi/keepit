'use strict';

angular.module('kifi.invite', [
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'jun.facebook',
  'kifi.inviteService',
  'kifi.social',
  'kifi.modal'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/invite', {
      templateUrl: 'invite/invite.tpl.html',
      resolve: {
        'wtiList': ['wtiService', function (wtiService) {
          return wtiService.loadInitial().then(function (res) {
            return res;
          });
        }]
      }
    }).when('/friends/invite', {
      redirectTo: '/invite'
    });
  }
])

.controller('InviteCtrl', [
  '$scope', '$http', 'profileService', 'routeService', '$window', 'wtiService', 'socialService',
  function ($scope, $http, profileService, routeService, $window, wtiService, socialService) {
    $window.document.title = 'Kifi • Invite your friends';

    $scope.$watch(socialService.checkIfRefreshingSocialGraph, function (v) {
      $scope.isRefreshingSocialGraph = v;
    });

    socialService.checkIfUpdatingGraphs(2);

    $scope.whoToInvite = wtiService.list;

    $scope.wtiScrollDistance = '100%';
    $scope.isWTIScrollDisabled = function () {
      return !wtiService.hasMore();
    };
    $scope.wtiScrollNext = wtiService.getMore;

  }
])

.directive('kfSocialInviteWell', [
  'socialService',
  function (socialService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteWell.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networks = socialService.networks;

        scope.data = scope.data || {};

        socialService.refresh();
      }
    };
  }
])

.directive('kfSocialInviteSearch', [
  'inviteService', '$document', '$log', '$timeout',
  function (inviteService, $document, $log, $timeout) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteSearch.tpl.html',
      link: function (scope, element/*, attrs*/) {
        scope.search = {};
        scope.search.showDropdown = false;
        scope.data = scope.data || {};

        scope.results = [];
        scope.selected = inviteService.socialSelected;

        scope.change = _.debounce(function () { // todo: integrate service-wide debounce into Clutch, remove me
          inviteService.socialSearch(scope.search.name).then(function (res) {

            var set = _.clone(res);

            var socialConns = _.filter(res, function (result) {
              return result.network && result.network.indexOf('fortytwo') === -1;
            }).length;

            if (scope.search.name.length > 2 && (res.length < 3 || socialConns < 3)) {
              set.push({
                custom: 'cant_find'
              });
            }

            scope.results = set;

            if (!set || set.length === 0) {
              scope.search.showDropdown = false;
            } else {
              scope.search.showDropdown = true;
            }
          });
        }, 200);

        function clickOutside(e) {
          if (scope.search.showDropdown && !element.find(e.target)[0]) { // click was outside of dropdown
            scope.$apply(function () {
              scope.search.showDropdown = false;
            });
          }
        }

        scope.invite = function (result, $event) {
          $log.log('this person:', result);
          var $elem = angular.element($event.target);
          $elem.text('Sending');
          $elem.parent().removeClass('clickable');
          inviteService.invite(result.networkType, result.socialId).then(function () {
            $elem.text('Sent!');
            $elem.off('click');
            $timeout(function () {
              $elem.parent().fadeOut('fast');
            }, 2000);
            inviteService.expireSocialSearch();
          }, function (err) {
            $log.log('err:', err, result);
            $elem.text('Error. Retry?');
            $elem.parent().addClass('clickable');
            inviteService.expireSocialSearch();
          });
        };

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

      }
    };
  }
]);
