function attachComposeBindings($f, composeTypeName) {
  var $d = $f.find(".kifi-compose-draft");
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
  }).keydown(function(e) {
    if (e.which == 13 && e.metaKey) { // ⌘-Enter
      $f.submit();
    }
  });
  $f.on("click", ".kifi-compose-submit", function() {
    $f.submit();
  })
  .submit(function(e) {
    e.preventDefault();
    if (!$p[0].parentNode) {  // placeholder detached
      $d.trigger("kifi:compose-submit", [convertDraftToText($d.html())]);
    }
  })
  .on("click", ".kifi-compose-snapshot", function() {
    snapshot.take(composeTypeName, function(selector) {
      if (selector) {
        $d.append(" <a href='x-kifi-sel:" + selector.replace("'", "&#39;") + "'>look here</a>");
      }
      $d.focus();
      var r = document.createRange();
      r.selectNodeContents($d[0]);
      r.collapse(false);
      var s = window.getSelection();
      s.removeAllRanges();
      s.addRange(r);
    });
  });
}
