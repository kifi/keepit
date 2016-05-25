package com.keepit.common.util

import play.api.libs.json._
import play.api.libs.functional.syntax._

final case class BatchComputable[I, O](input: I, values: BatchFetchable.Values) {
  def run(implicit tbf: I => BatchFetchable[O]) = tbf(input).f(values)
}
object BatchComputable {
  implicit def format[I, O](implicit inputFormat: Format[I]): Format[BatchComputable[I, O]] = (
    (__ \ 'input).format[I] and
    (__ \ 'values).format[BatchFetchable.Values]
  )(BatchComputable.apply, unlift(BatchComputable.unapply))
}
