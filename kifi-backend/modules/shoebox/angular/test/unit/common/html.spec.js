'use strict';

describe('HTML', function () {

  beforeEach(module('HTML'));

  var HTML;
  beforeEach(inject(function (_HTML_) {
    HTML = _HTML_;
  }));

  describe('HTML.escapeElementContent', function () {
    it('escapes ampersand and less-than', function () {
      expect(HTML.escapeElementContent(null)).toBe('');
      expect(HTML.escapeElementContent(2)).toBe('2');
      expect(HTML.escapeElementContent('')).toBe('');
      expect(HTML.escapeElementContent('hi')).toBe('hi');
      expect(HTML.escapeElementContent('3 < 4')).toBe('3 &lt; 4');
      expect(HTML.escapeElementContent('3 & 4')).toBe('3 &amp; 4');
      expect(HTML.escapeElementContent('<script>post(document.cookie) && alert("gotcha!")</script>')).toBe(
        '&lt;script>post(document.cookie) &amp;&amp; alert("gotcha!")&lt;/script>');
    });
  });

  describe('HTML.escapeDoubleQuotedAttr', function () {
    it('escapes double-quotes', function () {
      expect(HTML.escapeDoubleQuotedAttr(null)).toBe('');
      expect(HTML.escapeDoubleQuotedAttr(2)).toBe('2');
      expect(HTML.escapeDoubleQuotedAttr('')).toBe('');
      expect(HTML.escapeDoubleQuotedAttr('hi')).toBe('hi');
      expect(HTML.escapeDoubleQuotedAttr('3 < 4')).toBe('3 < 4');
      expect(HTML.escapeDoubleQuotedAttr('3 & 4')).toBe('3 & 4');
      expect(HTML.escapeDoubleQuotedAttr('<script>post(document.cookie) && alert("gotcha!")</script>')).toBe(
        '<script>post(document.cookie) && alert(&quot;gotcha!&quot;)</script>');
    });
  });
});
