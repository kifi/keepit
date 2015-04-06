package com.keepit.common.store

import com.keepit.rover.sensitivity.{ PornWordLikelihood, PornWordLikelihoodStore }

case class FakePornWordLikelihoodStore() extends PornWordLikelihoodStore {
  override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
  def -=(key: String) = null
  def +=(kv: (String, PornWordLikelihood)) = null
}
