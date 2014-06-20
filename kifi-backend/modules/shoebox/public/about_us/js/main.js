$(function() {

	$(window).on("load", function(){
		$("#story").addClass("animate");
	});
	

  // update browser url
  function updateUrl(target){
    location.hash = "#" + target;
  }
  
  // homepage animation
  $(".home_container .image").each(function() {
    var $img = $(this);
    if ($img.css("transitionProperty") === "left") {
      $img.addClass("loaded");
    } else {
      $img.animate({left: -$(this).width()}, 7000, "linear");
    }
  });

  // avatars tooltip
  $(".avatars").on("mouseenter mouseout", "li", function(event) {
    if (event.type == "mouseenter") {
      var $this=$(this), name=$this.data("name"), position=$this.data("position");
      $("<div id=avatars_tooltip><div class=name>" + name + "</div><div class=position>" + position + "</div>").appendTo($this).fadeIn(300);
    } else {
      $("#avatars_tooltip").remove();
    }
  });

  // scroll avatars down
  $("body").on("click", ".avatars li", function(e){
    $(this).addClass('active').siblings().removeClass('active');
    var target=$(this).data("scroll");
	$("#team_btn").removeClass("active");
    $('html,body').removeClass("slide_team").animate({
        scrollTop: $("#"+target).offset().top
      }, 600, function(){updateUrl(target);});
	$(".page").animate({
        scrollTop: $("#"+target).closest(".page").scrollTop() + $("#"+target).position().top
      }, 600, function(){updateUrl(target);});
	});


  // culture header images animations

  var delay = 50, speed = 180, $tabs = $(".culture_tabs");

  function animateInCultureImage(i) {
    // ensure image has loaded before animating it
    var $img = $(this), n = 1 + $img.siblings(".img").length, im = new Image;
    im.onload = function() {
      $img.delay(delay * ($img.data("in") - 1)).animate({left: i / n * 100 + "%"}, speed);
    };
    im.src = $img.css("backgroundImage").replace(/(^url\(['"]?|['"]?\)$)/g, "");
  }

  $tabs.on("click", ".tabs_nav>li:not(.current)", function(e, init) {
    $('html,body').animate({scrollTop: 0}, 600);
    var $li = $(this);	
    updateUrl($li.data("target"));
    $li.addClass("current").siblings().removeClass("current");
    $tabs.find(".tab").hide().eq($li.index()).css({display: "block", opacity: 0}).delay(350).animate({opacity: 1}, 200);
	$tabs.find(".mobile_banner").hide().eq($li.index()).css({display: "block", opacity: 0}).delay(350).animate({opacity: 1}, 200);	
    var numDone = 0, $imgs = $tabs.find(".banner.current .img").each(function() {
      var $img = $(this), sign = $img.data("dir");
      $img.delay((init ? 0 : delay) * $img.data("out")).animate({left: sign == "-" ? "-50%" : "120%"}, (init ? 0 : speed), function() {
        if (++numDone == $imgs.length) {
          $img.parent().removeClass("current");
          $tabs.find(".banner").eq($li.index()).addClass("current").find(".img").each(animateInCultureImage);
        }
      });
    });
  });
	
	$("#main_nav").on("click","[data-tab]",function(){
		var $this = $(this), target = $this.data("tab");
		$tabs.find("li[data-target='" + target + "']").trigger("click");
		$this.addClass("current").siblings().removeClass("current");
		$("#menu_btn").trigger("click");
		
	});

  // header shadow toggle
  $(window).scroll(function() {
    $("body").toggleClass("header_shadow", $(window).scrollTop() > 0);
  });
  

  // navigate to a hash inside a page on page load (if hash present in URL)
  !function() {
    var current = location.hash.substr(1).replace("'", ""), $li = $(".tabs_nav>li:not(.current)[data-target='" + current +"']");
    if ($li.length) {
      $li.trigger("click", [true]);
	  $("#main_nav").find("li[data-tab='" + current + "']").addClass("current").siblings().removeClass("current");
    } else {
      // do anything that might be required to init the page with no hash, invalid hash, or default view hash
      $tabs.find(".banner.current .img").each(animateInCultureImage);
    }
  }();

//mobile

$("#menu_btn").on("click", function(){
	$("body").toggleClass("slide_nav");
	$(this).toggleClass("active");
});

$("#team_btn").on("click", function(){
	$("body").toggleClass("slide_team");
	$(this).toggleClass("active");
	
});


});


