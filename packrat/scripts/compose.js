function attachComposeBindings($c, composeTypeName) {
  var $f = $c.find(".kifi-compose");
  var $t = $f.find(".kifi-compose-to");
  var $d = $f.find(".kifi-compose-draft");
  var $p = $d.find(".kifi-placeholder");

  $d.focus(function() {  // TODO: reinstate pull request 1292 after Chrome 28 reaches Stable channel
    if ($p[0].parentNode) {
      $p.detach();
      // detaching destroys the selection if it was in the placeholder
      var r = document.createRange();
      r.selectNodeContents(this);
      r.collapse(false);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
    }
  }).blur(function() {
    // wkb.ug/112854 crbug.com/222546
    $("<input style=position:fixed;top:999%>").appendTo("html").each(function() {this.setSelectionRange(0,0)}).remove();

    if (!convertDraftToText($d.html())) {
      $d.empty().append($p);
    }
  }).keydown(function(e) {
    if (e.which == 13 && e.metaKey) { // ⌘-Enter
      $f.submit();
    }
  }).on("input", function() {
    updateMaxHeight();
    this.classList[this.firstElementChild === this.lastElementChild && !this.textContent ? "add" : "remove"]("kifi-empty");
  });

  if ($t.length) {
    api.port.emit("get_friends", function(o) {
      api.log("friends:", o);
      o.friends.forEach(function(f) {
        f.name = f.firstName + " " + f.lastName;
      });
      $t.tokenInput(o.friends, {
        searchDelay: 0,
        minChars: 2,
        placeholder: "To",
        hintText: "",
        noResultsText: "",
        searchingText: "",
        animateDropdown: false,
        preventDuplicates: true,
        allowTabOut: true,
        tokenValue: "externalId",
        theme: "KiFi",
        zindex: 2147483641});
      $("#token-input-kifi-compose-to").focus();
    });
  } else {
    $d.focus();
  }

  $f.submit(function(e) {
    e.preventDefault();
    var text;
    if ($p[0].parentNode || !(text = convertDraftToText($d.html()))) {
      $d.focus();
      return;
    }
    var args = [text];
    if ($t.length) {
      var recipients = $t.tokenInput("get");
      if (!recipients.length) {
        $f.find("#token-input-kifi-compose-to").focus();
        return;
      }
      args.push(recipients.map(function(r) {return r.externalId}).join(","));
    }
    $d.trigger("kifi:compose-submit", args);
    var $submit = $f.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
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
  })
  .find(".kifi-compose-submit")
  .click(function() {
    $f.submit();
  })
  .keypress(function(e) {
    if (e.which == 32) {
      $f.submit();
    }
  });

  var hOld, elAbove = $f[0].previousElementSibling;
  updateMaxHeight();

  $(window).on("resize", updateMaxHeight);

  $c.closest(".kifi-pane-box").on("kifi:remove", function() {
    $(window).off("resize", updateMaxHeight);
  });

  function updateMaxHeight() {
    var hNew = Math.max(0, $c[0].offsetHeight - $f[0].offsetHeight);
    if (hNew != hOld) {
      api.log("[updateMaxHeight]", hOld, "→", hNew);
      var scrollTop = elAbove.scrollTop;
      elAbove.style.maxHeight = hNew + "px";
      elAbove.scrollTop = hOld == null ? 9999 : Math.max(0, scrollTop + hOld - hNew);
      hOld = hNew;
    }
  }
}
