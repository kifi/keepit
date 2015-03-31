package com.keepit.cortex.store

import com.keepit.cortex.core.Versionable
import com.keepit.cortex.core.StatModel
import com.keepit.common.store.S3ObjectStore
import com.keepit.cortex.core.ModelVersion
import com.keepit.common.store.ObjectStore
import com.keepit.common.store.InMemoryObjectStore

case class VersionedStoreKey[K, M <: StatModel](key: K, version: ModelVersion[M]) {
  def toKey(): String = "version_" + version.version + "/" + key.toString
}

/**
 * K: key without version
 * V: some model-version dependent value
 */
trait VersionedStore[K, M <: StatModel, V <: Versionable[M]] {
  protected def toVersionedKey(k: K, v: ModelVersion[M]): VersionedStoreKey[K, M] = VersionedStoreKey(k, v)

  def syncGet(key: K, version: ModelVersion[M]): Option[V]

  def +=(key: K, version: ModelVersion[M], value: V): this.type

  def -=(key: K, version: ModelVersion[M]): this.type

  def syncBatchGet(keys: Seq[K], version: ModelVersion[M]): Seq[Option[V]] = {
    keys.par.map { k => syncGet(k, version) }.seq
  }
}

trait VersionedS3Store[K, M <: StatModel, V <: Versionable[M]] extends VersionedStore[K, M, V] with S3ObjectStore[VersionedStoreKey[K, M], V] {

  override def syncGet(key: K, version: ModelVersion[M]): Option[V] = {
    val vkey = toVersionedKey(key, version)
    super.syncGet(vkey)
  }

  override def +=(key: K, version: ModelVersion[M], value: V) = {
    val vkey = toVersionedKey(key, version)
    super.+=((vkey, value))
  }

  override def -=(key: K, version: ModelVersion[M]) = {
    val vkey = toVersionedKey(key, version)
    super.-=(vkey)
  }
}

trait VersionedInMemoryStore[K, M <: StatModel, V <: Versionable[M]] extends VersionedStore[K, M, V] with InMemoryObjectStore[VersionedStoreKey[K, M], V] {
  override def syncGet(key: K, version: ModelVersion[M]): Option[V] = {
    val vkey = toVersionedKey(key, version)
    super.syncGet(vkey)
  }

  override def +=(key: K, version: ModelVersion[M], value: V) = {
    val vkey = toVersionedKey(key, version)
    super.+=((vkey, value))
  }

  override def -=(key: K, version: ModelVersion[M]) = {
    val vkey = toVersionedKey(key, version)
    super.-=(vkey)
  }
}
