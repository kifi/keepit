// see https://developer.mozilla.org/en-US/docs/Places_Developer_Guide
/*jshint globalstrict:true */
'use strict';

var { Cc, Ci } = require('chrome')
var bookmarks = Cc["@mozilla.org/browser/nav-bookmarks-service;1"].getService(Ci.nsINavBookmarksService);
var history = Cc["@mozilla.org/browser/nav-history-service;1"].getService(Ci.nsINavHistoryService);

function identity(x) {
  return x;
}

exports.getAll = function(callback) {
  var systemFolderIds = [
    bookmarks.placesRoot,
    bookmarks.bookmarksMenuFolder,
    bookmarks.toolbarFolder,
    bookmarks.unfiledBookmarksFolder,
  ];

  var query = history.getNewQuery();
  query.setFolders([bookmarks.placesRoot], 1);
  var root = history.executeQuery(query, history.getNewQueryOptions()).root;

  var arr = [], path, httpRe = /^https?:/, commaRe = /\s*(?:,\s*)+/;

  !function traverse(node) {
    switch (node.type) {
      case node.RESULT_TYPE_FOLDER:
        if (node.itemId !== bookmarks.tagsFolder && !(node.title === 'Mozilla Firefox' && node.parent.itemId === bookmarks.bookmarksMenuFolder)) {
          node.QueryInterface(Ci.nsINavHistoryContainerResultNode);
          node.containerOpen = true;

          var name = systemFolderIds.indexOf(node.itemId) < 0 && node.title.trim();
          if (name) {
            path = path ? path.concat([name]) : [name];
          }
          for (let i = 0, n = node.childCount; i < n; ++i) {
            traverse(node.getChild(i));
          }
          if (name) {
            path = path.length > 1 ? path.slice(0, -1) : undefined;
          }
        }
        break;
      case node.RESULT_TYPE_URI:
        if (httpRe.test(node.uri) && !(node.uri === 'https://www.mozilla.org/en-US/firefox/central/' && node.parent.itemId === bookmarks.toolbarFolder)) {
          var tags = (node.tags || '').trim();
          tags = tags && tags.split(commaRe).filter(identity);
          arr.push({
            title: node.title,
            url: node.uri,
            addedAt: Math.round(node.dateAdded / 1000),
            path: path,
            tags: tags.length ? tags : undefined
          });
        }
        break;
    }
  }(root);

  callback(arr);
};
