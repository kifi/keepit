function attachComposeBindings($c, composeTypeName) {
  var $f = $c.find(".kifi-compose");
  var $t = $f.find(".kifi-compose-to");
  var $d = $f.find(".kifi-compose-draft");
  var defaultText = $d.data("default");

  $d.focus(function() {
    if ($f.hasClass("kifi-empty")) {  // webkit workaround (can ditch when Chrome 27/28 ? goes stable)
      this.textContent = "\u200b";  // zero-width space
      var r = document.createRange();
      r.selectNodeContents(this);
      r.collapse(false);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
    } else if (defaultText && $d.text() == defaultText) {
      var r = document.createRange();
      r.selectNodeContents(this);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
      $(this).data("preventNextMouseUp", true); // mouseup clears selection
    }
    $c.css("overflow", "hidden");
  }).blur(function() {
    // wkb.ug/112854 crbug.com/222546
    $("<input style=position:fixed;top:999%>").appendTo("html").each(function() {this.setSelectionRange(0,0)}).remove();

    if (!convertDraftToText($d.html())) {
      if (defaultText && $t.tokenInput("get").length) {
        $f.removeClass("kifi-empty");
        $d.text(defaultText);
      } else {
        $d.empty();
        $f.addClass("kifi-empty");
      }
    }
  }).mousedown(function() {
    $(this).removeData("preventNextMouseUp");
  }).click(function() {
    this.focus();  // needed in Firefox for clicks on ::before placeholder text
  }).mouseup(function(e) {
    if ($(this).data("preventNextMouseUp")) {
      $(this).removeData("preventNextMouseUp");
      e.preventDefault();
    }
  }).keydown(function(e) {
    if (e.which == 13 && (e.metaKey || e.ctrlKey)) { // ⌘-Enter
      $f.submit();
    }
  }).on("input", function() {
    updateMaxHeight();
    $f[0].classList[this.firstElementChild === this.lastElementChild && !this.textContent ? "add" : "remove"]("kifi-empty");
  }).on("transitionend webkitTransitionEnd", function() {
    updateMaxHeight();
    $c.css("overflow", "");
  });

  if ($t.length) {
    api.port.emit("get_friends", function(friends) {
      api.log("friends:", friends);
      friends.forEach(function(f) {
        f.name = f.firstName + " " + f.lastName;
      });
      $t.tokenInput(friends, {
        searchDelay: 0,
        minChars: 2,
        placeholder: "To",
        hintText: "",
        noResultsText: "",
        searchingText: "",
        animateDropdown: false,
        preventDuplicates: true,
        allowTabOut: true,
        tokenValue: "id",
        theme: "KiFi",
        zindex: 999999999992,
        onAdd: function() {
          if (defaultText && !$d.text()) {
            $f.removeClass("kifi-empty");
            $d.text(defaultText);
          }
        },
        onDelete: function() {
          if (defaultText && !$t.tokenInput("get").length && $d.text() == defaultText) {
            $d.empty();
            $f.addClass("kifi-empty");
          }
        }});
      $t.data("friends", friends);
    });
  }

  $f.submit(function(e) {
    e.preventDefault();
    var text;
    if ($f.hasClass("kifi-empty") || !(text = convertDraftToText($d.html()))) {
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
      args.push(recipients.map(function(r) {return r.id}));
    }
    $d.empty().trigger("kifi:compose-submit", args).focus();
    $f.addClass("kifi-empty");
    var $submit = $f.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
  })
  .bindHover(".kifi-compose-snapshot", function(configureHover) {
    render("html/keeper/titled_tip.html", {
      title: "Microfind",
      html: "Click to mark something on<br>the page and reference it in<br>your " + composeTypeName + "."
    }, function(html) {
      configureHover(html, {
        showDelay: 500,
        click: "hide",
        position: function(w) {
          this.style.left = 21 - w / 2 + "px";
        }});
    });
  })
  .on("click", ".kifi-compose-snapshot", function() {
    snapshot.take(composeTypeName, function(selector) {
      if (selector) {
        $d.append(" <a href='x-kifi-sel:" + selector.replace("'", "&#39;") + "'>look here</a>");
      }
      $f.removeClass("kifi-empty");
      $d.focus();  // TODO: preserve insertion point & selection
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
  $(elAbove).scrollable().layout();
  updateMaxHeight();

  $(window).on("resize", updateMaxHeight);

  $c.closest(".kifi-pane-box")
  .on("kifi:shown", setFocus)
  .on("kifi:remove", function() {
    $(window).off("resize", updateMaxHeight);
    if ($t.length) {
      $t.tokenInput("destroy");
    }
  });

  function setFocus() {
    api.log("[setFocus]");
    if ($t.length) {
      if (!$f.find("#token-input-kifi-compose-to").focus().length) {
        setTimeout(setFocus, 100);
      }
    } else {
      $d.focus();
    }
  }

  function updateMaxHeight() {
    var hNew = Math.max(0, $c[0].offsetHeight - $f[0].offsetHeight);
    if (hNew != hOld) {
      api.log("[updateMaxHeight]", hOld, "->", hNew);
      var scrollTop = elAbove.scrollTop;
      elAbove.style.maxHeight = hNew + "px";
      if (hOld) {
        elAbove.scrollTop = Math.max(0, scrollTop + hOld - hNew);
      } else {
        elAbove.scrollTop = 99999;
      }
      hOld = hNew;
    }
  }
}
