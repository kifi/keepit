// general functionality

jQuery(function($){

	$(".home_banner .wrap").css("height",$(window).height());
	
	$("#main_content").css("padding-top",$(window).height());
	
	$(window).on("scroll", function(){
		
		var page = $(window), offset = 50;
		
		var scrollbarPos = page.scrollTop(), contentDistTop = $('#page_content').offset().top;
		
		if(scrollbarPos > contentDistTop - offset){
			$("body").addClass("fixed_banner");
			$(".home_banner").css("bottom", page.height() - offset);
		} else {
			$("body").removeClass("fixed_banner");
			$(".home_banner").css("bottom", "auto");
		};	
		
	});

});//end jQuery

