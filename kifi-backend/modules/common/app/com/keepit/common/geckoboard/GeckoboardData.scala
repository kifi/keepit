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

// http://www.geckoboard.com/developers/custom-widgets/widget-types/number-and-optional-secondary-stat/
case class SparkLine(title: String, primeValue: Int, values: Seq[Int]) extends GeckoboardData {
  def json = Json.obj(
    "item" -> Json.arr(
      Json.obj("text" -> title, "value" -> primeValue),
      values map { v => JsNumber(v) }
    ))
}

// http://www.geckoboard.com/developers/custom-widgets/widget-types/funnel/
case class FunnelItem(label: String, value: Int)

case class Funnel(values: Seq[FunnelItem]) extends GeckoboardData {
  def json = Json.obj(
    "item" -> Json.arr(values map { v => Json.obj("value" -> v.value, "label" -> v.label) })
  )
}
