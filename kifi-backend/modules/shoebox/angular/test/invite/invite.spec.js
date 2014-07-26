'use strict';

describe('kifi.invite', function () {
  var $injector, $rootScope, $httpBackend, routeService, $location;

  beforeEach(module('kifi.invite'));
  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
    $rootScope = $injector.get('$rootScope');
    $httpBackend = $injector.get('$httpBackend');
    $location = $injector.get('$location');
    routeService = $injector.get('routeService');
  }));

  describe('auto friend request', function () {
    var scope, elem;
    var compile = function () {
      $injector.get('$compile')(elem)(scope);
      scope.$digest();
    };

    beforeEach(function () {
      scope = $rootScope.$new();
      elem = angular.element("<div kf-auto-friend-request></div>");
      spyOn($location, 'hash').andReturn('friend=3&name=Joe');
    });

    it('shows a success message after server response ok', function () {
      $httpBackend.expectPOST(routeService.friendRequest(3)).respond(200, '{}');
      expect(scope.showAutoFriendRequestSuccess).toBeFalsy();
      compile();
      $httpBackend.flush();
      expect(scope.showAutoFriendRequestSuccess).toBeTruthy();
      expect(scope.showAutoFriendRequestError).toBeFalsy();
    });

    it('shows an error message after server response not ok', function () {
      $httpBackend.expectPOST(routeService.friendRequest(3)).respond(404, '{}');
      compile();
      $httpBackend.flush();
      expect(scope.showAutoFriendRequestSuccess).toBeFalsy();
      expect(scope.showAutoFriendRequestError).toBeTruthy();
    });
  });
});
