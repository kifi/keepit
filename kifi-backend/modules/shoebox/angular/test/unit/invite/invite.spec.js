/* global xit: false */
'use strict';

describe('kifi.invite', function () {
  var $injector, $rootScope, $httpBackend, routeService, $location, $compile,
    profileService, inviteService, elem, scope;

  var fakeSocialId = '29a10380-166a-11e4-8c21-0800200c9a66';

  // helper to get an element's isolated scope
  // precondion: elem var has been set
  function iscope() {
    return elem.isolateScope();
  }

  function compile() {
    $compile(elem)(scope);
    scope.$digest();
  }

  function mockPromise() {
    return $injector.get('$q').defer().promise;
  }

  beforeEach(module('kifi'));
  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
    $rootScope = $injector.get('$rootScope');
    $httpBackend = $injector.get('$httpBackend');
    $location = $injector.get('$location');
    $compile = $injector.get('$compile');
    routeService = $injector.get('routeService');
    profileService = $injector.get('profileService');
    inviteService = $injector.get('inviteService');

    scope = $rootScope.$new();
  }));

  describe('kfSocialInviteAction', function () {
    beforeEach(function () {
      var html =
        '<div kf-social-invite-action class="clickable kf-add-friend-banner-action" result="result">' +
          '<a href="javascript:" class="kf-add-friend-banner-add-button clickable-target"' +
            'ng-click="invite(result, $event)"><span class="sprite sprite-tag-new-plus-icon"> </span>Add</a>' +
        '</div>';
      elem = angular.element(html);
    });

    it('creates a 2-way binding with parent scope\'s "result"', function () {
      $rootScope.result = { foo: 'bar' };
      compile();
      var scope = iscope();
      expect(scope.result.foo).toEqual('bar');

      // test that changes from the outer scope updates the isolated scope
      $rootScope.result.foo = 'baz';
      expect(scope.result.foo).toEqual('baz');

      // test that changes from the isolated scope updates the outer scope
      scope.result.foo = 'moo';
      expect($rootScope.result.foo).toEqual('moo');
    });

    describe('scope.invite()', function () {
      it('is defined by the directive', function () {
        compile();
        expect(typeof iscope().invite).toEqual('function');
      });

      xit('is called on element click', function () {
        compile();
        var scope = iscope();
        spyOn(scope, 'invite');
        elem.find('a').click();
        expect(scope.invite).toHaveBeenCalled();
      });

      it('it calls inviteService.invite when user is not in fortytwo network', function () {
        $rootScope.result = {
          networkType: 'facebook',
          socialId: fakeSocialId
        };

        compile();
        // better to stub the http call and test the success/error functions, but I'm out of time
        spyOn(inviteService, 'invite').and.returnValue(mockPromise());
        iscope().invite();
        expect(inviteService.invite).toHaveBeenCalledWith('facebook', fakeSocialId);
      });

      it('it calls inviteService.friendRequest when user is in fortytwo network', function () {
        $rootScope.result = {
          networkType: 'fortytwo',
          socialId: fakeSocialId
        };

        compile();
        // better to stub the http call and test the success/error functions, but I'm out of time
        spyOn(inviteService, 'friendRequest').and.returnValue(mockPromise());
        iscope().invite();
        expect(inviteService.friendRequest).toHaveBeenCalledWith(fakeSocialId);
      });
    });
  });
});
