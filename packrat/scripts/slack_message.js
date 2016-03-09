// @require scripts/emoji.js

var slackFormat = (function () {
  'use strict';

  function stripEntityBrackets(message) { // Converts <@username> and <#channel> to @username and #channel
    return message.replace(/<([\@\#]\w\S*?)>/g, '$1');
  }

  // Converts link markup to plain text. If `emptyIfOnlyEntity` and the entire message is an entity, returns ''
  function flattenLinks(message) {
    return message.replace(/<(\S*?)\|(.*?)>|<(\S*?)>/g, function (match, href, linkText, onlyHref) {
        var text = onlyHref || linkText;
        text = text.length < 30 ? text : text.slice(0, 25) + '…';
        return text;
    });
  }

  // Returns whether message is likely worth displaying
  function isSubstantial(message) {
    var noEntities = message.replace(/<(\S*?)\|(.*?)>|<(\S*?)>\s?/g, '').trim();
    var emojied = noEntities.length > 4 ? emoji.decode(noEntities) : noEntities;
    return emojied.length > 4;
  }


  return {
    plain: function (message, emptyIfInsignificant) {
      if (emptyIfInsignificant && !isSubstantial(message)) {
        return '';
      } else {
        return emoji.decode(flattenLinks(stripEntityBrackets(message)));
      }
    }
    // todo: html(message), returns HTML that makes clickable things clickable
  };
})();
