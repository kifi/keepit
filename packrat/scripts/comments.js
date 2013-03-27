// @require styles/metro/comments.css
// @require scripts/lib/jquery.timeago.js
// @require scripts/api.js
// @require scripts/formatting.js
// @require scripts/look.js
// @require scripts/render.js
// @require scripts/compose.js
// @require scripts/snapshot.js

function renderComments($container, comments) {
  var postedEl;
  render("html/metro/comments.html", {
    // kifiuser: {
    //   "firstName": session.name,
    //   "lastName": "",
    //   "avatar": session.avatarUrl
    // },
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
    postedEl = $c[0].querySelector(".kifi-comments-posted");
    updateMinHeight();

    $c.on("mousedown", "a[href^='x-kifi-sel:']", lookMouseDown)
    .on("click", "a[href^='x-kifi-sel:']", function(e) {
      e.preventDefault();
    });

    attachComposeBindings($c);
    $c.find("time").timeago();
    $c.find(".kifi-compose-draft").on("input", updateMinHeight);
    $(window).on("resize", updateMinHeight);
  });

  $container.closest(".kifi-pane-box").on("kifi:remove", function() {
    $(window).off("resize", updateMinHeight);
  });

  function updateMinHeight() {
    api.log("[comments:updateMinHeight]");
    if (postedEl) {
      postedEl.style.maxHeight = Math.max(0, $container[0].offsetHeight - postedEl.nextElementSibling.offsetHeight) + "px";
    }
  }
}
