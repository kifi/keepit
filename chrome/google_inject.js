(function() {
  $ = jQuery.noConflict()
  console.log("injecting keep it to google search result page")

  chrome.extension.onRequest.addListener(
    function(request, sender, sendResponse) {
      console.log("onRequest");
    });

  chrome.extension.onConnect.addListener(function(port) {
    console.log("onConnect: " + port);
  });  

  var queryInput = $(document.getElementsByName('q')[0]);
  
  function updateQuery() { 
    var query = queryInput.val();
    if (!query) {
      console.log("query is undefined");
      return;
    }
    console.log("search term: " + query);
    chrome.extension.sendRequest({type: "get_keeps", query: queryInput.val()}, function(searchResults) {
      function addResults() {
        var ol = $('<ol id="keepit" class="kpt-results"></ol>');
        var head = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Your Bookmarks</center></h3></div><!--n--></li>')
        var tail = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Google Results</center></h3></div><!--n--></li>')
        ol.append(head);
        head.after(tail);
        //$('#rso').prepend(head)
        //head.after(tail)
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
        ol.css("height", "0px");
        console.log(ol);
        var iterations = 10;
        function showResults() {
          if ($('#keepit').length > 0) {
            return;
          }
          var googleResults = $('#ires');
          console.log(googleResults);
          if (googleResults.length > 0) {
            googleResults.prepend(ol);
            //neight needs to be proportional to num of elements with max = 3
            ol.css("height", "0px");
            ol.show();
            ol.animate({
              height: '+=' + (80 + 100 * resultCount) 
            }, 1000, function() {
              console.log("done expanding");
            })
          } 
          if (iterations > 0) {
            console.log("test show results, iterations = " + iterations);
            iterations = iterations - 1;
            setTimeout(function(){showResults();}, 1000);
          }
        }
        showResults();
      }
      if (!(searchResults) || searchResults.length == 0) {
        console.log("No search results!");
        return;
      }
      debugger;
      console.log("keeps are: " + searchResults);
      var old = $('#keepit');
      if (old && old.length > 0) {
        old.slideUp(function(){
          old.remove();
          addResults();
        });
      } else {
        addResults();
      }
    });
  }
  
  updateQuery();
  console.log("jquery val = " + queryInput.val());
  $('#main').change(function() {
    if ($('#keepit').length === 0) {
      updateQuery();
    }
  });
  queryInput.change(function(){
    updateQuery();
  });

})()