$(function() {

  var $cards = $(".card_row");
  setInterval(function() {
    var $card = $cards.filter(".visible").removeClass("visible");
    var i = $cards.index($card);
    $cards.eq((i + 1) % $cards.length).addClass("visible");
  }, 8000);

  $("#contentscroll").click(function() {
    $("body").animate({
      scrollTop: $("#kifi_features").offset().top - 100
    }, 250)
  });

  $('#loginHeader,#signupHeader').click(function () {
    var pinned = $(document).scrollTop() >= 90;
	  var value = this.id === 'loginHeader' ?
	   (pinned ? 'clickLoginPinned' : 'clickLoginHeader') :
	   (pinned ? 'clickSignupPinned' : 'clickSignupHeader');
	$(this).attr('data-track-action', value);
  });


});
window.addEventListener("message", function(b) {
  "playing-video" === b.data && setTimeout(function() {
    var a = $('<a href="/signup" class="video-signup-btn" data-track-action="clickSignUpVideo">Sign up</a>').css({
      opacity: 0,
      transition: "1s ease-out"
    });
    $(".video-iframe").not(":has(.video-signup-btn)").after(a);
    a.on("transitionend", function() {
      $(this).removeAttr("style")
    }).each(function() {
      this.offsetHeight
    }).css("opacity", 1)
  }, 300)
});

$(window).on('scroll', function(){

	if($(window).scrollTop() > 250 ) {
		$('#header_bar').addClass('fixed');
	} else {
  	$('#header_bar').removeClass('fixed');
	}
});
