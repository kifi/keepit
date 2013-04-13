// @require styles/metro/comments.css
// @require styles/metro/compose.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

commentsPane = function() {
  var $posted = $();
  return {
    render: function($container, comments, userId, isAdmin) {
      comments.forEach(function(c) {
        c.isLoggedInUser = c.user.id == userId;
      });
  // ---> TODO: indent properly (postponed to make code review easier)
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
    .on("kifi:compose-submit", submitComment.bind(null, $container))
    .find("time").timeago();

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

    $posted = $container.find(".kifi-comments-posted");
    $container.closest(".kifi-pane-box").on("kifi:remove", function() {
      $posted.length = 0;
    });
  });
  // --->
    },
    update: function(comment, userId) {
      if (!$posted.length) return;
      comment.isLoggedInUser = comment.user.id == userId;
      renderComment(comment, function($c) {
        $posted.append($c).layout()[0].scrollTop = 99999;  // should we compare timestamps and insert in order?
      });
    }};

  function submitComment($container, e, text) {
    logEvent("slider", "comment");
    api.port.emit("post_comment", {
      "url": document.URL,
      "title": document.title,
      "text": text,
      "permissions": "public"
    }, function(response) {
      api.log("[submitComment] resp:", response);
      renderComment({
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
      }, function($c) {
        $posted.append($c).layout()[0].scrollTop = 99999;
        $container.find(".kifi-compose-draft").empty().blur();
      });
    });
  }

  function renderComment(c, callback) {
    c.formatComment = getTextFormatter;
    c.formatLocalDate = getLocalDateFormatter;
    c.formatIsoDate = getIsoDateFormatter;
    render("html/metro/comment.html", c, function(html) {
      callback($(html).find("time").timeago().end());
    });
  }
}();
