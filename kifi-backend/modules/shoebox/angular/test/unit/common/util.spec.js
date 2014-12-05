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

  describe('util.isYoutube', function () {
    it('correctly extracts youtube videos', function () {
      expect(util.isYoutubeUrl('http://www.youtube.com/watch?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://www.youtube.com/v/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://www.youtube.com/e/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://www.youtube.com/embed/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://www.youtube.com/?v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://www.youtube.com/user/IngridMichaelsonVEVO#p/u/11/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('http://youtu.be/dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('https://www.youtube-nocookie.com/v/dQw4w9WgXcQ?version=3&hl=en_US&rel=0')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('https://www.youtube.com/watch?feature=player_embedded&v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('https://www.youtube.com/?feature=player_embedded&v=dQw4w9WgXcQ')).toBe('dQw4w9WgXcQ');
      expect(util.isYoutubeUrl('https://www.youtube.com/watch?v=zlz-WOglHgo')).toBe('zlz-WOglHgo'); // test dashes in youtubeId
      expect(util.isYoutubeUrl('https://www.youtube.com/watch?v=dYK_Gqyf48Y')).toBe('dYK_Gqyf48Y'); // test underscores in youtubeId
      expect(util.isYoutubeUrl('https://www.google.com')).toBe(null);
      expect(util.isYoutubeUrl('https://www.foursquare.com/v/dQw4w9WgXcQ')).toBe(null);
      expect(util.isYoutubeUrl('http://youtu.be/dQw4w9WgXcQ1')).toBe(null); // invalid youtube Id
    });
  });

});
