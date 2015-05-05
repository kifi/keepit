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
  });
});
