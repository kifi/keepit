package com.keepit.eliza.util

import scala.util.matching.Regex.Match

object MessageFormatter {

  private[this] val lookHereRe = """\[((?:\\\]|[^\]])*)\]\(x-kifi-sel:[^)]*(?:(?<=\\)\)[^)]*)*\)""".r

  /**
   * Formats [[com.keepit.eliza.model.Message.messageText]] (in a markdown-based format) as plain text.
   * Removes "look here" links, preserving only the link text.
   */
  def toText(messageText: String): String = {
    lookHereRe.replaceAllIn(messageText, (m: Match) => m.group(1))
  }

  /**
   * Formats [[com.keepit.eliza.model.Message.messageText]] (in a markdown-based format) as HTML for an email.
   */
  def toHtmlForEmail(messageText: String): String = ???

}
