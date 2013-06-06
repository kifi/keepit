/*!
jQuery Plugin: Tokenizing Autocomplete Text Entry
commit f1ad56020c + pull requests 522, 529, 538, 539

Copyright (c) 2009 James Smith (http://loopj.com)
Licensed jointly under the GPL and MIT licenses,
choose which suits your project best!
*/

(function(a) {
  var H, B, z;
  function C(a) {
    return String(null === a || void 0 === a ? "" : a).replace(W, function(a) {
      return X[a]
    })
  }
  var Y = {method:"GET", queryParam:"q", searchDelay:300, minChars:1, propertyToSearch:"name", jsonContainer:null, contentType:"json", prePopulate:null, processPrePopulate:!1, hintText:"Type in a search term", noResultsText:"No results", searchingText:"Searching...", deleteText:"&times;", animateDropdown:!0, placeholder:null, theme:null, zindex:999, resultsLimit:null, enableHTML:!1, resultsFormatter:function(a) {
    a = a[this.propertyToSearch];
    return"<li>" + (this.enableHTML ? a : C(a)) + "</li>"
  }, tokenFormatter:function(a) {
    a = a[this.propertyToSearch];
    return"<li><p>" + (this.enableHTML ? a : C(a)) + "</p></li>"
  }, tokenLimit:null, tokenDelimiter:",", preventDuplicates:!1, tokenValue:"id", allowFreeTagging:!1, allowTabOut:!1, onResult:null, onCachedResult:null, onAdd:null, onFreeTaggingAdd:null, onDelete:null, onReady:null, idPrefix:"token-input-", disabled:!1}, I = {tokenList:"token-input-list", token:"token-input-token", tokenReadOnly:"token-input-token-readonly", tokenDelete:"token-input-delete-token", selectedToken:"token-input-selected-token", highlightedToken:"token-input-highlighted-token", dropdown:"token-input-dropdown",
  dropdownItem:"token-input-dropdown-item", dropdownItem2:"token-input-dropdown-item2", selectedDropdownItem:"token-input-selected-dropdown-item", inputToken:"token-input-input-token", focused:"token-input-focused", disabled:"token-input-disabled"};
  H = 0;
  B = 1;
  z = 2;
  var X = {"&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#x27;", "/":"&#x2F;"}, W = /[&<>"'\/]/g, w = {init:function(b, h) {
    var l = a.extend({}, Y, h || {});
    return this.each(function() {
      a(this).data("settings", l);
      a(this).data("tokenInputObject", new a.TokenList(this, b, l))
    })
  }, clear:function() {
    this.data("tokenInputObject").clear();
    return this
  }, add:function(a) {
    this.data("tokenInputObject").add(a);
    return this
  }, remove:function(a) {
    this.data("tokenInputObject").remove(a);
    return this
  }, get:function() {
    return this.data("tokenInputObject").getTokens()
  }, toggleDisabled:function(a) {
    this.data("tokenInputObject").toggleDisabled(a);
    return this
  }, setOptions:function(b) {
    a(this).data("settings", a.extend({}, a(this).data("settings"), b || {}));
    return this
  }, destroy:function() {
    this.data("tokenInputObject") && (this.data("tokenInputObject").destroy(), this.removeData("tokenInputObject settings"));
    return this
  }};
  a.fn.tokenInput = function(a) {
    return w[a] ? w[a].apply(this, Array.prototype.slice.call(arguments, 1)) : w.init.apply(this, arguments)
  };
  a.TokenList = function(b, h, l) {
    function p(c) {
      return a(b).data("settings").enableHTML ? c : C(c)
    }
    function N(c) {
      "boolean" === typeof c ? a(b).data("settings").disabled = c : a(b).data("settings").disabled = !a(b).data("settings").disabled;
      g.attr("disabled", a(b).data("settings").disabled);
      k.toggleClass(a(b).data("settings").classes.disabled, a(b).data("settings").disabled);
      f && x(a(f), z);
      j.attr("disabled", a(b).data("settings").disabled)
    }
    function O() {
      null !== a(b).data("settings").tokenLimit && m >= a(b).data("settings").tokenLimit && (g.hide(), t())
    }
    function w() {
      if(D !== (D = g.val())) {
        var a = k.width() - g.offset().left + k.offset().left;
        P.html(C(D));
        g.width(Math.min(k.width(), Math.max(a, P.width() + 30)))
      }
    }
    function Q() {
      var c = a.trim(g.val()).split(a(b).data("settings").tokenDelimiter);
      a.each(c, function(c, d) {
        if(d) {
          a.isFunction(a(b).data("settings").onFreeTaggingAdd) && (d = a(b).data("settings").onFreeTaggingAdd.call(j, d));
          var q = {};
          q[a(b).data("settings").tokenValue] = q[a(b).data("settings").propertyToSearch] = d;
          E(q)
        }
      })
    }
    function R(c) {
      var e = a(a(b).data("settings").tokenFormatter(c)), d = !0 === c.readonly ? !0 : !1;
      d && e.addClass(a(b).data("settings").classes.tokenReadOnly);
      e.addClass(a(b).data("settings").classes.token).insertBefore(u);
      d || a("<span>" + a(b).data("settings").deleteText + "</span>").addClass(a(b).data("settings").classes.tokenDelete).appendTo(e).click(function() {
        if(!a(b).data("settings").disabled) {
          return F(a(this).parent()), j.change(), !1
        }
      });
      a.data(e.get(0), "tokeninput", c);
      n = n.slice(0, r).concat([c]).concat(n.slice(r));
      r++;
      S(n, j);
      m += 1;
      null !== a(b).data("settings").tokenLimit && m >= a(b).data("settings").tokenLimit && (g.hide(), t());
      return e
    }
    function E(c) {
      var e = a(b).data("settings").onAdd;
      if(0 < m && a(b).data("settings").preventDuplicates) {
        var d = null;
        k.children().each(function() {
          var b = a(this), e = a.data(b.get(0), "tokeninput");
          if(e && e[l.tokenValue] === c[l.tokenValue]) {
            return d = b, !1
          }
        });
        if(d) {
          A(d);
          u.insertAfter(d);
          y(g);
          return
        }
      }
      g.width(0);
      if(null == a(b).data("settings").tokenLimit || m < a(b).data("settings").tokenLimit) {
        R(c), g.attr("placeholder", null), O()
      }
      g.val("");
      t();
      a.isFunction(e) && e.call(j, c)
    }
    function A(c) {
      a(b).data("settings").disabled || (c.addClass(a(b).data("settings").classes.selectedToken), f = c.get(0), g.val(""), t())
    }
    function x(c, e) {
      c.removeClass(a(b).data("settings").classes.selectedToken);
      f = null;
      e === H ? (u.insertBefore(c), r--) : e === B ? (u.insertAfter(c), r++) : (u.appendTo(k), r = m);
      y(g)
    }
    function F(c) {
      var e = a.data(c.get(0), "tokeninput"), d = a(b).data("settings").onDelete, q = c.prevAll().length;
      q > r && q--;
      c.remove();
      f = null;
      y(g);
      n = n.slice(0, q).concat(n.slice(q + 1));
      0 == n.length && l.placeholder && (g.attr("placeholder", l.placeholder), D = null, w());
      q < r && r--;
      S(n, j);
      m -= 1;
      null !== a(b).data("settings").tokenLimit && (g.show().val(""), y(g));
      a.isFunction(d) && d.call(j, e)
    }
    function S(c, e) {
      var d = a.map(c, function(c) {
        return"function" == typeof a(b).data("settings").tokenValue ? a(b).data("settings").tokenValue.call(this, c) : c[a(b).data("settings").tokenValue]
      });
      e.val(d.join(a(b).data("settings").tokenDelimiter))
    }
    function t() {
      v.hide().empty();
      s = null
    }
    function G() {
      v.css({position:"absolute", top:k.offset().top + k.outerHeight(), left:k.offset().left, width:k.width(), "z-index":a(b).data("settings").zindex}).show()
    }
    function J(c, e) {
      if(e && e.length) {
        v.empty();
        var d = a("<ul>").appendTo(v).mouseover(function(b) {
          K(a(b.target).closest("li"))
        }).mousedown(function(b) {
          E(a(b.target).closest("li").data("tokeninput"));
          j.change();
          return!1
        }).hide();
        a(b).data("settings").resultsLimit && e.length > a(b).data("settings").resultsLimit && (e = e.slice(0, a(b).data("settings").resultsLimit));
        a.each(e, function(e, g) {
          var f = a(b).data("settings").resultsFormatter(g), h = g[a(b).data("settings").propertyToSearch], f = f.replace(RegExp("(?![^&;]+;)(?!<[^<>]*)(" + h.replace(T, "\\$&") + ")(?![^<>]*>)(?![^&;]+;)", "g"), h.replace(RegExp("(?![^&;]+;)(?!<[^<>]*)(" + c.replace(T, "\\$&") + ")(?![^<>]*>)(?![^&;]+;)", "gi"), function(a, b) {
            return"<b>" + p(b) + "</b>"
          })), f = a(f).appendTo(d);
          e % 2 ? f.addClass(a(b).data("settings").classes.dropdownItem) : f.addClass(a(b).data("settings").classes.dropdownItem2);
          0 === e && K(f);
          a.data(f.get(0), "tokeninput", g)
        });
        G();
        a(b).data("settings").animateDropdown ? d.slideDown("fast") : d.show()
      }else {
        a(b).data("settings").noResultsText && (v.html("<p>" + p(a(b).data("settings").noResultsText) + "</p>"), G())
      }
    }
    function K(c) {
      c && (s && (a(s).removeClass(a(b).data("settings").classes.selectedDropdownItem), s = null), c.addClass(a(b).data("settings").classes.selectedDropdownItem), s = c.get(0))
    }
    function U() {
      var c = g.val();
      c && c.length && (f && x(a(f), B), c.length >= a(b).data("settings").minChars ? (a(b).data("settings").searchingText && (v.html("<p>" + p(a(b).data("settings").searchingText) + "</p>"), G()), clearTimeout(V), V = setTimeout(function() {
        var e = c + L(), d = M.get(e);
        if(d) {
          a.isFunction(a(b).data("settings").onCachedResult) && (d = a(b).data("settings").onCachedResult.call(j, d)), J(c, d)
        }else {
          if(a(b).data("settings").url) {
            var d = L(), f = {data:{}};
            -1 < d.indexOf("?") ? (d = d.split("?"), f.url = d[0], d = d[1].split("&"), a.each(d, function(a, b) {
              var c = b.split("=");
              f.data[c[0]] = c[1]
            })) : f.url = d;
            f.data[a(b).data("settings").queryParam] = c;
            f.type = a(b).data("settings").method;
            f.dataType = a(b).data("settings").contentType;
            a(b).data("settings").crossDomain && (f.dataType = "jsonp");
            f.success = function(d) {
              M.add(e, a(b).data("settings").jsonContainer ? d[a(b).data("settings").jsonContainer] : d);
              a.isFunction(a(b).data("settings").onResult) && (d = a(b).data("settings").onResult.call(j, d));
              g.val() === c && J(c, a(b).data("settings").jsonContainer ? d[a(b).data("settings").jsonContainer] : d)
            };
            a.ajax(f)
          }else {
            a(b).data("settings").local_data && (d = a.grep(a(b).data("settings").local_data, function(d) {
              return-1 < d[a(b).data("settings").propertyToSearch].toLowerCase().indexOf(c.toLowerCase())
            }), M.add(e, d), a.isFunction(a(b).data("settings").onResult) && (d = a(b).data("settings").onResult.call(j, d)), J(c, d))
          }
        }
      }, a(b).data("settings").searchDelay)) : t())
    }
    function L() {
      var c = a(b).data("settings").url;
      "function" == typeof a(b).data("settings").url && (c = a(b).data("settings").url.call(a(b).data("settings")));
      return c
    }
    function y(a) {
      setTimeout(function() {
        a.focus()
      }, 50)
    }
    "string" === a.type(h) || "function" === a.type(h) ? (a(b).data("settings").url = h, h = L(), void 0 === a(b).data("settings").crossDomain && "string" === typeof h && (-1 === h.indexOf("://") ? a(b).data("settings").crossDomain = !1 : a(b).data("settings").crossDomain = location.href.split(/\/+/g)[1] !== h.split(/\/+/g)[1])) : "object" === typeof h && (a(b).data("settings").local_data = h);
    a(b).data("settings").classes ? a(b).data("settings").classes = a.extend({}, I, a(b).data("settings").classes) : a(b).data("settings").theme ? (a(b).data("settings").classes = {}, a.each(I, function(c, e) {
      a(b).data("settings").classes[c] = e + "-" + a(b).data("settings").theme
    })) : a(b).data("settings").classes = I;
    var n = [], m = 0, M = new a.TokenList.Cache, V, D, g = a('<input type="text"  autocomplete="off" autocapitalize="off">').css({outline:"none"}).attr("id", a(b).data("settings").idPrefix + b.id).focus(function() {
      if(a(b).data("settings").disabled) {
        return!1
      }
      if((null === a(b).data("settings").tokenLimit || a(b).data("settings").tokenLimit !== m) && a(b).data("settings").hintText) {
        v.html("<p>" + p(a(b).data("settings").hintText) + "</p>"), G()
      }
      k.addClass(a(b).data("settings").classes.focused)
    }).blur(function() {
      t();
      a(b).data("settings").allowFreeTagging && Q();
      a(this).val("");
      k.removeClass(a(b).data("settings").classes.focused)
    }).bind("keyup keydown blur update", w).keydown(function(c) {
      var e, d;
      switch(c.keyCode) {
        case 37:
        case 39:
        case 38:
        case 40:
          return a(this).val() ? (e = null, e = 40 === c.keyCode || 39 === c.keyCode ? a(s).next() : a(s).prev(), e.length && K(e)) : (e = u.prev(), d = u.next(), e.length && e.get(0) === f || d.length && d.get(0) === f ? 37 === c.keyCode || 38 === c.keyCode ? x(a(f), H) : x(a(f), B) : (37 === c.keyCode || 38 === c.keyCode) && e.length ? A(a(e.get(0))) : (39 === c.keyCode || 40 === c.keyCode) && d.length && A(a(d.get(0)))), !1;
        case 8:
          e = u.prev();
          if(a(this).val().length) {
            1 === a(this).val().length ? t() : setTimeout(function() {
              U()
            }, 5)
          }else {
            return f ? (F(a(f)), j.change()) : e.length && A(a(e.get(0))), !1
          }
          break;
        case 9:
        case 13:
        case 108:
        case 188:
          if(s) {
            E(a(s).data("tokeninput")), j.change()
          }else {
            if(a(b).data("settings").allowFreeTagging) {
              if(a(b).data("settings").allowTabOut && "" === a(this).val()) {
                return!0
              }
              Q()
            }else {
              if(a(this).val(""), a(b).data("settings").allowTabOut) {
                return!0
              }
            }
            c.stopPropagation();
            c.preventDefault()
          }
          return!1;
        case 27:
          return t(), !0;
        default:
          String.fromCharCode(c.which) && setTimeout(function() {
            U()
          }, 5)
      }
    });
    l.placeholder && g.attr("placeholder", l.placeholder);
    var j = a(b).hide().val("").on("focus.tokenInput", function() {
      y(g)
    }).on("blur.tokenInput", function() {
      g.blur();
      return j
    }), f = null, r = 0, s = null, k = a("<ul />").addClass(a(b).data("settings").classes.tokenList).click(function(b) {
      if((b = a(b.target).closest("li")) && b.get(0) && a.data(b.get(0), "tokeninput")) {
        var e = f;
        f && x(a(f), z);
        e === b.get(0) ? x(b, z) : A(b)
      }else {
        f && x(a(f), z), y(g)
      }
    }).mouseover(function(c) {
      (c = a(c.target).closest("li")) && f !== this && c.addClass(a(b).data("settings").classes.highlightedToken)
    }).mouseout(function(c) {
      (c = a(c.target).closest("li")) && f !== this && c.removeClass(a(b).data("settings").classes.highlightedToken)
    }).insertBefore(j), u = a("<li />").addClass(a(b).data("settings").classes.inputToken).appendTo(k).append(g), v = a("<div>").addClass(a(b).data("settings").classes.dropdown).appendTo(a("body")[0] || "html").hide(), P = a("<tester/>").insertAfter(g).css({position:"absolute", top:-9999, left:-9999, width:"auto", fontSize:g.css("fontSize"), fontFamily:g.css("fontFamily"), fontWeight:g.css("fontWeight"), letterSpacing:g.css("letterSpacing"), whiteSpace:"nowrap"});
    j.val("");
    h = a(b).data("settings").prePopulate || j.data("pre");
    a(b).data("settings").processPrePopulate && a.isFunction(a(b).data("settings").onResult) && (h = a(b).data("settings").onResult.call(j, h));
    h && h.length && a.each(h, function(a, b) {
      R(b);
      O();
      g.attr("placeholder", null)
    });
    a(b).data("settings").disabled && N(!0);
    a.isFunction(a(b).data("settings").onReady) && a(b).data("settings").onReady.call();
    this.clear = function() {
      k.children("li").each(function() {
        0 === a(this).children("input").length && F(a(this))
      })
    };
    this.add = function(a) {
      E(a)
    };
    this.remove = function(b) {
      k.children("li").each(function() {
        if(0 === a(this).children("input").length) {
          var e = a(this).data("tokeninput"), d = !0, f;
          for(f in b) {
            if(b[f] !== e[f]) {
              d = !1;
              break
            }
          }
          d && F(a(this))
        }
      })
    };
    this.getTokens = function() {
      return n
    };
    this.toggleDisabled = function(a) {
      N(a)
    };
    this.destroy = function() {
      this.clear();
      k.remove();
      v.remove();
      j.off(".tokenInput").show()
    };
    w();
    var T = RegExp("[.\\\\+*?\\[\\^\\]$(){}=!<>|:\\-]", "g")
  };
  a.TokenList.Cache = function(b) {
    var h = a.extend({max_size:500}, b), l = {}, p = 0;
    this.add = function(a, b) {
      p > h.max_size && (l = {}, p = 0);
      l[a] || (p += 1);
      l[a] = b
    };
    this.get = function(a) {
      return l[a]
    }
  }
})(jQuery);
