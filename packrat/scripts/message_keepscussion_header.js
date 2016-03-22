// @require styles/keeper/message_keepscussion_header.css
// @require styles/keeper/keep_box.css
// @require scripts/html/keeper/message_keepscussion_header.js
// @require scripts/html/keeper/keep_box_lib.js
// @require scripts/lib/jquery.js
// @require scripts/render.js
// @require scripts/keep_box.js

/**
 * --------------------------
 *  Message Keepscussion Header
 * --------------------------
 *
 * Message Keepscussion Header is an UI component that manages
 * the elements of messages related to keeps, including libraries
 * that the keep belongs to, and its privacy.
 */

k.messageKeepscussionHeader = k.messageKeepscussionHeader || (function ($, win) {
  'use strict';

  return {
    /**
     * Parent UI Component. e.g. Message Header
     *
     * @property {Object}
     */
    parent: null,

    initialized: false,

    /**
     * Initializes a Message Keepscussion Header.
     */
    init: function () {
      this.initialized = true;
      this.initEvents();
    },

    initEvents: (function () {
      function onClick(e) {
        var $target = $(e.target);
        if (this.isDialogOpened() && this.get$().find($target).length === 0) {
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

        this.parent.$el
        .on('click', '.kifi-message-keepscussion-dialog-button', submitAddParticipants)
        .on('keydown', '.kifi-message-keepscussion-dialog', function (e) {
          if (e.which === 13 && e.originalEvent.isTrusted !== false) {
            submitAddParticipants();
          }
        })
        .on('click', '.kifi-message-keepscussion-header-lib-edit', function () {
          this.toggleAddDialog();
          api.port.emit('track_pane_click', {
            type: 'chooseLibraryForDiscussion',
            action: 'editDiscussionLibrary'
          });
        }.bind(this))
        .on('click', '.kifi-message-keepscussion-header-lib-add', function () {
          this.toggleAddDialog();
          var $dummy = this.get$('.kifi-message-keepscussion-header-lib-add-icon-dummy')[0];
          if ($dummy) {
            $dummy.addEventListener('transitionend', function transitionend() {
              $dummy.removeEventListener('transitionend', transitionend);
              $dummy.classList.remove('kifi-message-keepscussion-header-lib-add-icon-clicked');
            });
            $dummy.classList.add('kifi-message-keepscussion-header-lib-add-icon-clicked');
          }
          api.port.emit('track_pane_click', {
            type: 'chooseLibraryForDiscussion',
            action: 'addDiscussionLibrary'
          });
        }.bind(this));

        win.setTimeout(addDocListeners.bind(this));
      };
    })(),

    /**
     * Renders a UI component given the component name.
     *
     * @param {string} name - UI component name
     *
     * @return {string} A rendered html
     */
    render: function (name) {
      switch (name) {
        case 'content':
          return this.renderContent();
      }
    },

    /**
     * Returns the current state object excluding UI states.
     *
     * @return {Object} A state object
     */
    getView: function () {
      var keep = this.parent.keep;
      var keptBy = keep && keep.keptBy;
      return {
        keep: keep,
        canEdit: (keptBy && keptBy.id === k.me.id),
        librariesPlural: keep && keep.libraries && keep.libraries.length !== 1
      };
    },

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
      if (!$input) {
        $input = this.$input = this.get$('.kifi-message-keepscussion-dialog-input');
        initFriendSearch($input, 'threadPane', [ this.parent.keep.id ], api.noop, {
          placeholder: 'Type a name...',
          onAdd: function () {
            this.getAddDialog().addClass('kifi-non-empty');
            this.addParticipantTokens(); // This will close the dialog to prevent choosing multiple
          }.bind(this),
          onDelete: function () {
            if (!$input.tokenInput('get').length) {
              this.getAddDialog().removeClass('kifi-non-empty');
            }
          }.bind(this)
        }, { libraries: true });
      }

      setTimeout(function () {
        this.get$TokenInput().focus();
      }.bind(this));
    },

    /**
     * Renders and returns html for participants container.
     *
     * @return {string} participants html
     */
    renderContent: function () {
      var keep = this.parent.keep;
      var library = keep && keep.libraries && keep.libraries[0];
      var $rendered = $(k.render('html/keeper/message_keepscussion_header', this.getView(), {  'keep_box_lib': 'keep_box_lib' }));

      $rendered
      .find('.kifi-keep-box-lib')
      .attr('href', 'https://www.kifi.com' + (library && library.path))
      .attr('target','_blank');

      return $rendered;
    },

    refresh: function () {
      this.parent.find('.kifi-message-keepscussion-header').replaceWith(this.renderContent());
    },

    /**
     * Finds and returns a jQuery wrapper object for the given selector.
     *
     * @param {string} [selector] a optional selector
     *
     * @return {jQuery} A jQuery wrapper object
     */
    get$: function (selector) {
      return this.parent.find(selector || '.kifi-message-keepscussion-header');
    },

    isDialogOpened: function () {
      return this.get$().hasClass('kifi-dialog-opened');
    },

    getAddDialog: function () {
      return this.get$('.kifi-message-keepscussion-dialog');
    },

    addParticipantTokens: function () {
      var $input = this.$input;
      var tokens = $input.tokenInput('get');
      if (tokens.length > 0) {
        this.sendModifyKeep(tokens);
        this.$input = null;
      }
      $input.tokenInput('clear');
      this.toggleAddDialog();
    },

    sendModifyKeep: function (libraries) {
      var keep = this.parent.keep;
      return api.port.emit('update_discussion_keep_library', {
        discussionKeep: keep,
        newLibrary: libraries[0]
      }, function success(d) {
        var keep = d.response;
        if (d.success) {
          this.parent.keep = keep;
          this.parent.refresh();
        } else {
          // TODO(carlos): Add a progress bar to seem snappier
          var $toShake = this.get$();
          $toShake
          .on('animationend', function onEnd() {
            $toShake.off('animationend', onEnd);
            $toShake.removeClass('kifi-shake');
            this.parent.refresh();
          })
          .addClass('kifi-shake');
        }
      }.bind(this));
    },

    showAddDialog: function () {
      this.get$().toggleClass('kifi-dialog-opened', true);
      this.initAndAsyncFocusInput();
    },

    hideAddDialog: function () {
      this.get$().toggleClass('kifi-dialog-opened', false);
    },

    toggleAddDialog: function (e) {
      if (e && e.originalEvent && e.originalEvent.addDialogClosed) {
        return;
      }
      if (this.isDialogOpened()) {
        this.hideAddDialog();
      }
      else {
        this.showAddDialog();
      }
    },

    handleEsc: function () {
      if (this.isDialogOpened()) {
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

      // .remove() not called on jQuery elements because it would disrupt a farewell transition
      // an ancestor should call it later (e.g. by being removed itself)

      this.$input = this.$list = this.$el = null;
    }
  };
})(jQuery, this);
