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

});
