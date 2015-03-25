'use strict';

angular.module('kifi')

.controller('InviteCtrl', [
  '$scope', '$http', '$window', 'modalService', 'profileService', 'socialService', 'wtiService',
  function ($scope, $http, $window, modalService, profileService, socialService, wtiService) {
    $window.document.title = 'Kifi • Invite your connections';

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
      modalService.open({
        template: 'social/addNetworksModal.tpl.html'
      });
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
      link: function () {

      }
    };
  }
])

.directive('kfSocialNetworksStatus', [
  'modalService', 'socialService',
  function (modalService, socialService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/socialNetworkStatus.tpl.html',
      link: function (scope) {
        scope.numNetworks = socialService.networks.length;
        scope.$watch(function () {
          return socialService.networks.length;
        }, function (networksLength) {
          scope.numNetworks = networksLength;
        });


        scope.data = scope.data || {};

        scope.showAddNetworks = function () {
          modalService.open({
            template: 'social/addNetworksModal.tpl.html'
          });
        };

        socialService.refresh();
      }
    };
  }
])

.directive('kfSocialInviteAction', [
  '$log', 'inviteService', '$timeout', '$FB',
  function ($log, inviteService, $timeout, $FB) {
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

        $FB.init();

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
  '$document', '$log', 'inviteService', 'modalService',
  function ($document, $log, inviteService, modalService) {
    return {
      scope: {},
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/inviteSearch.tpl.html',
      link: function (scope, element) {
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

        scope.showCantFindFriendModal = function () {
          scope.search.showDropdown = false;

          modalService.open({
            template: 'invite/cantFindFriendModal.tpl.html',
            modalData: { searchFriendName: scope.search.name }
          });
        };
      }
    };
  }
])

.directive('kfCantFindFriend', [
  'modalService', 'socialService',
  function (modalService, socialService) {
    return {
      replace: true,
      restrict: 'A',
      templateUrl: 'invite/cantFindFriend.tpl.html',
      require: '^kfModal',
      link: function (scope, element, attrs, kfModalCtrl) {
        scope.close = kfModalCtrl.close;

        scope.hasNetworks = function () {
          return !!socialService.networks.length;
        };

        scope.refreshFriends = function () {
          scope.close();
          socialService.refreshSocialGraph();
        };

        scope.connectNetworks = function () {
          scope.close();
          modalService.open({
            template: 'social/addNetworksModal.tpl.html'
          });
        };

        scope.searchFriendName = scope.modalData.searchFriendName;
      }
    };
  }
]);
