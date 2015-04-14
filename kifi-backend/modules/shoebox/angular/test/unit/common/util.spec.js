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

  describe('util.preventOrphans', function () {
    it('makes spaces non-breaking near the end', function () {
      expect(util.preventOrphans(''), 16, 0.6).toBe('');
      expect(util.preventOrphans('Just for Men', 16, 0.6)).toBe(
        'Just for\u00a0Men');
      expect(util.preventOrphans('Expectant  + New Moms', 16, 0.6)).toBe(
        'Expectant + New\u00a0Moms');
      expect(util.preventOrphans('Become a Better Person', 16, 0.6)).toBe(
        'Become a Better\u00a0Person');
      expect(util.preventOrphans('Interesting Art History', 16, 0.6)).toBe(
        'Interesting Art\u00a0History');
      expect(util.preventOrphans('The art of body language', 16, 0.6)).toBe(
        'The art of body\u00a0language');
      expect(util.preventOrphans('Inspiration landing pages ', 16, 0.6)).toBe(
        'Inspiration landing\u00a0pages');
      expect(util.preventOrphans('Inspiring adventure films', 16, 0.6)).toBe(
        'Inspiring adventure\u00a0films');
      expect(util.preventOrphans('Overcoming Procrastination', 16, 0.6)).toBe(
        'Overcoming Procrastination');
      expect(util.preventOrphans('Anthropology & Archaeology', 16, 0.6)).toBe(
        'Anthropology & Archaeology');
      expect(util.preventOrphans('Useful Information for Parents ', 16, 0.6)).toBe(
        'Useful Information for\u00a0Parents');
      expect(util.preventOrphans('Useful how to tips and tricks', 16, 0.6)).toBe(
        'Useful how to tips\u00a0and\u00a0tricks');
      expect(util.preventOrphans('Inspiration from the tech world', 16, 0.6)).toBe(
        'Inspiration from the\u00a0tech\u00a0world');
      expect(util.preventOrphans('HTML Examples (e.g. <b> and &quot;)', 16, 0.6)).toBe(
        'HTML Examples (e.g.\u00a0<b>\u00a0and\u00a0&quot;)');
      expect(util.preventOrphans('Best Places to Eat Hummus in the Bay Area', 16, 0.6)).toBe(
        'Best Places to Eat Hummus\u00a0in\u00a0the\u00a0Bay\u00a0Area');
      expect(util.preventOrphans('Best Places to Eat Hummus in the Bay Area', 16, 1/3)).toBe(
        'Best Places to Eat Hummus in the\u00a0Bay\u00a0Area');
      expect(util.preventOrphans('The Internet of Things Will Thrive by 2025', 16, 1/3)).toBe(
        'The Internet of Things Will Thrive\u00a0by\u00a02025');
      expect(util.preventOrphans('The World of E-Sports and Competitive Gaming', 16, 1/3)).toBe(
        'The World of E\u2011Sports and Competitive Gaming');
      expect(util.preventOrphans('Useful handpicked marketing and growth articles', 16, 1/3)).toBe(
        'Useful handpicked marketing and growth\u00a0articles');
      expect(util.preventOrphans('Useful handpicked marketing and growth articles', 16, 0.6)).toBe(
        'Useful handpicked marketing and\u00a0growth\u00a0articles');
    });
  });

  describe('util.linkify', function () {
    it('correctly identifies and linkifies URLs and email addresses', function () {
      expect(util.linkify('')).toBe('');
      expect(util.linkify('Hello!\nBye.')).toBe('Hello!\nBye.');
      expect(util.linkify('Email me: jo@flo.com')).toBe('Email me: <a href="mailto:jo@flo.com">jo@flo.com</a>');
      expect(util.linkify('I hang out at https://example.com. You?')).toBe(
        'I hang out at <a target="_blank" rel="nofollow" href="https://example.com">https://example.com</a>. You?');
      expect(util.linkify('a+b@c.com & www.google.com/maps/123+Main/@37.4,-122.7/data=!3m1!1s:0xa\tb@c.d\ntwitter.com/example')).toBe(
        '<a href="mailto:a+b@c.com">a+b@c.com</a>' +
        ' &amp; <a target="_blank" rel="nofollow" href="http://www.google.com/maps/123+Main/@37.4,-122.7/data=!3m1!1s:0xa">' +
        'www.google.com/maps/123+Main/@37.4,-122.7/data=!3m1!1s:0xa</a>' +
        '\t<a href="mailto:b@c.d">b@c.d</a>' +
        '\n<a target="_blank" rel="nofollow" href="http://twitter.com/example">twitter.com/example</a>');
      expect(util.linkify('Writer.\nEmail: sarahp@techcrunch.com\nhttp://about.me/sarahperez\nArticles I’ve shared')).toBe(
        'Writer.\nEmail: <a href="mailto:sarahp@techcrunch.com">sarahp@techcrunch.com</a>' +
        '\n<a target="_blank" rel="nofollow" href="http://about.me/sarahperez">http://about.me/sarahperez</a>' +
        '\nArticles I’ve shared');
      expect(util.linkify('about.me/sarahperez')).toBe(
        '<a target="_blank" rel="nofollow" href="http://about.me/sarahperez">about.me/sarahperez</a>');
      expect(util.linkify('https://about.me/sarahperez')).toBe(
        '<a target="_blank" rel="nofollow" href="https://about.me/sarahperez">https://about.me/sarahperez</a>');
      expect(util.linkify('fail.wtf')).toBe(
        '<a target="_blank" rel="nofollow" href="http://fail.wtf">fail.wtf</a>');
      expect(util.linkify('http://fail.wtf/')).toBe(
        '<a target="_blank" rel="nofollow" href="http://fail.wtf/">http://fail.wtf/</a>');
      expect(util.linkify('lung.cancerresearch/news')).toBe(
        '<a target="_blank" rel="nofollow" href="http://lung.cancerresearch/news">lung.cancerresearch/news</a>');
      expect(util.linkify('https://lung.cancerresearch/news/')).toBe(
        '<a target="_blank" rel="nofollow" href="https://lung.cancerresearch/news/">https://lung.cancerresearch/news/</a>');
      expect(util.linkify('王府半島酒店.中國')).toBe(
        '王府半島酒店.中國');  // being conservative, not detected
      expect(util.linkify('http://王府半島酒店.中國')).toBe(
        '<a target="_blank" rel="nofollow" href="http://王府半島酒店.中國">http://王府半島酒店.中國</a>');
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
