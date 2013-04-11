// @require styles/metro/comments.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderComments($container, comments, isAdmin) {
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
    var $posted = $(html).prependTo($container)
    .on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    })
    .on("kifi:compose-submit", submitComment)
    .find(".kifi-comments-posted");

    $posted.find("time").timeago();
    if (isAdmin) {
      $posted.on("mouseenter", ".kifi-comment-posted", function() {
        if (this.lastChild.className != "kifi-comment-x") {
          $(this).append("<div class=kifi-comment-x>");
        }
      }).on("click", ".kifi-comment-x", function() {
        var $x = $(this);
        if ($x.hasClass("kifi-confirm")) {
          $x.addClass("kifi-confirmed");
          var id = $x.parent().animate({opacity: .1}, 400).data("id");
          api.log("[deleteComment]", id);
          api.port.emit("delete_comment", id, function() {
            $x.parent().slideUp(function() {
              $(this).remove();
            });
          });
        } else {
          $x.addClass("kifi-confirm");
          setTimeout($x.removeClass.bind($x, "kifi-confirm"), 1000);
        }
      });
    }

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
          "id": response.session.userId,
          "firstName": response.session.name,
          "lastName": "",
          "facebookId": response.session.facebookId
        },
        "isLoggedInUser": true,
        "id": response.commentId
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
