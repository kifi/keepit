$(function() {
  $("#chrome-install").click(function() {
    chrome.webstore.install(function() {
      alert("Installed!")
    })  
  });
});