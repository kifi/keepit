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
  var $scroller = $(), $holder = $();
  return {
    render: function($container, comments, session) {
      comments.forEach(function(c) {
        c.isLoggedInUser = c.user.id == session.userId;
      });
      render("html/metro/comments.html", {
        formatComment: getTextFormatter,
        formatLocalDate: getLocalDateFormatter,
        emptyUri: api.url("images/metro/bg_comments.png"),
        comments: comments,
        draftPlaceholder: "Type a comment…",
        submitButtonLabel: "Post",
        submitTip: CO_KEY + "-Enter to post",
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
        .on("kifi:compose-submit", submitComment.bind(null, $container, session))
        .find("time").timeago();

        $scroller = $container.find(".kifi-comments-posted");
        $holder = $scroller.find(".kifi-comments-posted-inner");

        if (~session.experiments.indexOf("admin")) {
          $holder.on("mouseenter", ".kifi-comment-posted", function() {
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
                  updateEmptyMessage();
                });
              });
            } else {
              $x.addClass("kifi-confirm");
              setTimeout($x.removeClass.bind($x, "kifi-confirm"), 1000);
            }
          });
        }

        attachComposeBindings($container, "comment");

        $container.closest(".kifi-pane-box").on("kifi:remove", function() {
          $scroller = $holder = $();
        });

        if (comments.length) emitRead(comments[comments.length - 1]);
      });
    },
    update: function(comment, userId) {
      if ($scroller.length) {
        comment.isLoggedInUser = comment.user.id == userId;
        renderComment(comment, function($c) {
          var $old;
          if (comment.isLoggedInUser && ($old = $holder.find(".kifi-comment-posted[data-id=]").first()).length) {
            $old.replaceWith($c);
          } else {
            $holder.append($c);  // should we compare timestamps and insert in order?
            $scroller.scrollToBottom();
            updateEmptyMessage();
          }
          emitRead(comment);
        });
      }
    }};

  function submitComment($container, session, e, text) {
    logEvent("slider", "comment");
    api.port.emit("post_comment", {
        url: document.URL,
        title: document.title,
        text: text},
      function(resp) {
        api.log("[submitComment] resp:", resp);
      });
    renderComment({
        createdAt: new Date().toISOString(),
        text: text,
        user: {
          id: session.userId,
          firstName: session.name,
          lastName: ""},
        isLoggedInUser: true,
        id: ""},
      function($c) {
        $holder.append($c);
        $scroller.scrollToBottom();
        updateEmptyMessage();
      });
  }

  function renderComment(c, callback) {
    c.formatComment = getTextFormatter;
    c.formatLocalDate = getLocalDateFormatter;
    render("html/metro/comment.html", c, function(html) {
      callback($(html).find("time").timeago().end());
    });
  }

  function updateEmptyMessage() {
    var any = $holder.find(".kifi-comment-posted").length > 0;
    $scroller.closest(".kifi-pane-box").find(".kifi-pane-empty").toggleClass("kifi-hidden", any);
  }

  function emitRead(c) {
    api.port.emit("set_comment_read", {id: c.id, time: c.createdAt});
  }
}();
