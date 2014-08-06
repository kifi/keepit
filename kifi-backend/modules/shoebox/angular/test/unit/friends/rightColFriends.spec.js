'use strict';

describe('kifi.friends.rightColFriendsView', function () {

  var $injector,
      $compile,
      $rootScope,
      tpl,
      elem,
      scope,
      friendService;

  tpl = '<div kf-compact-friends-view></div>';

  function compile() {
    elem = angular.element(tpl);
    scope = $rootScope.$new();
    $compile(elem)(scope);
  }

  function mockPromise() {
    return $injector.get('$q').defer().promise;
  }

  beforeEach(module('kifi.friends.rightColFriendsView'));

  beforeEach(inject(function (_$injector_) {
    $injector = _$injector_;
    $rootScope = $injector.get('$rootScope');
    $compile = $injector.get('$compile');

    friendService = $injector.get('friendService');
  }));

  describe('kfCompactFriendsView', function () {
    beforeEach(function () {
      compile();
    });

    it('should have the correct friend count text', function () {
      spyOn(friendService, 'totalFriends').andReturn(2);
      spyOn(friendService, 'getKifiFriends').andReturn(mockPromise());

      // Hardcode this to be a positive number so ng-if will be true
      // for this test.
      scope.friends = [1, 2];

      scope.$digest();

      // With a combination of replace being true and ng-if, the
      // element for the directive is actually the original element's
      // sibling.
      // See: http://stackoverflow.com/questions/24011324/unit-testing-a-directive-with-isolated-scope-bidirectional-value-and-ngif
      elem = elem.next();

      expect(friendService.totalFriends).toHaveBeenCalled();
      expect(elem.find('.kf-rightcol-friends-links div').text()).toBe('2 friends');
    });
  });

});

