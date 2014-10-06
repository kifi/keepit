$(function () {
 $('#home_slider').anythingSlider({
   mode                : "fade",
   buildStartStop      : false,
   expand              : true,
   buildNavigation     : false,
   buildArrows         : false,
   autoPlay            : true,
   hashTags            : false,
   delay               : 4000,
   animationTime       : 1000,
   easing              : 'easeInOutQuint',
   onSlideBegin: function (event, slider) {
     var laptop = $("#laptop_slides"), slides = laptop.find("li").length, currSlide = slider.currentPage;
     if (currSlide >= slides){
       currSlide = 0;
     }
     $("#laptop_slides").find("li").eq(currSlide).fadeIn(1000).siblings().fadeOut(1000);
   },
   onSlideComplete: function (slider) {
   }
 });
});

window.addEventListener('message', function (e) {
  if (e.data === 'playing-video') {
    setTimeout(function () {
      var $a = $('<a href="/signup" class="video-signup-btn" data-track-action="clickSignUpVideo">Sign up</a>')
        .css({opacity: 0, transition: '1s ease-out'});
      $('.video-iframe').not(':has(.video-signup-btn)').after($a);
      $a.on('transitionend', function () {$(this).removeAttr('style')})
        .each(function () {this.offsetHeight})
        .css('opacity', 1);
    }, 300);
  }
});
