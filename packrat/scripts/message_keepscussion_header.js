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
     * Initializes a Message Participants.
     */
    init: function () {
      this.initialized = true;
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

      return {
        keep: keep,
        librariesPlural: keep && keep.libraries && keep.libraries.length !== 1
      };
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
      .on('click', '.kifi-message-keepscussion-header-lib-add,.kifi-message-keepscussion-header-lib-edit', function () {
        k.keeper.showKeepBox('keepscussion');
        var $dummy = this.querySelector('.kifi-message-keepscussion-header-lib-add-icon-dummy');
        if ($dummy) {
          $dummy.addEventListener('transitionend', function transitionend() {
            $dummy.removeEventListener('transitionend', transitionend);
            $dummy.classList.remove('kifi-message-keepscussion-header-lib-add-icon-clicked');
          });
          $dummy.classList.add('kifi-message-keepscussion-header-lib-add-icon-clicked');
        }
      })
      .find('.kifi-keep-box-lib')
      .attr('href', 'https://www.kifi.com' + (library && library.path))
      .attr('target','_blank');

      return $rendered;
    },

    /**
     * It removes all event listeners and caches to elements.
     */
    destroy: function () {
      this.initialized = false;
      this.parent = null;
    }
  };
})(jQuery, this);
