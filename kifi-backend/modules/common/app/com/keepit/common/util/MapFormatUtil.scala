package com.keepit.common.util

import com.keepit.model.ScoreType._
import play.api.libs.json._

object MapFormatUtil {
  implicit val scoreTypeMapFormat = new Format[Map[ScoreType, Float]] {
    def writes(map: Map[ScoreType, Float]): JsValue =
      Json.obj(map.map {
        case (k, v) =>
          (k.toString -> Json.toJsFieldJsValueWrapper(v))
      }.toSeq: _*)

    def reads(jv: JsValue): JsResult[Map[ScoreType, Float]] =
      JsSuccess(jv.as[Map[String, JsValue]].map {
        case (v, k) =>
          k.as[ScoreType] -> v.toFloat
      })
  }

}
