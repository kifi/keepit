// @require scripts/scrollable.js

function attachComposeBindings($c, composeTypeName, enterToSend) {
  var $f = $c.find(".kifi-compose");
  var $t = $f.find(".kifi-compose-to");
  var $d = $f.find(".kifi-compose-draft");
  var defaultText = $d.data("default");  // real text, not placeholder

  $d.focus(function() {
    var r, sel = window.getSelection();
    if ($f.hasClass("kifi-empty")) {  // webkit workaround (can ditch when Chrome 27/28 ? goes stable)
      this.textContent = "\u200b";  // zero-width space
      r = document.createRange();
      r.selectNodeContents(this);
      r.collapse(false);
      sel.removeAllRanges();
      sel.addRange(r);
    } else if (defaultText && $d.text() == defaultText) {
      // select default text for easy replacement
      r = document.createRange();
      r.selectNodeContents(this);
      sel.removeAllRanges();
      sel.addRange(r);
      $(this).data("preventNextMouseUp", true); // to avoid clearing selection
    } else if (r = $d.data("sel")) {
      // restore previous selection
      sel.removeAllRanges();
      sel.addRange(r);
    }
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
    $d.removeData("preventNextMouseUp");
  }).click(function() {
    this.focus();  // needed in Firefox for clicks on ::before placeholder text
  }).mouseup(function(e) {
    $d.data("sel", window.getSelection().getRangeAt(0));

    if ($d.data("preventNextMouseUp")) {
      $d.removeData("preventNextMouseUp");
      e.preventDefault();
    }
  }).keyup(function() {
    $d.data("sel", window.getSelection().getRangeAt(0));
  }).on("input", function() {
    updateMaxHeight();
    $f[0].classList[this.firstElementChild === this.lastElementChild && !this.textContent ? "add" : "remove"]("kifi-empty");
  }).on("transitionend", function() {
    updateMaxHeight();
  }).on("paste", function(e) {
    var cd = e.originalEvent.clipboardData;
    if (cd) {
      e.preventDefault();
      document.execCommand("insertText", false, cd.getData("text/plain"));
    }
  });

  if ($t.length) {
    $t.tokenInput({}, {
      searchDelay: 0,
      minChars: 1,
      placeholder: "To",
      hintText: "",
      noResultsText: "",
      searchingText: "",
      animateDropdown: false,
      resultsLimit: 4,
      preventDuplicates: true,
      allowTabOut: true,
      tokenValue: "id",
      theme: "Kifi",
      zindex: 999999999992,
      resultsFormatter: function(f) {
        return "<li style='background-image:url(//" + cdnBase + "/users/" + f.id + "/pics/100/0.jpg)'>" +
          Mustache.escape(f.name) + "</li>";
      },
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
    api.port.emit("get_friends", function(friends) {
      friends.forEach(function(f) {
        f.name = f.firstName + " " + f.lastName;
      });
      $t.data("settings").local_data = friends;
      $t.data("friends", friends);
    });
  }

  $f.keydown(function(e) {
    if (e.which == 13 && !e.shiftKey && !e.altKey && !enterToSend == (e.metaKey || e.ctrlKey)) {
      e.preventDefault();
      $f.submit();
    }
  }).submit(function(e) {
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
    $d.trigger("kifi:compose-submit", args).empty().focus().triggerHandler("input");
    var $submit = $f.find(".kifi-compose-submit").addClass("kifi-active");
    setTimeout($submit.removeClass.bind($submit, "kifi-active"), 10);
  })
  .bindHover(".kifi-compose-snapshot", function(configureHover) {
    render("html/keeper/titled_tip", {
      title: "Microfind",
      html: "Click to mark something on<br>the page and reference it in<br>your " + composeTypeName + "."
    }, function(html) {
      configureHover(html, {
        mustHoverFor: 500,
        hideAfter: 3000,
        click: "hide",
        position: function(w) {
          this.style.left = 21 - w / 2 + "px";
        }});
    });
  })
  .on("click", ".kifi-compose-snapshot", function() {
    snapshot.take(composeTypeName, function(selector) {
      $d.focus();
      if (!selector) return;
      $f.removeClass("kifi-empty");

      // insert link
      var r = $d.data("sel"), $a = $("<a>", {href: "x-kifi-sel:" + selector, text: "look\u00A0here"}), pad = true;
      if (r && r.startContainer === r.endContainer && !$(r.endContainer).closest("a").length) {
        var par = r.endContainer, i = r.startOffset, j = r.endOffset;
        if (par.nodeType == 3) {  // text
          var s = par.textContent;
          if (i < j) {
            $a.text(s.substring(i, j));
            pad = false;
          }
          $(par).replaceWith($a);
          $a.before(s.substr(0, i))
          $a.after(s.substr(j));
        } else if (i == j || !r.cloneContents().querySelector("a")) {
          var next = par.childNodes.item(j);
          if (i < j) {
            $a.empty().append(r.extractContents());
            pad = false;
          }
          par.insertBefore($a[0], next);
        }
      }
      if (!$a[0].parentNode) {
        $d.append($a);
      }

      if (pad) {
        var sib;
        if ((sib = $a[0].previousSibling) && (sib.nodeType != 3 || !/[\s\u200b]$/.test(sib.nodeValue))) {
          $a.before(" ");
        }
        if ((sib = $a[0].nextSibling) && (sib.nodeType != 3 || /^\S/.test(sib.nodeValue))) {
          $a.after(" ");
        }
      }

      // position caret immediately after link
      r = r || document.createRange();
      r.setStartAfter($a[0]);
      r.collapse(true);
      var sel = window.getSelection();
      sel.removeAllRanges();
      sel.addRange(r);
    });
  })
  .on("mousedown", ".kifi-compose-tip", function(e) {
    e.preventDefault();
    var prefix = CO_KEY + "-";
    var $tip = $(this), tipTextNode = this.firstChild;
    var $alt = $("<span class=kifi-compose-tip-alt>")
      .text((enterToSend ? prefix : "") + tipTextNode.nodeValue.replace(prefix, ""))
      .css({"min-width": $tip.outerWidth(), "visibility": "hidden"})
      .hover(
        function() { $alt.addClass("kifi-hover"); },
        function() { $alt.removeClass("kifi-hover"); });
    var $menu = $("<span class=kifi-compose-tip-menu>").append($alt).insertAfter($tip);
    $tip.css("min-width", $alt.outerWidth()).addClass("kifi-active");
    $alt.css("visibility", "").mouseup(hide.bind(null, true));
    document.addEventListener("mousedown", docMouseDown, true);
    function docMouseDown(e) {
      hide($alt[0].contains(e.target));
      if ($tip[0].contains(e.target)) {
        e.stopPropagation();
      }
      e.preventDefault();
    }
    function hide(toggle) {
      document.removeEventListener("mousedown", docMouseDown, true);
      $tip.removeClass("kifi-active");
      $menu.remove();
      if (toggle) {
        enterToSend = !enterToSend;
        api.log("[enterToSend]", enterToSend);
        tipTextNode.nodeValue = enterToSend ? tipTextNode.nodeValue.replace(prefix, "") : prefix + tipTextNode.nodeValue;
        api.port.emit("set_enter_to_send", enterToSend);
      }
    }
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
  var elScroll = $(elAbove).find(".kifi-scroll-inner").scrollable({
    $above: $c.closest(".kifi-pane-box").find(".kifi-pane-title,.kifi-thread-who").last(),
    $below: $f
  })[0];
  $(elAbove).layout();
  updateMaxHeight();

  $(window).on("resize", updateMaxHeight);

  var $box = $c.closest(".kifi-pane-box")
  if ($box.data("shown")) {
    setFocus();
  } else {
    $box.on("kifi:shown", setFocus);
  }
  $box.on("kifi:remove", function() {
    $(window).off("resize", updateMaxHeight);
    if ($t.length) {
      $t.tokenInput("destroy");
    }
  });

  function setFocus() {
    api.log("[setFocus]");
    if ($t.length) {  // timeout avoids Chrome transition displacement glitch
      setTimeout($f.focus.bind($f.find("#token-input-kifi-compose-to")));
    } else {
      $d.focus();
    }
  }

  function updateMaxHeight() {
    var hNew = Math.max(0, $c[0].offsetHeight - $f[0].offsetHeight);
    if (hNew != hOld) {
      api.log("[updateMaxHeight]", hOld, "->", hNew);
      var scrollTop = elScroll.scrollTop;
      elAbove.style.maxHeight = hNew + "px";
      if (hOld) {
        elScroll.scrollTop = Math.max(0, scrollTop + hOld - hNew);
      } else {
        elScroll.scrollTop = 99999;
      }
      hOld = hNew;
    }
  }
}
