$(function() {
  $(".invite_field:not(:first)").prop("disabled", "true");

  $(".invite_field").val("Select a friend to invite");

  function hideAlreadyInvited() {
    $(".alreadyinvited").fadeOut();
  }

  $(".close").click(function() {
    hideAlreadyInvited();
    return false;
  });

  function editLinkedInInvitation($form) {
    $form.find(".invitation_editor").show();
    $form.find(".invitation_subject").val("Join me on the Kifi.com Private Beta");
    var name = $form.find(".invite_field").val().split(" ")[0] || "";
    $form.find(".invitation_message").val(
       "Hi " + name + ",\n\n"
     + "I would like to invite you to the private beta of kifi.com.\n\n"
     + "Use this link to accept the invitation:\n"
    );
    $form.find(".send_btn").off('click').click(function () {
      $this.text("Inviting...");
      $form.submit();
    });
    $form.find(".cancel_btn").off('click').click(function () {
      $form.find(".invitation_editor").hide();
    });
  }

  $.getJSON("/site/user/all-connections", function(connections) {

    $("#invites").on("click", ".invite_btn", function() {
      var $this = $(this);
      var $form = $this.parents("form");
      var network = $form.find(".invited_id").val().split("/")[0];
      if (network == "linkedin") {
        editLinkedInInvitation($form);
      } else {
        $this.text("Inviting...");
        $form.submit();
      }
      return false;
    });

    $("body").click(function() {
      hideAlreadyInvited();
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
          var $this = $(this);
          if(ui.item.status != "") {
            $this.siblings(".invited_id").val("");
            $this.val("");
            $(".alreadyinvited").fadeIn();
            return false;
          } else {
            $this.blur();
            $this.val( ui.item.label ).siblings(".invite_btn").fadeIn(250);
            $this.parent().siblings(".avatar").attr("src", ui.item.image);

            var $invited = $this.siblings(".invited_id")
            $invited.val(ui.item.value);
            return false;
          }

        },
        response: function( event, ui ) {
          $this = $(this);
          $this.siblings(".invited_id").val("");
          $this.siblings(".invite_btn").fadeOut(250);
          $this.parent().siblings(".avatar").attr("src", "/assets/images/default_avatar.png")
        }
      }).data( "ui-autocomplete" )._renderItem = function( ul, item ) {
        item.image = item.image || "/assets/images/default_avatar.png";
        return $( "<li>" )
        .addClass(item.status)
        .append( "<a><img src= '" + item.image + "' height='45' width='45'><span class='name'>" + item.label + "</span>" + "<span class='status'>" + item.status + "</span></a>" )
        .appendTo( ul );
      };

    });
  });
});
