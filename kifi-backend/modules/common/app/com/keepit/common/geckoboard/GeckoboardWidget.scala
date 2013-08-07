package com.keepit.common.geckoboard

abstract class GeckoboardWidget[T <: GeckoboardData](val id: GeckoboardWidgetId) {
  def data(): T
}

case class GeckoboardWidgetId(id: String) extends AnyVal

