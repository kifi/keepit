'use strict';

describe('util', function () {

  beforeEach(module('util'));

  var util;
  beforeEach(inject(function (_util_) {
    util = _util_;
  }));

  describe('util.startsWith', function () {
    it('returns true when both params are equal', function () {
      expect(util.startsWith('', '')).toBe(true);
      expect(util.startsWith('abc', 'abc')).toBe(true);
      expect(util.startsWith('abc', 'def')).toBe(false);
      expect(util.startsWith('', 'abc')).toBe(false);
    });

    it('returns true when the second param is an empty string', function () {
      expect(util.startsWith('', '')).toBe(true);
      expect(util.startsWith('abc', '')).toBe(true);
    });

    it('returns false when the first param is an empty string', function () {
      expect(util.startsWith('', '')).toBe(true);
      expect(util.startsWith('', 'abc')).toBe(false);
    });

    it('returns true when the first string starts with the second string', function () {
      expect(util.startsWith('', '')).toBe(true);
      expect(util.startsWith('a', '')).toBe(true);
      expect(util.startsWith('a', 'a')).toBe(true);
      expect(util.startsWith('ab', 'a')).toBe(true);
      expect(util.startsWith('ab', 'ab')).toBe(true);
      expect(util.startsWith('abc', 'ab')).toBe(true);
    });
  });

  describe('util.formatQueryString', function () {
    it('correctly formats and escapes query strings', function () {
      expect(util.formatQueryString({})).toBe('');
      expect(util.formatQueryString({a: []})).toBe('');
      expect(util.formatQueryString({a: true, b: false, c: null, d: undefined, e: 0, f: '', g: []})).toBe('?a&b=false&c=null&d=undefined&e=0&f=');
      expect(util.formatQueryString({a: true, b: '1/=2'})).toBe('?a&b=1%2F%3D2');
    });
  });

  describe('util.getYoutubeIdFromUrl', function () {
    it('correctly extracts youtube video IDs', function () {
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/v/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/e/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/embed/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://www.youtube.com/user/IngridMichaelsonVEVO#p/u/11/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('http://youtu.be/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('https://www.youtube-nocookie.com/v/dQw4w9WgXcQ?version=3&hl=en_US&rel=0')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('https://www.youtube.com/watch?feature=player_embedded&v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('https://www.youtube.com/?feature=player_embedded&v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.getYoutubeIdFromUrl('https://www.youtube.com/watch?v=zlz-WOglHgo')).toBe('zlz-WOglHgo'); // test dashes in youtubeId
      expect(util.getYoutubeIdFromUrl('https://www.youtube.com/watch?v=dYK_Gqyf48Y')).toBe('dYK_Gqyf48Y'); // test underscores in youtubeId
      expect(util.getYoutubeIdFromUrl('https://www.google.com')).toBe(null);
      expect(util.getYoutubeIdFromUrl('https://www.foursquare.com/v/dQw4w9WgXcQ')).toBe(null);
      expect(util.getYoutubeIdFromUrl('http://youtu.be/dQw4w9WgXcQ1')).toBe(null); // invalid youtube Id
    });
  });

  describe('util.linkify', function () {
    it('correctly identifies and linkifies URLs and email addresses', function () {
      expect(util.linkify('')).toBe('');
      expect(util.linkify('Hello!\nBye.')).toBe('Hello!\nBye.');
      expect(util.linkify('Email me: jo@flo.com')).toBe('Email me: <a href="mailto:jo@flo.com">jo@flo.com</a>');
      expect(util.linkify('I hang out at https://example.com. You?')).toBe(
        'I hang out at <a target="_blank" rel="nofollow" href="https:&#x2F;&#x2F;example.com">https:&#x2F;&#x2F;example.com</a>. You?');
      expect(util.linkify('a+b@c.com www.google.com/maps/123+Main/@37.4,-122.7/data=!3m1!1s:0xa\tb@c.d\ntwitter.com/example')).toBe(
        '<a href="mailto:a+b@c.com">a+b@c.com</a>' +
        ' <a target="_blank" rel="nofollow" href="http://www.google.com&#x2F;maps&#x2F;123+Main&#x2F;@37.4,-122.7&#x2F;data=!3m1!1s:0xa">' +
        'www.google.com&#x2F;maps&#x2F;123+Main&#x2F;@37.4,-122.7&#x2F;data=!3m1!1s:0xa</a>' +
        '\t<a href="mailto:b@c.d">b@c.d</a>' +
        '\n<a target="_blank" rel="nofollow" href="http://twitter.com&#x2F;example">twitter.com&#x2F;example</a>');
    });
  });

  describe('util.generateSlug', function () {
    it('correctly generates library slugs', function () {
      expect(util.generateSlug('-- Foo, Bar & Baz! --')).toBe('foo-bar-baz');
      expect(util.generateSlug('Far-away Places I’d like to go')).toBe('far-away-places-id-like-to-go');
      expect(util.generateSlug('Gift Ideas -- For That Special Someone')).toBe('gift-ideas-for-that-special-someone');
      expect(util.generateSlug('A Super Long Library Name That Surely Never Would Be Actually Chosen'))
        .toBe('a-super-long-library-name-that-surely-never-would');
      expect(util.generateSlug('Connections')).toBe('connections-');
    });
  });
});
