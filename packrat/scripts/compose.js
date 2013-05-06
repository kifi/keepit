function attachComposeBindings($c, composeTypeName) {
  var $f = $c.find(".kifi-compose");
  var $t = $f.find(".kifi-compose-to");
  var $d = $f.find(".kifi-compose-draft");
  var defaultText = $d.data("default");

  $d.focus(function() {
    if (this.classList.contains("kifi-empty")) {  // webkit workaround (can ditch when Chrome 27/28 ? goes stable)
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
        $d.removeClass("kifi-empty").text(defaultText);
      } else {
        $d.empty().addClass("kifi-empty");
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
    this.classList[this.firstElementChild === this.lastElementChild && !this.textContent ? "add" : "remove"]("kifi-empty");
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
        zindex: 2147483641,
        onAdd: function() {
          if (defaultText && !$d.text()) {
            $d.removeClass("kifi-empty").text(defaultText);
          }
        },
        onDelete: function() {
          if (defaultText && !$t.tokenInput("get").length && $d.text() == defaultText) {
            $d.empty().addClass("kifi-empty");
          }
        }});
      $t.data("friends", friends);
    });
  }

  $f.submit(function(e) {
    e.preventDefault();
    var text;
    if ($d.hasClass("kifi-empty") || !(text = convertDraftToText($d.html()))) {
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
    var $submit = $f.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
  })
  .on("click", ".kifi-compose-snapshot", function() {
    snapshot.take(composeTypeName, function(selector) {
      if (selector) {
        $d.append(" <a href='x-kifi-sel:" + selector.replace("'", "&#39;") + "'>look here</a>");
      }
      $d.removeClass("kifi-empty").focus();  // TODO: preserve insertion point & selection
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
  elAbove.clientHeight, updateMaxHeight();

  $(window).on("resize", updateMaxHeight);

  $c.closest(".kifi-pane-box")
  .on("kifi:shown", setFocus)
  .on("kifi:remove", function() {
    $(window).off("resize", updateMaxHeight);
  }).each(function() {
    if ($(this).data("shown")) {
      setFocus();
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
        $(elAbove).scrollToBottom();
      }
      hOld = hNew;
    }
  }
}
