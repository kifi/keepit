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
      controller: 'InviteCtrl'
    }).when('/friends/invite', {
      redirectTo: '/invite'
    });
  }
])

.controller('InviteCtrl', [
  '$scope', '$http', 'profileService', 'routeService', '$window', 'wtiService',
  function ($scope, $http, profileService, routeService, $window, wtiService) {
    $window.document.title = 'Kifi • Invite your friends';


    wtiService.loadInitial();
    $scope.whoToInvite = wtiService.list;

    $scope.wtiScrollDistance = '100%';
    $scope.isWTIScrollDisabled = function () {
      return !wtiService.hasMore();
    };
    $scope.wtiScrollNext = wtiService.getMore;


    $scope.invite = function (friend) {
      // `value` will let you decide what platform the user is coming from. Perhaps better to let inviteService decide?
      // is 'friend' overloaded naming wise? is socialFriend better?
      $window.alert('Inviting ' + friend);
    };

  }
])

.directive('kfSocialInviteWell', [
  'socialService', '$rootScope',
  function (socialService, $rootScope) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteWell.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.networks = socialService.networks;

        scope.data = scope.data || {};

        scope.showAddNetworks = function () {
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };

        socialService.refresh();
      }
    };
  }
])

.directive('kfSocialInviteSearch', [
  'inviteService', '$document', '$log', 'socialService',
  function (inviteService, $document, $log, $socialService) {
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
          inviteService.invite(result.networkType, result.socialId).then(function (res) {
            $elem.text('Sent!');
            inviteService.expireSocialSearch();
          }, function (err) {
            $log.log('err:', err, result);
            $elem.text('Error. Retry?');
          });
        };

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

        scope.refreshFriends = function () {
          scope.data.showCantFindModal = false;
          $socialService.refreshNetworks();
        }

      }
    };
  }
]);
