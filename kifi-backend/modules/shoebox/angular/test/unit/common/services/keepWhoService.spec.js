'use strict';

describe('kifi.keepWhoService', function () {

  var keepWhoService;

  beforeEach(module('kifi.keepWhoService'));

  beforeEach(inject(function (_keepWhoService_) {
    keepWhoService = _keepWhoService_;
  }));

  describe('keepWhoService.getPicUrl()', function () {
    it('returns an empty string when user is not set', function () {
      expect(keepWhoService.getPicUrl(null)).toBe('');
    });

    it('returns an empty string when user.id is not set', function () {
      expect(keepWhoService.getPicUrl({
        id: null,
        pictureName: 'USER_PIC'
      })).toBe('');
    });

    it('returns an empty string when user.pictureName is not set', function () {
      expect(keepWhoService.getPicUrl({
        id: 'USER_ID',
        pictureName: null
      })).toBe('');
    });

    it('returns a fully composed picture url with id and pictureName', function () {
      var user = {
        id: 'USER_ID',
        pictureName: 'USER_PIC'
      };
      expect(keepWhoService.getPicUrl(user)).toBe('//djty7jcqog9qu.cloudfront.net/users/' + user.id + '/pics/100/' + user.pictureName);
    });
  });

  describe('keepWhoService.getName()', function () {
    it('returns an empty string when user is not set', function () {
      expect(keepWhoService.getName(null)).toBe('');
    });

    it('returns an empty string when both user.firstName and user.lastName are not set', function () {
      expect(keepWhoService.getName({
        firstName: null,
        lastName: null
      })).toBe('');
    });

    it('returns first name when only user.firstName is set', function () {
      expect(keepWhoService.getName({
        firstName: 'FIRST_NAME',
        lastName: null
      })).toBe('FIRST_NAME');
    });

    it('returns last name when only user.lastName is set', function () {
      expect(keepWhoService.getName({
        firstName: null,
        lastName: 'LAST_NAME'
      })).toBe('LAST_NAME');
    });

    it('returns a full name when both user.firstName and user.lastName are set', function () {
      expect(keepWhoService.getName({
        firstName: 'FIRST_NAME',
        lastName: 'LAST_NAME'
      })).toBe('FIRST_NAME LAST_NAME');
    });
  });

});
