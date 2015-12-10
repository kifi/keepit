// see https://developer.mozilla.org/en-US/Add-ons/SDK/Low-Level_APIs/places_bookmarks
/*jshint globalstrict:true */
'use strict';

var { search, TOOLBAR, MENU, UNSORTED } = require('sdk/places/bookmarks');
var httpRe = /^https?:/;

function isFirefoxDefaultBookmark(bookmark) {
  var hasDefaultTitle = (bookmark.group.title === 'Mozilla Firefox');
  var isGettingStarted = (bookmark.url === 'https://www.mozilla.org/en-US/firefox/central/' && bookmark.group.id === TOOLBAR.id);
  return hasDefaultTitle || isGettingStarted;
}

function getPath(node) {
  var systemFolderIds = [
    TOOLBAR.id,
    MENU.id,
    UNSORTED.id
  ];
  var path = [];

  node = node.group;
  while (node && systemFolderIds.indexOf(node.id) === -1) {
    path = path.concat(node.title.trim());
    node = node.group;
  }

  return path;
}

exports.getAll = function(callback) {
  search({
    query: '' // wildcard
  })
  .on('end', function (bookmarks) {
    var kifiBookmarks = bookmarks.map(function (b) {
      if (httpRe.test(b.url) && !isFirefoxDefaultBookmark(b)) {
        return {
          title: b.title,
          url: b.url,
          addedAt: Math.round(b.updated / 1000),
          path: getPath(b),
          tags: b.tags.size ? Array.from(b.tags) : undefined // tags is a Set, so we need to Array.from it
        };
      }
    }).filter(Boolean);

    callback(kifiBookmarks);
  });
};
