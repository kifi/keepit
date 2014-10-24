'use strict';

describe('kifi.hashtagService', function () {

  var hashtagService, $httpBackend, $q, $rootScope, keepDecoratorService;

  var libraryId = 'l6XWQOhPGRgg',
      keepId = '42b043aa-e389-45f2-ab37-b7809990ee7e',
      keep;

  beforeEach(module('kifi'));

  beforeEach(inject(function (_hashtagService_, _$httpBackend_, _$q_, _$rootScope_, _keepDecoratorService_) {
    hashtagService = _hashtagService_;
    $httpBackend = _$httpBackend_;
    $q = _$q_;
    $rootScope = _$rootScope_;
    keepDecoratorService = _keepDecoratorService_;

    // mock keep
    keep = new keepDecoratorService.Keep({
      'libraryId': libraryId,
      'id': keepId,
      'url': 'https://www.kifi.com',
      'hashtags': ['foo', 'bar']
    });
    keep.buildKeep(keep);
    keep.makeKept();
  }));

  afterEach(function () {
    $httpBackend.verifyNoOutstandingExpectation();
    $httpBackend.verifyNoOutstandingRequest();
  });

  describe('hashtagService', function () {
    it('suggestsTags returns suggested tags for keeps', function () {
      var p = hashtagService.suggestTags(keep, 'scala');
      p.then(function (data) {
        expect(data).toEqual([{'tag':'Scala','matches':[[0,5]]},{'tag':'Learn Scala','matches':[[6,5]]}]);
      });

      $httpBackend.expectGET('http://server/ext/libraries/l6XWQOhPGRgg/keeps/42b043aa-e389-45f2-ab37-b7809990ee7e/tags/suggest?q=scala')
        .respond(200, '[{"tag":"Scala","matches":[[0,5]]},{"tag":"Learn Scala","matches":[[6,5]]}]');
      $httpBackend.flush();

    });

    it('tagKeep adds tag to keep', function () {
      expect(keep.hashtags).toEqual(['foo', 'bar']);
      var p = hashtagService.tagKeep(keep, 'Scala');
      p.then(function (data) {
        expect(data).toEqual({'tag':'Scala'});
        expect(keep.hashtags).toEqual(['foo', 'bar', 'Scala']);
      });
      $httpBackend.expectPOST('http://server/site/libraries/'+ libraryId +'/keeps/' + keepId + '/tags/Scala').respond(200, '{"tag":"Scala"}');
      $httpBackend.flush();
    });

    it('untagKeep removes tag from keep', function () {
      expect(keep.hashtags).toEqual(['foo', 'bar']);
      var p = hashtagService.untagKeep(keep, 'foo');
      p.then(function (data) {
        expect(data).toBeUndefined();
        expect(keep.hashtags).toEqual(['bar']);
      });
      $httpBackend.expectDELETE('http://server/site/libraries/'+ libraryId +'/keeps/' + keepId + '/tags/foo').respond(204);
      $httpBackend.flush();
    });

  });
});
