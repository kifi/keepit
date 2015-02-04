'use strict';

describe('kifi.friends.rightColFriendsView', function () {

  beforeEach(module('kifi'));

  var $q,
      $compile,
      $rootScope,
      friendService;

  beforeEach(inject(function ($injector) {
    $q = $injector.get('$q');
    $compile = $injector.get('$compile');
    $rootScope = $injector.get('$rootScope');
    friendService = $injector.get('friendService');
  }));

  describe('kfCompactFriendsView', function () {
    var scope,
        elem;

    beforeEach(function () {
      scope = $rootScope.$new();
      elem = $compile('<div kf-compact-friends-view></div>')(scope);
    });

    it('should have the correct friend count text', function () {
      spyOn(friendService, 'totalFriends').and.returnValue(2);
      spyOn(friendService, 'getKifiFriends').and.returnValue(promise([{}, {}]));

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

  function promise(val) {
    var deferred = $q.defer();
    deferred.resolve(val);
    return deferred.promise;
  }

});
