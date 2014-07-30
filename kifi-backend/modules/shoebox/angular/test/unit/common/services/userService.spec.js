'use strict';

describe('kifi.userService', function () {
  var $injector, $httpBackend, routeService, userService;

  beforeEach(module('kifi.userService'));

  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
    $httpBackend = $injector.get('$httpBackend');
    routeService = $injector.get('routeService');
    userService = $injector.get('userService');
  }));

  describe('userService.getBasicUserInfo', function () {
    var id = '90132930-167f-11e4-8c21-0800200c9a66';

    it('calls the basicUserInfo route with the id', function () {
      $httpBackend.expectGET(routeService.basicUserInfo(id));
    });
  });
});
