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
  '$log', 'inviteService',
  function ($log, inviteService) {
    return {
      restrict: 'A',
      scope: {
        result: '='
      },
      link: function (scope, elem) {
        var ignoreClick = {};

        console.log('created kfSocialInviteAction', elem);
        console.log('scope.result', scope.result);

        scope.invite = function (result, $event) {
          console.log('kfSocialInviteAction.link', scope.result);
          return;
          $log.log('this person:', result);
          if (ignoreClick[result.socialId]) {
            return;
          }
          ignoreClick[result.socialId] = true;

          var $elem = angular.element($event.target);
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
    }
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

        var ignoreClick = {};

        /*
        scope.invite = function (result, $event) {
          $log.log('this person:', result);
          if (ignoreClick[result.socialId]) {
            return;
          }
          ignoreClick[result.socialId] = true;

          var $elem = angular.element($event.target);
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
            }, function (err) {
              $log.log('err:', err, result);
              delete ignoreClick[result.socialId];
              $elem.text('Error. Retry?');
              $elem.parent().addClass('clickable');
              inviteService.expireSocialSearch();
            });
          }
        };
        */

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

// we don't want to call inviteService.friendRequest twice; this factory
// prevents an invite from being sent to the same externalId more than once;
// because of the injectedState and $location.search({}),
// controllers/directives are called twice
.factory('friendRequestFactory', [
  '$location', '$http', '$rootScope', '$q', 'routeService', 'inviteService',
  function ($location, $http, $rootScope, $q, routeService, inviteService) {
    var runs = {};

    function runOnce(externalId) {
      if (runs.hasOwnProperty(externalId)) {
        return runs[externalId];
      }

      var deferred = $q.defer();
      inviteService.friendRequest(externalId).then(function (res) {
        deferred.resolve(res);
      }, function (res) {
        deferred.reject(res);
      });

      return (runs[externalId] = deferred.promise);
    }

    return { 'run': runOnce };
  }
])

.directive('kfFriendRequestBanner', [
  'injectedState', 'friendRequestFactory', 'routeService', 'userService', 'keepWhoService', 'profileService',
  function (injectedState, friendRequestFactory, routeService, userService, keepWhoService, profileService) {

    function setupShowFriendRequestBanner(scope, externalId) {
      userService.getBasicUserInfo(externalId).then(function (res) {
        var user = res.data,
            picUrl = keepWhoService.getPicUrl(user, 200);
        scope.user = _.extend(user, {
          mainImage: picUrl,
          name: user.firstName + ' ' + user.lastName
        });
        scope.state = 'user-loaded';
        scope.friendCount = 1;
        scope.mainImage = picUrl;
        scope.hidden = false;
        scope.actionText = 'Add';
        scope.mainLabel = scope.name;
        scope.result = {
          socialId: externalId,
          networkType: 'fortytwo'
        };
        console.log(scope.result);
      }, function () {
        scope.state = 'user-load-error';
      });

      scope.confirmFriendRequest = function () {
        scope.state = 'Sending';
        var retries = 3;
        $timeout(function () {
          friendRequestFactory.run(externalId).then(function () {
            scope.state = 'Sent!';
            scope.state = 'friend-request-complete';
          }, function () {
            scope.state = 'Error';
            scope.state = 'friend-request-error';
          });
        });
      };

      scope.close = function () {
        scope.hideBanner = true;
      };
    }

    function link(scope) {
      scope.hidden = true;
      var externalId = injectedState.state.friend;
      externalId = '75669bca-b570-4884-8f7a-2e22efd99d05';

      if (!externalId) {
        return;
      }

      profileService.getMe().then(function (me) {
        scope.showFriendRequestBanner = true || me.experiments && me.experiments.indexOf('notify_user_when_contacts_join') > -1;
        if (scope.showFriendRequestBanner) {
        console.log(me);
          setupShowFriendRequestBanner(scope, externalId);
        }
      });
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
