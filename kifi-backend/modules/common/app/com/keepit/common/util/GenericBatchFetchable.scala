package com.keepit.common.util

import play.api.libs.functional.{ ~, FunctionalCanBuild, Functor }

object GenericBatchFetchable {
  implicit val garbageFunctor = Garbage.functor
  implicit val garbageFCB = Garbage.fcb
  abstract class BatchFetchKind[K, V](val fetcher: BatchFetcher[K, V]) {
    type MyK = K
    type MyV = V
    def get(k: K): Garbage[Option[V]] = new Garbage[Option[V]](
      keys = Map(this -> new BatchFetchKeys(this)(Set(k))),
      compute = _.get(this).flatMap { vs =>
        vs.values.asInstanceOf[Map[K, V]].get(k)
      }
    )
  }
  trait BatchFetcher[K, V] {
    def fetch(keys: Set[K]): Map[K, V]
  }

  class Garbage[T](private val keys: Map[BatchFetchKind[_, _], BatchFetchKeys[_, _]], private val compute: Map[BatchFetchKind[_, _], BatchFetchValues[_, _]] => T) {
    def map[S](f: T => S): Garbage[S] = new Garbage(this.keys, this.compute andThen f)
    def run: T = compute(keys.mapValues(_.fetch))
  }
  private object Garbage {
    val functor = new Functor[Garbage] {
      def fmap[T, S](m: Garbage[T], f: T => S): Garbage[S] = m.map(f)
    }
    val fcb: FunctionalCanBuild[Garbage] = new FunctionalCanBuild[Garbage] {
      def apply[A, B](ma: Garbage[A], mb: Garbage[B]): Garbage[A ~ B] = new Garbage[A ~ B](
        keys = MapHelpers.unionWithKey[BatchFetchKind[_, _], BatchFetchKeys[_, _]] {
          case (kind, (a, b)) =>
            new BatchFetchKeys(kind)(a.keys.asInstanceOf[Set[kind.MyK]] ++ b.keys.asInstanceOf[Set[kind.MyK]])
        }(ma.keys, mb.keys),
        vs => new ~(ma.compute(vs), mb.compute(vs))
      )
    }
  }
  private class BatchFetchKeys[K, V](val kind: BatchFetchKind[K, V])(val keys: Set[K]) {
    def fetch: BatchFetchValues[K, V] = new BatchFetchValues(kind)(kind.fetcher.fetch(keys))
  }

  private class BatchFetchValues[K, V](val kind: BatchFetchKind[K, V])(val values: Map[K, V])
}
