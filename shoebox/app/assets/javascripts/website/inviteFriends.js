$(function() {

  $.getJSON("/user/all-connections", function(connections) {

    $("#invites").on("click", ".invite_btn", function() {
      $this = $(this);
      $.post("/invite", JSON.stringify({ socialId: $this.siblings(".invited_id").val() }))
        .done(function() {
          $this.parents("li").addClass("invite-active");
          var name = $this.siblings('.invite_field').val();
          $this.parents(".avatar_info").html('<div class="name">' + name + '</div><div class="status">Invited</div>');
        })
        .fail(function(e) {
          var msg = JSON.parse(e.responseText).invitation;
          var $alreadyInvited = $this.parents(".avatar_info").append('<div class="status invite-already">' + msg + '</div>')
          setTimeout(function() {
            $alreadyInvited.find('.invite-already').fadeOut();
          }, 2000);
        });
      $(this).fadeOut();

      return false;
    });

    $(".invite_field").each(function(){

      $(this).autocomplete({
        minLength: 0,
        source: connections,
        focus: function( event, ui ) {
          $(this).val( ui.item.label );
          return false;
        },
        select: function( event, ui ) {
          $this = $(this);
          $this.val( ui.item.label ).siblings(".invite_btn").fadeIn(250);
          $this.parent().siblings(".avatar").attr("src", ui.item.image);

          var $invited = $this.siblings(".invited_id")
          $invited.val(ui.item.value);
          return false;
        },
        response: function( event, ui ) {
          $this = $(this);
          $this.siblings(".invited_id").val("");
          $this.siblings(".invite_btn").fadeOut(250);
          $this.parent().siblings(".avatar").attr("src", "/assets/images/default_avatar.png")
        }
      }).data( "ui-autocomplete" )._renderItem = function( ul, item ) {
        return $( "<li>" )
        .addClass(item.status)
        .append( "<a><img src= '" + item.image + "' height='45' width='45'><span class='name'>" + item.label + "</span>" + "<span class='status'>" + item.status + "</span></a>" )
        .appendTo( ul );
      };

    });
  });
});