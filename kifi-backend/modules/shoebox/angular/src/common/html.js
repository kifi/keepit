'use strict';

angular.module('HTML', [])

.constant('HTML', (function () {
  var ALL_QUOTES = /"/g;
  var ALL_AMP_AND_LT = /[&<]/g;

  var REPLACEMENTS = {
    '&': '&amp;',
    '<': '&lt;'
  };

  function replace(ch) {
    return REPLACEMENTS[ch];
  }

  return {
    escapeElementContent: function (text) {
      // www.owasp.org/index.php/XSS_Experimental_Minimal_Encoding_Rules
      return text == null ? '' : String(text).replace(ALL_AMP_AND_LT, replace);
    },

    escapeDoubleQuotedAttr: function (text) {
      // Rule #2 at www.owasp.org/index.php/XSS_(Cross_Site_Scripting)_Prevention_Cheat_Sheet states
      // "Properly quoted attributes can only be escaped with the corresponding quote."
      // Caller must ensure text is safe in its specific attribute context (e.g. href, onload).
      return text == null ? '' : String(text).replace(ALL_QUOTES, '&quot;');
    }
  };
}()));
