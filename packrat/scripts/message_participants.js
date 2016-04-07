// @require styles/keeper/message_participants.css
// @require styles/keeper/compose.css
// @require scripts/html/keeper/kifi_mustache_tags.js
// @require scripts/html/keeper/message_participants.js
// @require scripts/html/keeper/message_participant_email.js
// @require scripts/html/keeper/message_participant_user.js
// @require scripts/html/keeper/message_participant_icon.js
// @require scripts/html/keeper/message_avatar_email.js
// @require scripts/html/keeper/message_avatar_user.js
// @require scripts/html/keeper/message_avatar_library.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/lib/q.min.js
// @require scripts/lib/jquery.js
// @require scripts/lib/jquery-tokeninput.js
// @require scripts/lib/antiscroll.min.js
// @require scripts/formatting.js
// @require scripts/friend_search.js
// @require scripts/render.js
// @require scripts/kifi_util.js
// @require scripts/prevent_ancestor_scroll.js

/**
 * --------------------------
 *  Message Participants
 * --------------------------
 *
 * Message Participants is an UI component that manages
 * participants of the current message conversation.
 */

k.messageParticipants = k.messageParticipants || (function ($, win) {
  'use strict';

  var OVERFLOW_LENGTH = 8;

  var partials = {
    'message_avatar_user': 'message_avatar_user',
    'message_avatar_email': 'message_avatar_email',
    'message_avatar_library': 'message_avatar_library',
    'message_participant_user': 'message_participant_user',
    'message_participant_email': 'message_participant_email',
    'keep_box_lib': 'keep_box_lib'
  };

  var portHandlers = {
    participants: function (participants) {
      k.messageParticipants.setParticipants(participants);
    },
    add_participants: function (users) {
      k.messageParticipants.addParticipant.apply(k.messageParticipants, users);
    }
  };

  api.onEnd.push(function () {
    k.messageParticipants.destroy();
  });

  return {

    /**
     * Whether this component is initialized
     *
     * @property {boolean}
     */
    initialized: false,

    /**
     * Parent UI Component. e.g. Message Header
     *
     * @property {Object}
     */
    parent: null,

    escHandler: null,

    onDocClick: null,

    /**
     * Initializes a Message Participants.
     */
    init: function () {
      this.initialized = true;

      api.port.on(portHandlers);

      this.initEvents();
      this.initScroll();
    },

    /**
     * Initializes event listeners.
     */
    initScroll: function () {
      var $list = this.get$('.kifi-message-participant-list');
      this.$list = $list;
      $list.antiscroll({
        x: false
      }).children().preventAncestorScroll();
    },

    /**
     * Initializes event listeners.
     */
    initEvents: (function () {
      function onClick(e) {
        var $target = $(e.target);
        if (this.isExpanded() && !$target.closest('.kifi-message-participants').length) {
          this.collapseParticipants();
          e.participantsClosed = true;
        }
        else if (this.isDialogOpened() && !$target.closest('.kifi-message-participant-dialog').length && !this.getAddDialog().hasClass('kifi-non-empty')) {
          this.hideAddDialog();
          e.addDialogClosed = true;
        }
      }

      function addDocListeners() {
        if (this.initialized) {
          this.escHandler = this.handleEsc.bind(this);
          $(document).data('esc').add(this.escHandler);

          var onDocClick = this.onDocClick = onClick.bind(this);
          document.addEventListener('click', onDocClick, true);
        }
      }

      return function () {
        var submitAddParticipants = this.addParticipantTokens.bind(this);

        this.get$()
        .on('click', '.kifi-message-participant-avatar>a,.kifi-message-participant-a', this.openUserProfile.bind(this))
        .on('click', '.kifi-message-participants-avatars-expand,.kifi-message-participant-1-or-2-add', this.toggleAddDialog.bind(this))
        .on('click', '.kifi-message-participant-list-hide', this.toggleParticipants.bind(this))
        .on('click', '.kifi-message-participant-dialog-button', submitAddParticipants)
        .on('keydown', '.kifi-message-participant-dialog', function (e) {
          if (e.which === 13 && e.originalEvent.isTrusted !== false) {
            submitAddParticipants();
          }
        });

        this.getParent$()
        .on('click', '.kifi-message-show-participant', this.toggleParticipants.bind(this));

        win.setTimeout(addDocListeners.bind(this));
      };
    })(),

    /**
     * Returns a jQuery wrapper object for dialog input
     * after tokenInput construction.
     */
    get$TokenInput: function () {
      return this.get$('.kifi-ti-token-for-input>input');
    },

    /**
     * Constructs and initializes (if not already done) and then asynchronously focuses a tokenInput
     * with autocomplete feature for searching and adding people to the conversation.
     */
    initAndAsyncFocusInput: function () {
      var $input = this.$input;
      var keep = this.parent.keep;
      var permissions = keep && keep.viewer && keep.viewer.permissions || [];
      var canChangeLibrary = permissions.indexOf('add_libraries') > -1 && permissions.indexOf('remove_libraries') > -1;

      if (!$input) {
        $input = this.$input = this.get$('.kifi-message-participant-dialog-input');
        initFriendSearch($input, 'threadPane', this.getParticipants(), api.noop, {
          placeholder: 'Type a name...',
          onAdd: function () {
            this.getAddDialog().addClass('kifi-non-empty');
          }.bind(this),
          onDelete: function () {
            if (!$input.tokenInput('get').length) {
              this.getAddDialog().removeClass('kifi-non-empty');
            }
          }.bind(this)
        }, { objects: canChangeLibrary, participants: !canChangeLibrary });
      }

      setTimeout(function () {
        this.get$TokenInput().focus();
      }.bind(this));
    },

    // socket.send(["add_participants_to_thread","e45841fb-b7de-498f-97af-9f1ab17ef9a9",["df7ba036-700c-4f5d-84d1-313b5bf312b6"]])

    /**
     * A listener for adding
     *
     * participants: [{
     *   firstName: "Jenny"
     *   id: "6f21b520-87e7-4053-9676-85762e96970a"
     *   lastName: "Batres"
     *   pictureName: "0.jpg"
     * }]
     */
    setParticipants: function (participants) {
      var parent = this.parent;
      if (parent && parent.initialized) {
        parent.participants = participants;
        this.updateView();
      }
    },

    /**
     * Renders a UI component given the component name.
     *
     * @param {string} name - UI component name
     *
     * @return {string} A rendered html
     */
    render: function (name) {
      switch (name) {
      case 'button':
        return this.renderButton();
        //case 'option':
      case 'content':
        return this.renderContent();
      }
    },

    getParticipants: function () {
      return this.parent.participants;
    },

    /**
     * Returns a boolean value representing whether
     * the conversation has more number of people than the threshold.
     *
     * true - n > threshold
     * false - n <= threshold
     *
     * @return {boolean} Whether the conversation has more number of people than the threshold
     */
    isOverflowed: function () {
      return this.getParticipants().length > OVERFLOW_LENGTH;
    },

    /**
     * Given the user object,
     * returns the full name of the user.
     *
     * @param {Object} user - A user object
     *
     * @return {string} Full name of the user
     */
    getFullName: function (o) {
      return o.firstName ? o.firstName + ' ' + o.lastName : o.name ? o.name : o.id;
    },

    /**
     * Returns the current state object excluding UI states.
     *
     * @return {Object} A state object
     */
    getView: function () {
      var participants = this.getParticipants();
      if (!participants.some(idIs(k.me.id))) {
        participants.push(k.me);
      }
      var participantsCount = participants.filter(function (user) {
        return user.id !== k.me.id;
      }).length + 1; // Always treat current user as a counted participant
      var onKeep = !!(this.parent.keep && this.parent.keep.recipients.libraries.length > 0);
      var other = participants.length <= 2 ? participants.filter(function (user) {
        return user.id !== k.me.id;
      })[0] : null;

      var author = (this.parent.keep && this.parent.keep.author);
      var attr = (author && author.id !== k.me.id ? author : other);

      return {
        author: author,
        authorName: author.name,
        participantName: attr ? this.getFullName(attr) : '',
        isOverflowed: this.isOverflowed(),
        participantCount: participantsCount,
        avatars: this.renderAvatars(),
        participants: this.renderParticipants()
      };
    },
    /**
     * Renders and returns html for participants container.
     *
     * @return {string} participants html
     */
    renderContent: function () {
      return $(k.render('html/keeper/message_participants', this.getView(), partials));
    },

    /**
     * Renders and returns a 'Add Participants' button.
     *
     * @return {string} Add participant icon html
     */
    renderButton: function () {
      return $(k.render('html/keeper/message_participant_icon', this.getView(), partials));
    },

    /**
     * Renders and returns html for a list of avatars.
     *
     * @return {string} html for a list of avatars
     */
    renderAvatars: function () {
      return this.getParticipants().map(this.renderAvatar).sort(orderLibrariesFirst).slice(0, OVERFLOW_LENGTH);
    },

    /**
     * Renders and returns html for a single avatar.
     *
     * @return {string} html for a single avatar
     */
    renderAvatar: function (participant) {
      formatParticipant(participant);
      return {
        email: participant.isEmail ? participant : null,
        user: participant.isUser ? participant : null,
        library: participant.isLibrary ? participant : null
      };
    },

    /**
     * Renders and returns html for a participant list.
     *
     * @return {string} html for a participant list
     */
    renderParticipants: function () {
      return this.getParticipants().map(this.renderParticipant).sort(orderLibrariesFirst);
    },

    /**
     * Renders and returns html for a participant list item.
     *
     * @return {string} html for a participant list item
     */
    renderParticipant: function (participant) {
      formatParticipant(participant);
      return {
        email: participant.isEmail ? participant : null,
        user: participant.isUser ? participant : null,
        library: participant.isLibrary ? participant : null
      };
    },

    /**
     * Finds and returns a jQuery wrapper object for the given selector.
     *
     * @param {string} [selector] a optional selector
     *
     * @return {jQuery} A jQuery wrapper object
     */
    get$: function (selector) {
      return this.parent.find(selector || '.kifi-message-participants');
    },

    /**
     * Returns a jQuery wrapper object for the parent module.
     *
     * @return {jQuery} A jQuery wrapper object
     */
    getParent$: function () {
      return this.parent.$el;
    },

    /**
     * Expands the participant list.
     */
    expandParticipants: function () {
      var $wrapper = this.get$('.kifi-message-participant-list-root'),
        list = $wrapper.children()[0];

      this.parent.shadePane();

      this.get$().addClass('kifi-expanded');

      $wrapper.height(list.offsetHeight);
    },

    /**
     * Collapses the participant list.
     */
    collapseParticipants: function () {
      this.get$().removeClass('kifi-expanded');
      this.get$('.kifi-message-participant-list-root').height(0);

      this.parent.unshadePane();
    },

    updateParticipantsHeight: function () {
      if (this.isExpanded()) {
        this.expandParticipants();
      }
    },

    toggleParticipants: function (e) {
      if (e && e.originalEvent && e.originalEvent.participantsClosed) {
        return;
      }

      if (this.isExpanded()) {
        this.collapseParticipants();
      } else {
        this.expandParticipants();
        this.hideAddDialog();
      }
    },

    openUserProfile: function (e) {
      var a = e.currentTarget, url = a.href;
      if (url.indexOf('?') < 0) {
        a.href = url + '?o=xmp';
        setTimeout(function () {
          a.href = url;
        });
      }
    },

    /**
     * Add/Remove classname(s) to/from the container
     */
    toggleClass: function (name, val) {
      return this.get$().toggleClass(name, !! val);
    },

    hasClass: function (name) {
      return this.get$().hasClass(name);
    },

    isExpanded: function () {
      return this.hasClass('kifi-expanded');
    },

    isDialogOpened: function () {
      return this.hasClass('kifi-dialog-opened');
    },

    getAddDialog: function () {
      return this.get$('.kifi-message-participant-dialog');
    },

    addParticipantTokens: function () {
      var $input = this.$input;
      var participants = $input.tokenInput('get');
      var users = participants.filter(function (p) { return !p.email && p.id[0] !== 'l'; });
      var libraries = participants.filter(function (p) { return p.id[0] === 'l'; });
      var emails = participants.filter(function (p) { return p.email; });

      this.sendModifyKeep(users, emails, libraries)
      .then(function () {
        this.addParticipant.apply(this, participants);
      }.bind(this))
      .finally(function () {
        $input.tokenInput('clear');
        this.toggleAddDialog();
      }.bind(this));
    },

    sendModifyKeep: function (users, emails, libraries) {
      var deferred = Q.defer();

      var keep = this.parent.keep;
      api.port.emit('update_keepscussion_recipients', {
        keepId: keep.id,
        newUsers: users || [],
        newEmails: emails || [],
        newLibrary: libraries[0],
        oldLibraries: keep.recipients.libraries
      }, function success(d) {
        var keep = d.response;
        if (d.success) {
          this.parent.keep = keep;
          this.parent.refresh();
          this.updateView();
          deferred.resolve(keep);
        } else {
          // TODO(carlos): Add a progress bar to seem snappier
          var $toShake = this.get$();
          $toShake
          .on('animationend', function onEnd() {
            $toShake.off('animationend', onEnd);
            $toShake.removeClass('kifi-shake');
            this.parent.refresh();
          }.bind(this))
          .addClass('kifi-shake');

          deferred.reject();
        }
      }.bind(this));

      return deferred.promise;
    },

    showAddDialog: function () {
      this.get$('.kifi-message-add-participant').toggleClass('kifi-active', true);
      this.toggleClass('kifi-dialog-opened', true);
      this.initAndAsyncFocusInput();
      this.parent.hideOptions();
    },

    hideAddDialog: function () {
      this.get$('.kifi-message-add-participant').toggleClass('kifi-active', false);
      this.toggleClass('kifi-dialog-opened', false);
    },

    toggleAddDialog: function (e) {
      if (e && e.originalEvent && e.originalEvent.addDialogClosed) {
        return;
      }
      if (this.isDialogOpened()) {
        this.hideAddDialog();
      } else {
        this.showAddDialog();
        this.collapseParticipants();
      }
    },

    addParticipant: function () {
      var participants = this.getParticipants();
      var args = Array.prototype.slice.call(arguments);
      var count = 0;

      for (var i = 0, len = arguments.length, participant, participantId; i < len; i++) {
        participant = args[i];
        participantId = participant && participant.id;

        if (participantId && !participants.some(idIs(participantId))) {
          if (isLibrary(participant)) { // TODO(carlos): remove for multiple libraries on keep
            removeFromList(participants, participants.find(isLibrary));
          }
          participants.unshift(participant);
          count++;
        }
      }

      if (count) {
        this.updateView();
        this.highlightParticipantsWithIds(args.map(mapId));
      }
    },

    highlightParticipantsWithIds: function (ids) {
      this.get$('.kifi-message-participant-avatar')
      .filter(function () {
        return ids.indexOf(this.dataset.id) > -1;
      })
      .addClass('kifi-highlight');
    },

    updateView: function () {
      var view = this.getView();
      var $el = this.get$();
      $el.attr('data-kifi-participants', view.participantCount);
      $el.toggleClass('kifi-overflow', view.isOverflowed);
      $el.find('.kifi-message-participant-name').text(view.participantName);
      $el.find('.kifi-participant-count').text(view.participantCount);
      var renderedAvatars = view.avatars.map(function (a) {
        a = a.email || a.user || a.library;
        var template = (a.isEmail ? 'html/keeper/message_avatar_email' : a.isUser ? 'html/keeper/message_avatar_user' : 'html/keeper/message_avatar_library');
        return $(k.render(template, a));
      });
      var renderedParticipants = view.participants.map(function (p) {
        p = p.email || p.user || p.library;
        var template = (p.isEmail ? 'html/keeper/message_participant_email' : p.isUser ? 'html/keeper/message_participant_user' : 'html/keeper/keep_box_lib');
        return $(k.render(template, p));
      });
      var avatarList = $el.find('.kifi-message-participants-avatars');
      avatarList.find('> :not(:last)').remove();
      avatarList.prepend(renderedAvatars);
      $el.find('.kifi-message-participant-list-inner').empty().append(renderedParticipants);
      this.updateParticipantsHeight();
      this.$list.data('antiscroll').refresh();
    },

    updateCount: function (count) {
      return this.get$('.kifi-participant-count').text(count);
    },

    handleEsc: function (e) {
      if (this.isExpanded()) {
        this.collapseParticipants();
        return false;
      }
      else if (this.isDialogOpened()) {
        this.hideAddDialog();
        return false;
      }
    },

    /**
     * It removes all event listeners and caches to elements.
     */
    destroy: function () {
      this.initialized = false;
      this.parent = null;

      $(document).data('esc').remove(this.escHandler);
      this.escHandler = null;

      var onDocClick = this.onDocClick;
      if (onDocClick) {
        document.removeEventListener('click', onDocClick, true);
        this.onDocClick = null;
      }

      api.port.off(portHandlers);

      // .remove() not called on jQuery elements because it would disrupt a farewell transition
      // an ancestor should call it later (e.g. by being removed itself)

      this.$input = this.$list = this.$el = null;
    }
  };

  function orderLibrariesFirst(a, b) {
    if (a.library && !b.library) {
      return -1;
    } else if (!a.library && b.library) {
      return 1;
    } else {
      return 0;
    }
  }

  function idIs(id) {
    return function (o) { return o.id === id; };
  }

  function isLibrary(o) {
    return o && (o.isLibrary === true || o.kind === 'library');
  }

  function mapId(o) {
    return o.id;
  }

  function removeFromList(list, item) {
    var index = list.indexOf(item);
    if (index > -1) {
      list.splice(list.indexOf(item), 1);
    }
  }
})(jQuery, this);
