package com.keepit.slack.models

import com.keepit.common.reflection.Enumerator

sealed abstract class SlackMessageEditability(val value: String)
object SlackMessageEditability extends Enumerator[SlackMessageEditability] {
  case object EDITABLE extends SlackMessageEditability("editable")
  case object UNEDITABLE extends SlackMessageEditability("uneditable")

  val all = _all
  def fromStr(str: String): Option[SlackMessageEditability] = all.find(_.value == str)
}

