// @require scripts/api.js
// @require scripts/render.js

generalPane = {
  render: function($box, params) {
    // $box.on("click", ".kifi-pane-action", function() {});

    render("html/metro/general.html", params, function(html) {
      $box.find(".kifi-pane-actions").before(html);
    });

    if ($box.data("shown")) {
      setFocus($box);
    } else {
      $box.on("kifi:shown", setFocus);
    }

    function setFocus() {
      $box.closest(".kifi-pane").find(".kifi-pane-search").focus();
    }
  }};
