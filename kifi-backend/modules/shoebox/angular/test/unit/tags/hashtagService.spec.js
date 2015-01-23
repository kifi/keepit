'use strict';

describe('kifi.hashtagService', function () {

  var hashtagService, routeService, $httpBackend, $q, $rootScope, keepDecoratorService;

  var libraryId = 'l6XWQOhPGRgg',
      keepId = '42b043aa-e389-45f2-ab37-b7809990ee7e';

  function createKeep(keepId, hashtags) {
    hashtags = hashtags || ['foo', 'bar'];
    // mock keep
    var keep = new keepDecoratorService.Keep({
      'libraryId': libraryId,
      'id': keepId,
      'url': 'https://www.kifi.com',
      'hashtags': hashtags,
      'keepers': []
    });
    keep.buildKeep(keep);
    keep.makeKept();
    return keep;
  }

  beforeEach(module('kifi'));

  beforeEach(inject(function (_hashtagService_, _routeService_, _$httpBackend_, _$q_, _$rootScope_, _keepDecoratorService_) {
    hashtagService = _hashtagService_;
    routeService = _routeService_;
    $httpBackend = _$httpBackend_;
    $q = _$q_;
    $rootScope = _$rootScope_;
    keepDecoratorService = _keepDecoratorService_;
  }));

  afterEach(function () {
    $httpBackend.verifyNoOutstandingExpectation();
    $httpBackend.verifyNoOutstandingRequest();
  });

  describe('hashtagService', function () {
    it('suggestsTags returns suggested tags for keeps', function () {
      var keep = createKeep(keepId);
      var p = hashtagService.suggestTags(keep, 'scala');
      p.then(function (data) {
        expect(data).toEqual([{'tag':'Scala','matches':[[0,5]]},{'tag':'Learn Scala','matches':[[6,5]]}]);
      });

      $httpBackend.expectGET(routeService.suggestTags('l6XWQOhPGRgg', '42b043aa-e389-45f2-ab37-b7809990ee7e', 'scala'))
        .respond(200, '[{"tag":"Scala","matches":[[0,5]]},{"tag":"Learn Scala","matches":[[6,5]]}]');
      $httpBackend.flush();
    });

    it('tagKeep adds tag to keep', function () {
      var keep = createKeep(keepId);
      expect(keep.hashtags).toEqual(['foo', 'bar']);
      var p = hashtagService.tagKeep(keep, 'Scala');
      p.then(function (data) {
        expect(data).toEqual({'tag':'Scala'});
        expect(keep.hashtags).toEqual(['foo', 'bar', 'Scala']);
      });
      $httpBackend.expectPOST(routeService.tagKeep(libraryId, keepId, 'Scala')).respond(200, '{"tag":"Scala"}');
      $httpBackend.flush();
    });

    it('tagKeep bulk adds tag to keeps', function () {
      var keep0 = createKeep('00000000-0000-0000-0000-000000000000', ['foo']);
      var keep1 = createKeep('00000000-0000-0000-0000-000000000001', ['bar']);
      var keep2 = createKeep('00000000-0000-0000-0000-000000000002', ['baz']);
      var p = hashtagService.tagKeeps([keep0, keep1, keep2], 'Scala');
      p.then(function () {
        expect(keep0.hashtags).toEqual(['foo', 'Scala']);
        expect(keep1.hashtags).toEqual(['bar', 'Scala']);
        expect(keep2.hashtags).toEqual(['baz', 'Scala']);
      });
      $httpBackend.expectPOST(routeService.tagKeeps('Scala'), { 'keepIds':
        [keep0.id, keep1.id, keep2.id] }).respond(200, '{}');
      $httpBackend.flush();
    });

    it('untagKeep removes tag from keep', function () {
      var keep = createKeep(keepId);
      expect(keep.hashtags).toEqual(['foo', 'bar']);
      var p = hashtagService.untagKeep(keep, 'foo');
      p.then(function (data) {
        expect(data).toBeUndefined();
        expect(keep.hashtags).toEqual(['bar']);
      });
      $httpBackend.expectDELETE(routeService.untagKeep(libraryId, keepId, 'foo')).respond(204);
      $httpBackend.flush();
    });

  });
});
