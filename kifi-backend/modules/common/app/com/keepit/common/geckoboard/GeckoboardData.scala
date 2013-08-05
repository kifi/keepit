package com.keepit.common.geckoboard

import play.api.libs.json._
import WidgetType.NumberAndSecondaryStatType

trait GeckoboardData[T <: WidgetType] {
  val widget: GeckoboardWidget[T]
  def json: JsValue
}

// http://www.geckoboard.com/developers/custom-widgets/widget-types/number-and-optional-secondary-stat/
case class NumberAndSecondaryStat[T <: NumberAndSecondaryStatType](widget: GeckoboardWidget[T], first: Int, second: Int) extends GeckoboardData[T] {
  def json = Json.obj("item" -> Json.arr(
      Json.obj("text" -> "", "value" -> first),
      Json.obj("text" -> "", "value" -> second)
    ))
}
