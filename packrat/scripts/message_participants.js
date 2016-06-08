// @require styles/keeper/message_participants.css
// @require styles/keeper/compose.css
// @require scripts/html/keeper/kifi_mustache_tags.js
// @require scripts/html/keeper/message_participants.js
// @require scripts/html/keeper/message_participant_email.js
// @require scripts/html/keeper/message_participant_user.js
// @require scripts/html/keeper/message_participant_library.js
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
// @require scripts/progress.js
// @require scripts/keep_box.js
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

  var OVERFLOW_LENGTH = 8; // maximum number of avatars (including libs) to show at once
  var LIB_OVERFLOW_LENGTH = 2; // maximum number of libraries to show

  var partials = {
    'message_avatar_user': 'message_avatar_user',
    'message_avatar_email': 'message_avatar_email',
    'message_avatar_library': 'message_avatar_library',
    'message_participant_user': 'message_participant_user',
    'message_participant_library': 'message_participant_library',
    'message_participant_email': 'message_participant_email',
    'keep_box_lib': 'keep_box_lib'
  };

  var portHandlers = {
    recipients: function (recipients) {
      var keep = k.messageParticipants.getKeep();
      var users = keep.recipients.users = recipients.users;
      var emails = keep.recipients.emails = recipients.emails;
      var libraries = keep.recipients.libraries = recipients.libraries;
      k.messageParticipants.addParticipant.apply(k.messageParticipants, users.concat(emails).concat(libraries));
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
        .on('click', '.kifi-message-participants-avatars-add,.kifi-message-participant-1-add', function (e) {
          api.port.emit('track_pane_click', {
            type: 'discussion',
            action: 'add_participants',
            subaction: e.target.classList.contains('kifi-message-participant-1-add') ? 'bar' : 'inline',
            countAtClick: this.getParticipants().length
          });
          this.toggleAddDialog();
        }.bind(this))
        .on('click', '.kifi-message-participant-dialog-create', function () {
          var progressBar = this.get$('.kifi-message-participant-dialog-create + .kifi-progress-parent');
          var openPromise = k.keepBox.createLibrary(this.getKeep(), 'recipient', 'mouse');
          k.progress.emptyAndShow(progressBar, openPromise);
        }.bind(this))
        .on('click', '.kifi-message-participant-list-hide', this.toggleParticipants.bind(this))
        .on('mousedown', '.kifi-message-participants-togglebar', this.toggleParticipants.bind(this))
        .on('click', '.kifi-message-participant-dialog-button', submitAddParticipants)
        .on('click', '.kifi-message-participant-remove', function (e) {
          var parent = e.target.parentElement;
          var id = parent && parent.dataset.id;
          this.removeParticipant(id);
        }.bind(this))
        .on('mouseover', this.showTogglebar.bind(this))
        .on('mouseout', this.hideTogglebar.bind(this))
        .on('keydown', '.kifi-message-participant-dialog', function (e) {
          if (e.which === 13 && e.originalEvent.isTrusted !== false) {
            submitAddParticipants();
          }
        });

        this.getParent$()
        .on('click', '.kifi-message-participants-avatars-more', this.toggleParticipants.bind(this));

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
      var keep = this.getKeep();
      var permissions = keep && keep.viewer && keep.viewer.permissions || [];
      var canAddLibrary = permissions.indexOf('add_libraries') > -1;

      if (!$input) {
        $input = this.$input = this.get$('.kifi-message-participant-dialog-input');
        initFriendSearch($input, 'threadPane', this.getParticipants.bind(this), api.noop, {
          placeholder: 'Type a library, name, or an email',
          onAdd: function () {
            this.getAddDialog().addClass('kifi-non-empty');
          }.bind(this),
          onDelete: function () {
            if (!$input.tokenInput('get').length) {
              this.getAddDialog().removeClass('kifi-non-empty');
            }
          }.bind(this)
        }, { user: true, email: true, library: canAddLibrary });

        $input.parent().find('.kifi-scroll-inner').on('scroll', function () {
          if (this.clientHeight > 0 && this.scrollTop + this.clientHeight > this.scrollHeight - 3) {
            $input.tokenInput('findMoreResults');
          }
        });
      }

      setTimeout(function () {
        this.get$TokenInput().focus();
      }.bind(this));
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
      // case 'button':
      // case 'option':
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
      var avatars = this.renderAvatars();
      var keep = this.getKeep();
      var elidedCount = Math.max(0, participants.length - avatars.length);
      var permissions = (keep && keep.viewer && keep.viewer.permissions);
      var canAddParticipants = (~permissions.indexOf('add_participants'));
      var canRemoveLibraries = (~permissions.indexOf('remove_libraries'));
      var canRemoveParticipants = (~permissions.indexOf('remove_participants'));

      return {
        isOverflowed: this.isOverflowed(),
        elidedCount: elidedCount,
        avatars: avatars,
        participants: this.renderParticipants(),
        canAddParticipants: canAddParticipants,
        canRemoveLibraries: canRemoveLibraries,
        canRemoveParticipants: canRemoveParticipants
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
     * Renders and returns html for a list of avatars.
     *
     * @return {string} html for a list of avatars
     */
    renderAvatars: function () {
      var keep = this.getKeep();
      var libCount = (keep && keep.recipients && keep.recipients.libraries && keep.recipients.libraries.length) || 0;
      var libsToShow = Math.min(LIB_OVERFLOW_LENGTH, libCount);
      var usersToShow = OVERFLOW_LENGTH - libsToShow;
      var untruncatedAvatars = this.getParticipants().map(this.renderAvatar).sort(orderLibrariesFirst);

      // Since the participants are sorted, we can slice through to limit both libraries and users separately
      var avatars = untruncatedAvatars.slice(0, libsToShow).concat(untruncatedAvatars.slice(libCount, libCount + usersToShow));
      return avatars;
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
      var $wrapper = this.get$('.kifi-message-participant-list-root');
      var list = $wrapper.children()[0];
      var $this = this.get$();

      this.parent.shadePane();
      this.hideTogglebar();
      $this.addClass('kifi-expanded').on('transitionend', updateHeight);
      function updateHeight() {
        $this.off('transitionend', updateHeight);
        $wrapper.height(list.offsetHeight);
      }
      setTimeout(function () {
        $this.off('transitionend', updateHeight);
      }, 1000);

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

    showTogglebar: function () {
      var $this = this.get$();
      if (!this.isExpanded() && !this.isDialogOpened()) {
        $this.addClass('kifi-message-participants-show-togglebar');
      }
    },

    hideTogglebar: function () {
      this.get$().removeClass('kifi-message-participants-show-togglebar');
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
        api.port.emit('track_pane_click', {
          type: 'discussion',
          action: 'show_full_participants'
        });
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
      var libraries = participants.filter(function (p) { return p.id && p.id[0] === 'l' && p.id.indexOf('@') === -1; });
      var emails = participants.filter(function (p) { return p.email; });

      api.port.emit('track_pane_click', {
        type: 'discussion',
        action: 'added_participants',
        users: users.length,
        libraries: libraries.length,
        emails: emails.length,
        totalParticipantsSelected: participants.length
      });

      if (participants.length > 0) {
        this.sendModifyKeep(users.map(getId), emails.map(getId), libraries.map(getId))
        .then(function () {
          var keep = this.getKeep();
          keep.recipients.users = keep.recipients.users.concat(users);
          keep.recipients.emails = keep.recipients.emails.concat(emails);
          keep.recipients.libraries = keep.recipients.libraries.concat(libraries);
          this.parent.refresh();
          this.addParticipant.apply(this, keep.recipients.users.concat(keep.recipients.emails).concat(keep.recipients.libraries));
        }.bind(this))
        .catch(function () {
          var $toShake = this.get$();
          $toShake
          .on('animationend', function onEnd() {
            $toShake.off('animationend', onEnd);
            $toShake.removeClass('kifi-shake');
            this.parent.refresh();
          }.bind(this))
          .addClass('kifi-shake');
        }.bind(this));
      }

      $input.tokenInput('clear');
      this.toggleAddDialog();
    },

    removeParticipant: function (id) {
      var participants = this.getParticipants().filter(function (o) { return o.id === id; });
      var users = participants.filter(function (p) { return !p.email && p.id[0] !== 'l'; }).map(getId);
      var emails = participants.filter(function (p) { return p.email; }).map(getId);
      var libraries = participants.filter(function (p) { return p.id && p.id[0] === 'l' && p.id.indexOf('@') === -1; }).map(getId);
      var toRemove = users.concat(emails).concat(libraries);

      api.port.emit('track_pane_click', {
        type: 'discussion',
        action: 'removed_participants',
        users: users.length,
        libraries: libraries.length,
        emails: emails.length,
        totalParticipantsSelected: participants.length
      });

      if (participants.length > 0) {
        this.sendModifyKeep(null, null, null, users, emails, libraries)
        .then(function () {
          var participants = this.getParticipants();
          toRemove.forEach(function (id) {
            var index = participants.findIndex(idIs(id));
            if (index !== -1) {
              participants.splice(index, 1);
            }
          });
          var keep = this.getKeep();
          keep.recipients.users = keep.recipients.users.filter(function (o) { return users.indexOf(o.id) === -1; });
          keep.recipients.emails = keep.recipients.emails.filter(function (o) { return emails.indexOf(o.id) === -1; });
          keep.recipients.libraries = keep.recipients.libraries.filter(function (o) { return libraries.indexOf(o.id) === -1; });
          this.updateView();
        }.bind(this))
        .catch(function () {
          var $toShake = this.get$();
          $toShake
          .on('animationend', function onEnd() {
            $toShake.off('animationend', onEnd);
            $toShake.removeClass('kifi-shake');
          }.bind(this))
          .addClass('kifi-shake');
        }.bind(this));
      }
    },

    getKeep: function () {
      return this.parent.keep;
    },

    sendModifyKeep: function (users, emails, libraries, removeUsers, removeEmails, removeLibraries) {
      var deferred = Q.defer();
      users = users || [];
      removeUsers = removeUsers || [];
      emails = emails || [];
      removeEmails = removeEmails || [];
      libraries = libraries || [];
      removeLibraries = removeLibraries || [];

      var keep = this.getKeep();
      api.port.emit('update_keepscussion_recipients', {
        keepId: keep.id,
        newUsers: users,
        removeUsers: removeUsers,
        newEmails: emails,
        removeEmails: removeEmails,
        newLibraries: libraries,
        removeLibraries: removeLibraries
      }, function (success) {
        if (success) {
          deferred.resolve();
        } else {
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
        this.hideTogglebar();
        this.collapseParticipants();
      }
    },

    addParticipant: function () {
      var participants = this.getParticipants();
      var originalLength = participants.length;
      var args = Array.prototype.slice.call(arguments);
      var modified = false;

      for (var i = 0, len = arguments.length, participant, participantId; i < len; i++) {
        participant = args[i];
        participantId = participant && participant.id;
        if (participantId && !participants.some(idIs(participantId))) {
          participant.highlight = true;
          participants.unshift(participant);
          modified = true;
        }
      }

      if (originalLength > args.length) {
        var i = 0;
        while (i < participants.length) {
          if (!args.some(idIs(participants[i].id))) {
            participants.splice(i, 1);
            modified = true;
          } else {
            i++;
          }
        }
      }

      if (modified) {
        this.updateView();
        this.highlightParticipantsWithIds(args.filter(function (a) { return a.highlight; }).map(getId));
      }

      args.forEach(function (a) {
        delete a.highlight;
      });
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
      $el.toggleClass('kifi-overflow', view.isOverflowed);
      $el.find('[data-elided]').attr('data-elided', view.elidedCount);
      var renderedAvatars = view.avatars.map(function (a) {
        a = a.email || a.user || a.library;
        var template = (a.isEmail ? 'html/keeper/message_avatar_email' : a.isUser ? 'html/keeper/message_avatar_user' : 'html/keeper/message_avatar_library');
        return $(k.render(template, a));
      });
      var renderedParticipants = view.participants.map(function (p) {
        p = simpleClone(p.email || p.user || p.library);
        var template = (p.isEmail ? 'html/keeper/message_participant_email' : p.isUser ? 'html/keeper/message_participant_user' : 'html/keeper/message_participant_library');
        p.canRemoveLibraries = view.canRemoveLibraries;
        p.canRemoveParticipants = view.canRemoveParticipants;
        return $(k.render(template, p));
      });
      var avatarList = $el.find('.kifi-message-participants-avatars');
      avatarList.find('> :not(:last)').remove(); // don't remove the add icon
      avatarList.prepend(renderedAvatars);
      $el.find('.kifi-message-participant-list-inner').empty().append(renderedParticipants);
      this.updateParticipantsHeight();
      this.$list.data('antiscroll').refresh();
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

  function getId(o) {
    return o.id;
  }

  function simpleClone(o) {
    return JSON.parse(JSON.stringify(o));
  }
})(jQuery, this);
