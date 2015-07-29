package com.keepit.rover

import com.keepit.rover.sensitivity.{ PornWordLikelihood, PornWordLikelihoodStore }

case class FakePornWordLikelihoodStore() extends PornWordLikelihoodStore {
  override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
  def -=(key: String) = null
  def +=(kv: (String, PornWordLikelihood)) = null
  def copy(sourceId: String, destinationId: String): Boolean = syncGet(sourceId).exists { blob => this += (destinationId, blob); true }
}
