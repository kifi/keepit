package com.keepit.notify.info

object NotificationEnglish {

  val plurals = Map(
    "A robot" -> "Some robots",
    "He" -> "They",
    "someone" -> "some people",
    "some friends" -> "a friend"
  )

  def englishJoin(items: Seq[String]) = items.length match {
    case 0 => throw new IllegalArgumentException("Items must have at least one element")
    case 1 => items.head
    case 2 => items.mkString(" and ")
    case n => items.init.mkString(", ") + ", and " + items.last
  }

}
