$(function() {

	// simple popup
	
	$("[data-popup]").on("click", function(e) {	
		e.preventDefault();
		var popupid = $(this).data("popup");
		$("#" + popupid).fadeIn();
		$("#overlay").css({'filter' : 'alpha(opacity=85)'}).fadeIn();
		var popuptopmargin = ($('#' + popupid).outerHeight()) / 2,
		popupleftmargin = ($('#' + popupid).outerWidth()) / 2;
		$('#' + popupid).css({
			'margin-top' : -popuptopmargin,
			'margin-left' : -popupleftmargin
		});			
	})
	$('#overlay').on("click", function() {
		$('#overlay, .popup').fadeOut();
	});
	
	
	//textarea and input clear default text on focus	
	$("textarea, input").not('input[type="submit"], .keep_text').focus(function() {
    	if (this.value == this.defaultValue){ this.value = ''; }
	});

	$("textarea, input").blur(function() {
    	if ($.trim(this.value) == ''){ this.value = (this.defaultValue ? this.defaultValue : ''); }
	});
	
	
	//autocomplete
	

	
	var users = [
      {
        value: "danny-blumenfeld",
		label: "Danny Blumenfeld",
        image: "danny.jpg",
		status: ""
      },
      {
        value: "dana-blumenfeld",
		label: "Dana Blumenfeld",
        image: "dana.jpg",
		status: "joined"
      },
      {
        value: "dandrew-conner",
		label: "Dandrew Conner",
        image: "dandrew.jpg",
		status: ""
      },
      {
        value: "dtamila-stavinsky",
		label: "Dtamila Stavinsky",
        image: "dtamila.jpg",
		status: ""
      }	  
    ];
	
	
	$.getJSON("/user/all-connections", function(connections) {
		$( ".invite_field" ).each(function(){

			$(this).autocomplete({
				minLength: 0,
				source: connections,
				focus: function( event, ui ) {
					$(this).val( ui.item.label );
					return false;
				},
				select: function( event, ui ) {
					$(this).val( ui.item.label ).siblings(".invite_btn").fadeIn(250);
					return false;
				},
				response: function( event, ui ) {
					$(this).siblings(".invite_btn").fadeOut(250);
				}
			}).data( "ui-autocomplete" )._renderItem = function( ul, item ) {
				return $( "<li>" )
				.addClass(item.status)
				.append( "<a><img src= 'images/autocomplete/" + item.image + "'><span class='name'>" + item.label + "</span>" + "<span class='status'>" + item.status + "</span></a>" )
				.appendTo( ul );
			};

		});
	});

	
	/* checkbox */
	
	$("body").on("click", "agree_check", function(){
		var fakeCheck = $(this).find('.check').toggleClass("checked"), realCheck = fakeCheck.find("input"), btn = $("#agree_btn");
		if(fakeCheck.hasClass("checked")){
			realCheck.attr("checked", "checked").trigger("change");			
			btn.slideDown(150);
		} else {
			realCheck.removeAttr("checked").trigger("change");	
			btn.slideUp(150);	
		}
	})
	
	
	$("#change_email").on("click", function(){
		var $this = $(this), newText = $this.data("text");
		$this.data("text", $this.text());
		$this.text(newText).toggleClass("confirm");
		
		if($this.hasClass("confirm")){
			$('#email').prop('disabled', false).trigger("focus");
		} else {
			$('#email').prop('disabled', true)
		}
		
	});
	
	// adjust input
	
	(function emailWidth(){
		var $email = $("#email"), $width = $("#email_width");
        $width.text($email.val());
        $inputSize = $width.width();
        $email.css("width", $inputSize);		
		
		$email.on("keypress",function(e) {
			if (e.which !== 0 && e.charCode !== 0) { // only characters
				var c = String.fromCharCode(e.keyCode|e.charCode);
				$width.text($(this).val() + c); 
				$inputSize = $width.width(); 
				$(this).css("width", $inputSize);
			 }
		});
		
	})();
	



});//end jQuery


