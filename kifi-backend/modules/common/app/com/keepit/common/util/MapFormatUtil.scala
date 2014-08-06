package com.keepit.common.util

import play.api.libs.json._

import com.keepit.common.collection._
import com.keepit.common.json._

object MapFormatUtil {

  //  Example of how to convert customized key to json when using a map
  //  implicit def scoreTypeMapFormat(implicit f: Format[Map[String, Float]]): Format[Map[ScoreType.Value, Float]] =
  //    mapKeyFormat(ScoreType.withName _, _.toString)

  def mapKeyFormat[A, B, C](aToC: A => C, cToA: C => A)(implicit f: Format[Map[A, B]]): Format[Map[C, B]] =
    f.convert(_.mapKeys(aToC), _.mapKeys(cToA))

}
