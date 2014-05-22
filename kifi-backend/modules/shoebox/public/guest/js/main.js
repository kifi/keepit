// general functionality

jQuery(function($){

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
		onSlideBegin : function(event, slider){
			var laptop = $("#laptop_slides"), slides = laptop.find("li").length, currSlide = slider.currentPage;
			if (currSlide >= slides){
				currSlide = 0;	
			}
			$("#laptop_slides").find("li").eq(currSlide).fadeIn(1000).siblings().fadeOut(1000);
			
		},
		onSlideComplete : function(slider){
		
			//slider.$currentPage.find(".slide_content").animate({opacity:1,left:0});

		}
	});

});//end jQuery

