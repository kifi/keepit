'use strict';

describe('kifi.invite', function () {
  var $injector, $rootScope, $httpBackend, routeService, $location, injectedState, profileService;

  beforeEach(module('kifi.invite'));
  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
    $rootScope = $injector.get('$rootScope');
    $httpBackend = $injector.get('$httpBackend');
    $location = $injector.get('$location');
    routeService = $injector.get('routeService');
    injectedState = $injector.get('injectedState');
    profileService = $injector.get('profileService');
  }));

  describe('friend request banner', function () {
    var scope, iscope, elem, friendRequestUrl, basicUserInfoUrl, profileUrl, expectGetProfile;
    var compile = function () {
      $injector.get('$compile')(elem)(scope);
      scope.$digest();
      iscope = elem.isolateScope();
    };
    var externalId = '29a10380-166a-11e4-8c21-0800200c9a66'

    beforeEach(function () {
      injectedState.state.friend = externalId;
      scope = $rootScope.$new();
      elem = angular.element("<div kf-friend-request-banner></div>");
      friendRequestUrl = routeService.friendRequest(externalId);
      basicUserInfoUrl = routeService.basicUserInfo(externalId);
      profileUrl = routeService.profileUrl;

      expectGetProfile = function () {
        var json = '{"id":"' + externalId + '","firstName":"John","lastName":"Doe","pictureName":"7kSuC.jpg","username":"johndoe","emails":[{"address":"johndoe@gmail.com","isPrimary":true,"isVerified":true,"isPendingPrimary":false}],"notAuthed":[],"experiments":["notify_user_when_contacts_join"],"uniqueKeepsClicked":5,"totalKeepsClicked":5,"clickCount":2,"rekeepCount":0,"rekeepTotalCount":0}';
        $httpBackend.expectGET(profileUrl).respond(200, json);
      };
    });

    it('shows a success message after server response ok', function () {
      expectGetProfile();
      $httpBackend.expectGET(basicUserInfoUrl).respond(200,
        '{"id":"' + externalId + '","firstName":"John","lastName":"Doe","pictureName":"123.jpg","username":"johndoe"}');

      compile();

      expect(iscope.state).toBeFalsy();
      $httpBackend.flush();

      expect(iscope.state).toEqual('user-loaded');
      $httpBackend.expectPOST(friendRequestUrl).respond(200, '{}');
      elem.find('div.state-user-loaded div.user-add-button').click();
      $httpBackend.flush();

      expect(iscope.state).toEqual('friend-request-complete');
    });

    it('shows an error message after server response to get user info fails', function () {
      expectGetProfile();
      $httpBackend.expectGET(basicUserInfoUrl).respond(404, '{}');

      compile();
      expect(iscope.state).toBeFalsy();
      $httpBackend.flush();
      expect(iscope.state).toEqual('user-load-error');
    });

    it('shows an error message after server response to add user fails', function () {
      expectGetProfile();
      $httpBackend.expectGET(basicUserInfoUrl).respond(200,
        '{"id":"' + externalId + '","firstName":"John","lastName":"Doe","pictureName":"123.jpg","username":"johndoe"}');

      compile();
      $httpBackend.flush();
      expect(iscope.state).toEqual('user-loaded');

      $httpBackend.expectPOST(friendRequestUrl).respond(500, '{}');
      elem.find('div.state-user-loaded div.user-add-button').click();
      $httpBackend.flush();
      expect(iscope.state).toEqual('friend-request-error');
    });
  });
});
