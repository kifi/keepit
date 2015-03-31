package com.keepit.common.store

import com.keepit.learning.porndetector.PornWordLikelihoodStore
import com.keepit.learning.porndetector.PornWordLikelihood

case class FakePornWordLikelihoodStore() extends PornWordLikelihoodStore {
  override def syncGet(key: String) = Some(PornWordLikelihood(Map("a" -> 1f)))
  def -=(key: String) = null
  def +=(kv: (String, PornWordLikelihood)) = null
}
