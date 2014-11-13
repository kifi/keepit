'use strict';

describe('kifi.youtube', function () {

  var $compile,
    $rootScope,
    $timeout,
    scope,
    iScope,
    $body;

  beforeEach(module('kifi'));

  beforeEach(inject(function (_$compile_, _$rootScope_, _$timeout_, _$document_) {
    $compile = _$compile_;
    $rootScope = _$rootScope_;
    $timeout = _$timeout_;
    $body = _$document_.find('body');
  }));

  describe('kfYoutube', function () {
    var element,
      vid = 'd2ZNaLQD60Y';

    beforeEach(function () {
      scope = $rootScope.$new();
      scope.videoId = vid;

      element = angular.element('<div kf-youtube video-id="videoId"></div>');
      $body.append(element);
      $compile(element)(scope);

      scope.$apply();
      iScope = element.isolateScope();
    });

    afterEach(function () {
      element.remove();
    });

    it('should append an iframe element as a child', function () {
      var child = element.find('img')[0];
      expect(child.nodeName).toBe('IMG');
      expect(iScope.videoId).toBe(vid);
      expect(child.src).toContain(vid);
      expect(child.src).toContain('youtube.com');
    });

    it('should update src whenever videoId is changed', function () {
      var child = element.find('img')[0];
      expect(iScope.videoId).toBe(vid);
      expect(child.src).toContain(vid);

      var newVid = 'NEW_VIDEO_ID';
      scope.videoId = newVid;
      scope.$digest();

      child = element.find('img')[0];
      expect(iScope.videoId).toBe(newVid);
      expect(child.src).not.toContain(vid);
      expect(child.src).toContain(newVid);
    });
  });

});
