// @require styles/metro/comments.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderComments($container, comments) {
  var elComments, hComments;
  render("html/metro/comments.html", {
    formatComment: getCommentTextFormatter,
    formatDate: getCommentDateFormatter,
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
    var $c = $(html).prependTo($container);
    elComments = $c[0].querySelector(".kifi-comments-posted");
    updateMinHeight();
    elComments.scrollTop = 9999;

    $c.find("time").timeago();
    $c.on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    });

    attachComposeBindings($c);
    $c.find(".kifi-compose-draft")
    .on("input", updateMinHeight)
    .on("kifi:compose-submit", submitComment);

    $(window).on("resize", updateMinHeight);
  });

  $container.closest(".kifi-pane-box").on("kifi:remove", function() {
    $(window).off("resize", updateMinHeight);
  });

  function updateMinHeight() {
    if (elComments) {
      var hCommentsNew = Math.max(0, $container[0].offsetHeight - elComments.nextElementSibling.offsetHeight);
      if (hCommentsNew != hComments) {
        api.log("[comments:updateMinHeight]", hComments, "→", hCommentsNew);
        var scrollTop = elComments.scrollTop;
        elComments.style.maxHeight = hCommentsNew + "px";
        if (hComments != null) {
          elComments.scrollTop = Math.max(0, scrollTop + hComments - hCommentsNew);
        }
        hComments = hCommentsNew;
      }
    }
  }

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
        "formatComment": getCommentTextFormatter,
        "formatDate": getCommentDateFormatter,
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
    var $submit = $container.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
  }
}
