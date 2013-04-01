// @require styles/metro/comments.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderComments($container, comments) {
  render("html/metro/comments.html", {
    formatComment: getTextFormatter,
    formatLocalDate: getLocalDateFormatter,
    formatIsoDate: getIsoDateFormatter,
    comments: comments,
    draftPlaceholder: "Type a comment…",
    submitButtonLabel: "Post",
    // following: following,
    snapshotUri: api.url("images/snapshot.png")
    // connected_networks: api.url("images/social_icons.png")
  }, {
    comment: "comment.html",
    compose: "compose.html"
  }, function(html) {
    $(html).prependTo($container)
    .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    })
    .on("kifi:compose-submit", submitComment)
    .find("time").timeago();

    attachComposeBindings($container, "comment");
  });

  function submitComment(e, text) {
    logEvent("slider", "comment");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "public"
    }, function(response) {
      api.log("[submitComment] resp:", response);
      render("html/metro/comment.html", {
        "formatComment": getTextFormatter,
        "formatLocalDate": getLocalDateFormatter,
        "formatIsoDate": getIsoDateFormatter,
        "createdAt": response.createdAt,
        "text": text,
        "user": {
          "externalId": response.session.userId,
          "firstName": response.session.name,
          "lastName": "",
          "facebookId": response.session.facebookId
        },
        "isLoggedInUser": true,
        "externalId": response.commentId
      }, function(html) {
        var $posted = $container.find(".kifi-comments-posted");
        $(html).find("time").timeago().end().appendTo($posted);
        $posted[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
        // TODO: better way to update comment counts
        $(".kifi-slider2-dock-btn.kifi-slider2-comments .kifi-count:not(.kifi-unread),#kifi-tile .kifi-count:not(.kifi-unread)").each(function() {
          this.innerHTML = 1 + (+this.innerHTML);
        });
      });
    });
  }
}
