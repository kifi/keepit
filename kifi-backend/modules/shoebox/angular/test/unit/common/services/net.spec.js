'use strict';

describe('net', function () {

  beforeEach(module('kifi'));
  beforeEach(module(function ($provide) {
    $provide.value('$http', function (o) { return o; });
  }));

  var net, env;
  beforeEach(inject(function (_net_, _env_) {
    net = _net_;
    env = _env_;
  }));

  describe('net.event', function () {
    it('issues a POST request', function () {
      var eventData = {type: 'foo', a: 0, b: true};
      expect(net.event(eventData)).toEqual({
        method: 'POST',
        url: env.xhrBase + '/events',
        params: undefined,
        data: eventData
      });
    });
  });

  describe('net.removeKeepFromLibrary', function () {
    it('issues a DELETE request', function () {
      var libraryId = 'l5B4wZ0GoRWb';
      var keepId = '8bcca8c5-d1a7-47d0-9f69-f8f58305779f';
      expect(net.removeKeepFromLibrary(libraryId, keepId)).toEqual({
        method: 'DELETE',
        url: env.xhrBase + '/libraries/' + libraryId + '/keeps/' + keepId,
        params: undefined,
        data: undefined
      });
    });
  });

  describe('net.search.search', function () {
    it('issues a GET request', function () {
      // Note: Angular omits a parameter from the query string if its value is [] (an empty array)
      var params = {q: 'dogfood', l: [], maxUsers: 3, maxLibraries: 3, maxUris: 3, is: '88x72'};
      expect(net.search.search(params)).toEqual({
        method: 'GET',
        url: env.xhrBaseSearch + '/search',
        params: params,
        cache: undefined
      });
    });
  });

});
