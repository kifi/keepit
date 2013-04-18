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

  
  /* checkbox */
  
  $("body").on("click", ".agree_check", function(){
    var fakeCheck = $(this).find('.check').toggleClass("checked"), realCheck = fakeCheck.find("input"), btn = $("#agree_btn");
    if(fakeCheck.hasClass("checked")){
      realCheck.attr("checked", "checked").trigger("change");     
      btn.slideDown(150);
    } else {
      realCheck.removeAttr("checked").trigger("change");  
      btn.slideUp(150); 
    }
  });

  $("#agree_btn").on("click", function() {
    $("#agree_form").submit();
  });
  
  
  $("#change_email").on("click", function(){
    var $button = $(this);
    $button.text("Saved!");

    $.post('/user/update-email', JSON.stringify({"email": $("#email").val()}))
    .always(function() {
      setTimeout(function() {
        console.log("what")
        $button.text("Save email address");
      }, 3000);
    });
    
  });

});//end jQuery


