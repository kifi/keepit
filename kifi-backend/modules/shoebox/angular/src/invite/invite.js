'use strict';

angular.module('kifi.invite', [
  'kifi',
  'util',
  'kifi.profileService',
  'kifi.routeService',
  'jun.facebook',
  'kifi.inviteService',
  'kifi.userService',
  'kifi.keepWhoService',
  'kifi.social',
  'kifi.modal'
])

.config([
  '$routeProvider',
  function ($routeProvider) {
    $routeProvider.when('/invite', {
      templateUrl: 'invite/invite.tpl.html'
    }).when('/friends/invite', {
      redirectTo: '/invite'
    });
  }
])

.controller('InviteCtrl', [
  '$scope', '$rootScope', '$http', 'profileService', 'routeService', '$window', 'wtiService', 'socialService',
  function ($scope, $rootScope, $http, profileService, routeService, $window, wtiService, socialService) {
    $window.document.title = 'Kifi • Invite your friends';

    $scope.$watch(socialService.checkIfRefreshingSocialGraph, function (v) {
      $scope.isRefreshingSocialGraph = v;
    });

    socialService.checkIfUpdatingGraphs(2);

    $scope.whoToInvite = wtiService.list;

    $scope.wtiLoaded = false;
    $scope.$watch(function () {
      return wtiService.list.length || !wtiService.hasMore();
    }, function (res) {
      if (res) {
        $scope.wtiLoaded = true;
      }
    });

    $scope.wtiScrollDistance = '100%';
    $scope.isWTIScrollDisabled = function () {
      return !wtiService.hasMore();
    };
    $scope.wtiScrollNext = wtiService.getMore;

    $scope.showAddNetworksModal = function () {
      $rootScope.$emit('showGlobalModal', 'addNetworks');
    };
  }
])

.directive('kfSocialInviteWell', [
  function () {
    return {
      scope: {
        'showFindFriends': '='
      },
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteWell.tpl.html',
      link: function (/*scope, element, attrs*/) {

      }
    };
  }
])

.directive('kfSocialNetworksStatus', [
  'socialService', '$rootScope',
  function (socialService, $rootScope) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/socialNetworkStatus.tpl.html',
      link: function (scope/*, element, attrs*/) {
        scope.numNetworks = socialService.networks.length;
        scope.$watch(function () {
          return socialService.networks.length;
        }, function (networksLength) {
          scope.numNetworks = networksLength;
        });


        scope.data = scope.data || {};

        scope.showAddNetworks = function () {
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };

        socialService.refresh();
      }
    };
  }
])

.directive('kfSocialInviteAction', [
  '$log', 'inviteService', '$timeout',
  function ($log, inviteService, $timeout) {
    return {
      restrict: 'A',
      scope: {
        result: '=',
        onAfterInvite: '&'
      },
      replace: true,
      transclude: true,
      template: '<div ng-transclude></div>',
      link: function (scope, elem) {
        var ignoreClick = {};

        // This is a **hack** because ng-click was not invoking scope.invite(),
        // NOTE: this REQUIRES that the arbitrary contents of this directive contains
        // the "div.clicktable-target" element or the click event will not work
        var link = elem.find('div.clickable-target');
        link.click(function (elem, $event) {
          scope.invite(scope.result, $event);
        });

        scope.invite = function (result, $event) {
          result = result || scope.result;
          $log.log('this person:', result);
          if (ignoreClick[result.socialId]) {
            return;
          }
          ignoreClick[result.socialId] = true;

          var $elem = $event && angular.element($event.target) || elem.find('div.clickable-target');
          $elem.text('Sending');
          $elem.parent().removeClass('clickable');
          if (result.networkType === 'fortytwo' || result.networkType === 'fortytwoNF') {
            // Existing user, friend request
            inviteService.friendRequest(result.socialId).then(function () {
              $elem.text('Sent!');
              $timeout(function () {
                delete ignoreClick[result.socialId];
                $elem.text('Resend');
                $elem.parent().addClass('clickable');
              }, 4000);
              inviteService.expireSocialSearch();

              if (typeof scope.onAfterInvite === 'function') {
                scope.onAfterInvite();
              }
            }, function (err) {
              $log.log('err:', err, result);
              delete ignoreClick[result.socialId];
              $elem.text('Error. Retry?');
              $elem.parent().addClass('clickable');
              inviteService.expireSocialSearch();
            });
          } else {
            // Request to external person
            inviteService.invite(result.networkType, result.socialId).then(function () {
              $elem.text('Sent!');
              $timeout(function () {
                delete ignoreClick[result.socialId];
                $elem.text('Resend');
                $elem.parent().addClass('clickable');
              }, 4000);
              inviteService.expireSocialSearch();

              if (typeof scope.onAfterInvite === 'function') {
                scope.onAfterInvite();
              }
            }, function (err) {
              $log.log('err:', err, result);
              delete ignoreClick[result.socialId];
              $elem.text('Error. Retry?');
              $elem.parent().addClass('clickable');
              inviteService.expireSocialSearch();
            });
          }
        };
      }
    };
  }
])

.directive('kfSocialInviteSearch', [
  'inviteService', '$document', '$log', 'socialService', '$timeout', '$rootScope',
  function (inviteService, $document, $log, $socialService, $timeout, $rootScope) {
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
              scope.search.name = '';
              scope.search.showDropdown = false;
            });
          }
        }

        scope.$on('$destroy', function () {
          $document.off('click', clickOutside);
        });

        $document.on('click', clickOutside);

        scope.refreshFriends = function () {
          scope.data.showCantFindModal = false;
          $socialService.refreshSocialGraph();
        };

        scope.connectNetworks = function () {
          scope.data.showCantFindModal = false;
          $rootScope.$emit('showGlobalModal', 'addNetworks');
        };

        scope.hasNetworks = function () {
          return !!$socialService.networks.length;
        };

      }
    };
  }
])

.directive('kfFriendRequestBanner', [
  'injectedState', 'routeService', 'userService', 'keepWhoService', 'profileService', '$timeout', '$analytics',
  function (injectedState, routeService, userService, keepWhoService, profileService, $timeout, $analytics) {

    function setupShowFriendRequestBanner(scope, externalId) {
      userService.getBasicUserInfo(externalId, true).then(function (res) {
        var user = res.data,
            picUrl = keepWhoService.getPicUrl(user, 200);
        scope.user = user;
        scope.mainImage = picUrl;
        scope.mainLabel = user.firstName + ' ' + user.lastName;
        scope.hidden = false;
        scope.actionText = 'Add';
        scope.result = {
          socialId: externalId,
          networkType: 'fortytwo'
        };

        scope.onAfterInvite = function () {
          $analytics.eventTrack('user_clicked_page', {
            type: 'addFriends',
            subtype: 'contactJoined',
            action: 'addFriend'
          });

          $timeout(function () {
            scope.hidden = true;
          }, 3000);
        };
      });

      scope.close = function () {
        $analytics.eventTrack('user_clicked_page', {
          type: 'addFriends',
          subtype: 'contactJoined',
          action: 'close'
        });

        scope.hidden = true;
      };
    }

    function link(scope) {
      var externalId = injectedState.state.friend;
      scope.hidden = true;
      if (!externalId) {
        return;
      }

      scope.showFriendRequestBanner = true;
      setupShowFriendRequestBanner(scope, externalId);
    }

    return {
      restrict: 'A',
      templateUrl: 'invite/friendRequestBanner.tpl.html',
      replace: true,
      scope: {},
      link: link
    };
  }
])

;
