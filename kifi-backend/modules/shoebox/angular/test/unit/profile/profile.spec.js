'use strict';

describe('kifi.profile', function () {
  var $injector;

  beforeEach(module('kifi'));
  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
  }));

  describe('close account link', function () {
    var $rootScope, $compile, $httpBackend, scope, elem, isolateScope, link,
      errorMsgElem, successMsgElem, routeService;

    beforeEach(inject(function () {
      $rootScope = $injector.get('$rootScope');
      $compile = $injector.get('$compile');
      $httpBackend = $injector.get('$httpBackend');
      routeService = $injector.get('routeService');

      scope = $rootScope.$new();
      elem = angular.element('<div kf-profile-manage-account></div>');
      $compile(elem)(scope);
      scope.$digest();

      link = elem.find('a.close-account-button');
      successMsgElem = elem.find('p.profile-close-account-status.success');
      errorMsgElem = elem.find('p.profile-close-account-status.error');

      isolateScope = elem.isolateScope();
      isolateScope.comment = 'n/a';
    }));

    describe('valid request', function () {
      beforeEach(function () {
        $httpBackend.expectPOST(routeService.userCloseAccount, { 'comment': 'n/a' }).respond(200, '{"closed":true}');
        expect(link.text()).toEqual('Close Account');
        link.click();
      });

      it('updates the button text during & after sending the request', function () {
        expect(link.text()).toEqual('Sending...');
        $httpBackend.flush();
        expect(link.text()).toEqual('Message Sent');
      });

      it('displays a success message after the request is sent', function () {
        expect(successMsgElem.hasClass('ng-hide')).toBe(true);
        $httpBackend.flush();
        expect(successMsgElem.hasClass('ng-hide')).toBe(false);
      });

      it('does not allow the request to be sent more than once', function () {
        $httpBackend.flush();
        link.click();
        $httpBackend.verifyNoOutstandingExpectation();
        $httpBackend.verifyNoOutstandingRequest();
      });
    });

    describe('invalid request', function () {
      beforeEach(function () {
        $httpBackend.expectPOST(routeService.userCloseAccount, { 'comment': 'n/a' }).respond(422);
        link.click();
      });

      it('shows an error message if the server throws an error', function () {
        expect(link.text()).toEqual('Sending...');
        $httpBackend.flush();
        expect(link.text()).toEqual('Retry');
      });

      it('displays an error message after the request returns an error', function () {
        expect(errorMsgElem.hasClass('ng-hide')).toBe(true);
        $httpBackend.flush();
        expect(errorMsgElem.hasClass('ng-hide')).toBe(false);
      });
    });
  });
});
