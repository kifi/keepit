function attachComposeBindings($c) {
  var $d = $c.find(".kifi-compose-draft");
  var $p = $d.find(".kifi-placeholder");
  $d.focus(function() {
    if ($p[0].parentNode) {
      $p.detach();
      // detaching destroys the selection if it was in the placeholder
      var sel = window.getSelection();
      if (!sel.rangeCount) {
        var r = document.createRange();
        r.selectNodeContents(this);
        sel.addRange(r);
      }
    }
  }).blur(function() {
    // wkb.ug/112854 crbug.com/222546
    $("<input style=position:fixed;top:999%>").appendTo("html").each(function() {this.setSelectionRange(0,0)}).remove();

    if (!convertDraftToText($(this).html())) {
      $d.empty().append($p);
    }
  });
}
