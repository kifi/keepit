package com.keepit.common.util

import play.api.libs.functional.{ ~, FunctionalCanBuild, Functor }

object GenericBatchFetchable {
  implicit val garbageFunctor = Garbage.functor
  implicit val garbageFCB = Garbage.fcb
  trait BatchFetchKind {
    type K
    type V
    def fetcher: BatchFetcher[K, V]

    def get(k: K): Garbage[Option[V]] = new Garbage[Option[V]](
      keys = Map(this -> BatchFetchKeys(this)(Set(k))),
      compute = _.get(this).flatMap { vs =>
        vs.values.asInstanceOf[Map[K, V]].get(k)
      }
    )
  }
  trait BatchFetcher[K, V] {
    def fetch(keys: Set[K]): Map[K, V]
  }

  class Garbage[T](private val keys: Map[BatchFetchKind, BatchFetchKeys], private val compute: Map[BatchFetchKind, BatchFetchValues] => T) {
    def map[S](f: T => S): Garbage[S] = new Garbage(this.keys, this.compute andThen f)
    def run: T = compute(keys.mapValues(_.fetch))
  }
  private object Garbage {
    val functor = new Functor[Garbage] {
      def fmap[T, S](m: Garbage[T], f: T => S): Garbage[S] = m.map(f)
    }
    val fcb: FunctionalCanBuild[Garbage] = new FunctionalCanBuild[Garbage] {
      def apply[A, B](ma: Garbage[A], mb: Garbage[B]): Garbage[A ~ B] = new Garbage[A ~ B](
        keys = MapHelpers.unionWithKey[BatchFetchKind, BatchFetchKeys] {
          case (kind, (a, b)) =>
            BatchFetchKeys(kind)(a.keys.asInstanceOf[Set[kind.K]] ++ b.keys.asInstanceOf[Set[kind.K]])
        }(ma.keys, mb.keys),
        vs => new ~(ma.compute(vs), mb.compute(vs))
      )
    }
  }
  private abstract class BatchFetchKeys(val kind: BatchFetchKind) {
    val keys: Set[kind.K]
    def fetch: BatchFetchValues = BatchFetchValues(kind)(kind.fetcher.fetch(keys))
  }
  private object BatchFetchKeys {
    def apply(kind: BatchFetchKind)(ks: Set[kind.K]): BatchFetchKeys = new BatchFetchKeys(kind) {
      val keys = ks.asInstanceOf[Set[kind.K]]
    }
  }

  private abstract class BatchFetchValues(val kind: BatchFetchKind) {
    val values: Map[kind.K, kind.V]
  }
  private object BatchFetchValues {
    def apply(kind: BatchFetchKind)(vs: Map[kind.K, kind.V]): BatchFetchValues = new BatchFetchValues(kind) {
      val values = vs.asInstanceOf[Map[kind.K, kind.V]]
    }
  }
}
