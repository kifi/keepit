package com.keepit.common.geckoboard

import play.api.libs.json._

trait GeckoboardData {
  def json: JsValue
}

// http://www.geckoboard.com/developers/custom-widgets/widget-types/number-and-optional-secondary-stat/
case class NumberAndSecondaryStat(first: Int, second: Int) extends GeckoboardData {
  def json = Json.obj("item" -> Json.arr(
      Json.obj("text" -> "", "value" -> first),
      Json.obj("text" -> "", "value" -> second)
    ))
}
