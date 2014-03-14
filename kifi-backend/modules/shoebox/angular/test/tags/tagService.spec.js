'use strict';

describe('kifi.tagService', function () {

  var tagService,
    $httpBackend;

  beforeEach(module('kifi', 'kifi.tagService'));

  beforeEach(inject(function (_tagService_, _$httpBackend_) {
    tagService = _tagService_;
    $httpBackend = _$httpBackend_;
  }));

  afterEach(function () {
    $httpBackend.verifyNoOutstandingExpectation();
    $httpBackend.verifyNoOutstandingRequest();
  });

  describe('tagService', function () {
    it('should have an array named "list"', function () {
      expect(Array.isArray(tagService.list)).toBe(true);
      expect(tagService.list.length).toBe(0);
    });
  });

  describe('tagService.fetchAll', function () {
    it('should make a GET request to /collections/all with params', function () {
      var tags = [
        {
          id: 'TAG_ID_1',
          name: 'TAG_NAME_1'
        },
        {
          id: 'TAG_ID_2',
          name: 'TAG_NAME_2'
        }
      ];

      $httpBackend.when('GET', /\/collections\/all/).respond(function (method, url) {
        var sort = url.match(/sort=(\w+)/);
        sort = sort || '';
        return [200, {
          collections: tags,
          keeps: 123
        }];
      });

      var data;
      tagService.fetchAll().then(function (list) {
        data = list;
      });

      $httpBackend.flush();

      expect(data).toEqual(tags);
      expect(data).not.toBe(tags);
      expect(tagService.list).toBe(data);

      expect(tagService.getById('TAG_ID_1')).toEqual(tags[0]);
      expect(tagService.getById('TAG_ID_2')).toEqual(tags[1]);
      expect(tagService.getById('TAG_ID_3')).toBe(null);
    });
  });
});
