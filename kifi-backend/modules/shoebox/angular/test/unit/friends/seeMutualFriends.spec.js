'use strict';

describe('kifi.friends.seeMutualFriends', function () {

  beforeEach(module('kifi.friends.seeMutualFriends'));

  var $q,
      $compile,
      $rootScope,
      friendService,
      inviteService,
      savePymkService;

  beforeEach(inject(function ($injector) {
    $q = $injector.get('$q');
    $compile = $injector.get('$compile');
    $rootScope = $injector.get('$rootScope');
    friendService = $injector.get('friendService');
    inviteService = $injector.get('inviteService');
    savePymkService = $injector.get('savePymkService');
  }));

  describe('kfSeeMutualFriends', function () {
    var scope,
        elem;

    var testSavedPerson = null;
    var savedPerson1 = {
      id: '1a316f42-13be-4d86-a4a2-8c7efb3010b8',
      fullName: 'Alexander Willis Schultz',
      pictureUrl: '//djty7jcqog9qu.cloudfront.net/users/1a316f42-13be-4d86-a4a2-8c7efb3010b8/pics/200/xOMd7.jpg',
      numMutualFriends: 2,
      mutualFriends: [
        {
          id: '597e6c13-5093-4cba-8acc-93318987d8ee',
          firstName: 'Stephen',
          lastName: 'Kemmerling',
          numFriends: 71,
          pictureName: 'tEbqN.jpg'
        },
        {
          id: 'ae139ae4-49ad-4026-b215-1ece236f1322',
          firstName: 'Jen',
          lastName: 'Granito Ruffner',
          numFriends: 148,
          pictureName: 'Upm5X.jpg'
        }
      ]
    };
    var savedPerson2 = {
      id: 'fake-id-123',
      fullName: 'John Smith',
      pictureUrl: '//djty7jcqog9qu.cloudfront.net/users/fake-id-123/pics/200/0.jpg',
      numMutualFriends: 2,
      mutualFriends: [
        {
          id: '597e6c13-5093-4cba-8acc-93318987d8ee',
          firstName: 'Stephen',
          lastName: 'Kemmerling',
          numFriends: 71,
          pictureName: 'tEbqN.jpg'
        },
        {
          id: 'ae139ae4-49ad-4026-b215-1ece236f1322',
          firstName: 'Jen',
          lastName: 'Granito Ruffner',
          numFriends: 148,
          pictureName: 'Upm5X.jpg'
        }
      ]
    };

    beforeEach(function () {
      scope = $rootScope.$new();
      elem = $compile('<div kf-see-mutual-friends></div>')(scope);

      spyOn(savePymkService, 'getSavedPersonYouMayKnow').andCallFake(function () {
        return testSavedPerson;
      });
      spyOn(friendService, 'getPictureUrlForUser').andReturn('fake picture URL');
    });

    it('should have the correct pymk header', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      expect(elem.find('.kf-mutual-friends-pymk').length > 0);
      expect(elem.find('.kf-mutual-friends-pymk-name').text()).toBe('Alexander Willis Schultz');
      expect(elem.find('.kf-mutual-friends-pymk-number').text()).toMatch('2');
    });

    it('should have the correct mutual friends', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      expect(elem.find('.kf-mutual-friend-card').length).toBe(2);
      expect(elem.find('.kf-mutual-friend-card-name').eq(0).text()).toBe('Stephen Kemmerling');
      expect(elem.find('.kf-mutual-friend-card-name').eq(1).text()).toBe('Jen Granito Ruffner');

    });

    it('should update modal content when saved person changes', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();
      expect(elem.find('.kf-mutual-friends-pymk-name').text()).toBe('Alexander Willis Schultz');

      testSavedPerson = savedPerson2;
      scope.$digest();
      expect(elem.find('.kf-mutual-friends-pymk-name').text()).toBe('John Smith');
    });

    it('should call inviteService\'s friendRequest when add friend link is called', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      spyOn(inviteService, 'friendRequest').andReturn(promise(null));
      elem.find('.kf-mutual-friends-action').click();
      expect(inviteService.friendRequest).toHaveBeenCalled();
    });

    it('should update action text when add friend link is called', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      spyOn(inviteService, 'friendRequest').andReturn(promise(null));
      expect(elem.find('.kf-mutual-friends-action').text()).toBe('Add Friend');
      elem.find('.kf-mutual-friends-action').click();
      expect(elem.find('.kf-mutual-friends-action').text()).toBe('Sent!');
    });

    it('should update action text when add friend link is called with invite error', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      spyOn(inviteService, 'friendRequest').andReturn(rejectedPromise());
      expect(elem.find('.kf-mutual-friends-action').text()).toBe('Add Friend');
      elem.find('.kf-mutual-friends-action').click();
      expect(elem.find('.kf-mutual-friends-action').text()).toBe('Error. Retry?');
    });

    it('should disable click when add friend link is called', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      spyOn(inviteService, 'friendRequest').andReturn(promise(null));
      expect(elem.find('.kf-mutual-friends-action').hasClass('clickable')).toBe(true);
      elem.find('.kf-mutual-friends-action').click();
      expect(elem.find('.kf-mutual-friends-action').hasClass('clickable')).toBe(false);
    });

    it('should disable and then reenable click when add friend link is called', function () {
      testSavedPerson = savedPerson1;
      scope.$digest();

      expect(elem.find('.kf-mutual-friends-action').hasClass('clickable')).toBe(true);

      var deferred = $q.defer();
      spyOn(inviteService, 'friendRequest').andReturn(deferred.promise);
      elem.find('.kf-mutual-friends-action').click();
      expect(elem.find('.kf-mutual-friends-action').hasClass('clickable')).toBe(false);

      deferred.reject();
      scope.$digest();
      expect(elem.find('.kf-mutual-friends-action').hasClass('clickable')).toBe(true);
    });
  });

  function promise (val) {
    var deferred = $q.defer();
    deferred.resolve(val);
    return deferred.promise;
  }

  function rejectedPromise () {
    var deferred = $q.defer();
    deferred.reject();
    return deferred.promise;
  }
});
