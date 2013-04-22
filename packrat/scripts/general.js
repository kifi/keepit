// @require scripts/api.js

generalPane = {
  render: function($box, o) {
    // $box.on("click", ".kifi-pane-action", function() {});

    if ($box.data("shown")) {
      setFocus($box);
    } else {
      $box.on("kifi:shown", setFocus);
    }

    function setFocus() {
      $box.closest(".kifi-pane").find(".kifi-pane-search").focus();
    }
  }};
