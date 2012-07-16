(function() {
  $ = jQuery.noConflict()
  function async(fun) {
    setTimeout(fun, 1000);
  }  
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
    chrome.extension.sendRequest({type: "get_keeps", query: queryInput.val()}, function(response) {
      if (response.message.length == 0) {
        return;
      }
      console.log("keeps are: " + JSON.stringify(response));
      var old = $('#keepit');
      old.slideUp(function(){old.remove();});
      function addResults() {
        var ol = $('<ol id="keepit"></ol>');
        var head = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Your Bookmarks</center></h3></div><!--n--></li>')
        var tail = $('<li class="g keepit"><div class="vsc"><h3 class="r"><center>Google Results</center></h3></div><!--n--></li>')
        ol.append(head);
        head.after(tail);
        //$('#rso').prepend(head)
        //head.after(tail)

        $(response.message).each(function(i, e){
          var link = $('<li class="g"><div class="vsc"><h3 class="r"><a href="'+e.url+'">'+e.title+'</a></h3><div class="vspib" aria-label="Result details" role="button" tabindex="0"></div><div class="s"><div class="f kv"><cite>'+e.url+'</cite></div></div></div><!--n--></li>');
          tail.before(link);
        });
        ol.hide();
        console.log(ol);
        function showResults() {
          var googleResults = $('#ires');
          console.log(googleResults);
          if (googleResults.length > 0) {
            googleResults.prepend(ol);
            ol.slideDown();
          } else {
            setTimeout(function(){showResults();}, 100);
          }
        }
        async(showResults());
      }
      async(addResults());
    });
  }
  
  updateQuery();
  console.log("jquery val = " + queryInput.val());
  $('#main').change(function() {
    if ($('#keepit').length === 0) {
      async(function(){updateQuery();});
    }
  });
  queryInput.change(function(){
    async(function(){updateQuery();});
  });

})()