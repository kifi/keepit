console.log("starting keepit google_inject.js");

(function () { try {
  $ = jQuery.noConflict()

  var lastInjected = null;

  function log(message) {
    console.log(message);
  }

  function error(exception, message) {
    debugger;
    var errMessage = exception.message;
    if(message) {
      errMessage = "[" + message + "] " + exception.message;
    }
    console.error(exception);
    console.error(errMessage);
    console.error(exception.stack);
    alert("exception: " + exception.message);
  }

  log("injecting keep it to google search result page");
  
  function updateQuery() { 
    var queryInput = $("input[name='q']");
    var query = queryInput.val();
    if (!query) {
      debugger;
      log("query is undefined");
      return;
    }
    log("search term: " + query);
    var request = {
      type: "get_keeps", 
      query: queryInput.val()
    };
    chrome.extension.sendRequest(request, function(searchResults) {
      try {
        if (!(searchResults) || searchResults.length == 0) {
          log("No search results!");
          return;
        }
        log("got " + searchResults.length + " keeps:");
        $(searchResults).each(function(i, e){log(e)});
        var old = $('#keepit');
        if (old && old.length > 0) {
          old.slideUp(function(){
            old.remove();
            addResults(searchResults);
          });
        } else {
          addResults(searchResults);
        }
      } catch (e) {
        error(e);
      }
    });
  }

  updateQuery();

  $('#main').change(function() {
    if ($('#keepit').length === 0) {
      updateQuery();
    }
  });

  $("input[name='q']").change(function(){
    updateQuery();
  });

  /*******************************************************/

  function addResults(searchResults) {
    try {
      var old = $('#keepit');
      if (old.length > 0) {
        throw Error("Old keepit is still around: " + old);
      }
      var ol = $('<ol id="keepit" class="kpt-results"></ol>');
      lastInjected = ol.head;
      var head = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Your Bookmarks</center></h3></div><!--n--></li>')
      var tail = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Google Results</center></h3></div><!--n--></li>')
      ol.append(head);
      head.after(tail);
      var resultCount = 0;
      $(searchResults).each(function(i, e){
        var link = $('<li class="g"></li>');
        link.append('<div class="vsc"><h3 class="r"><a href="'+e.bookmark.url+'">'+e.bookmark.title+'</a></h3><div class="vspib" aria-label="Result details" role="button" tabindex="0"></div><div class="s"><div class="f kv"><cite>'+e.bookmark.url+'</cite></div></div></div><!--n-->')
        resultCount++;
        $(e.users).each(function(j, user){
          var user = $('<span style="margin:2px"><a data-hover="tooltip" title="'+user.firstName+' '+user.lastName+'" class="name_tooltip_link" href="http://www.facebook.com/'+user.facebookId+'" target="_blank"><img src="https://graph.facebook.com/'+user.facebookId+'/picture?type=square" width="30" height="30" alt=""></a></span>');
          link.append(user);
        });
        tail.before(link);
      });
      ol.hide();
      var toExpend = (80 + 100 * resultCount);
      ol.css("height", toExpend + "px");
      console.log(ol);
      if ($('#keepit').length > 0) {
        return;
      }
      if ($('#ires').length == 0) {
        return;
      }
      var iterations = 10;
      function showResults() {
        if (ol.head !== lastInjected) {
          return;
        }
        if (iterations > 0) {
          console.log("test show results, iterations = " + iterations);
          iterations = iterations - 1;
          var element = $("#keepit");
          if (element.length > 0 && element.head !== ol.head) {
            element.remove();
          }
          if (element.length == 0) {
            $('#ires').prepend(ol);
            setTimeout(function(){ showResults(); }, 1000);
          } else if (!element.is(":visible")) {
            injectDiv(ol, resultCount, function(){
              setTimeout(function(){ showResults(); }, 1000);
            });
          }
        }
      }
      showResults();
    } catch (e) {
      error(e);
    }
  }

  function injectDiv(ol, resultCount, callback) {
    if (ol.head !== lastInjected) {
      return;
    }
    //neight needs to be proportional to num of elements with max = 3
    console.log("result count is " + resultCount + ", expending...");
    ol.slideDown(1000, function() {
      console.log("done expanding. now at " + ol.css("height"));
      callback();
    });
  }

} catch(exception) {
    debugger;
    alert("exception: " + exception.message);
    console.error(exception);
    console.error(exception.stack);
}})()