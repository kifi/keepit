package com.keepit.common.util

import play.api.libs.json._

import com.keepit.common.collection._
import com.keepit.common.json._

object MapFormatUtil {

  def mapKeyFormat[A, B, C](aToC: A => C, cToA: C => A)(implicit f: Format[Map[A, B]]): Format[Map[C, B]] =
    f.convert(_.mapKeys(aToC), _.mapKeys(cToA))

}
