package com.keepit.common.geckoboard

import WidgetType.NumberAndSecondaryStatType

trait WidgetType
object WidgetType {
  case class NumberAndSecondaryStatType() extends WidgetType
}

case class GeckoboardWidget[WidgetType](key: String) extends AnyVal

object GeckoboardWidget {
  val TotalKeepsPerHour = GeckoboardWidget[NumberAndSecondaryStatType]("37507-12ed349c-eee7-4564-b8b5-754d9ed0aeeb")
}


