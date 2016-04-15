package com.keepit.common.util

import com.keepit.common.db.Id
import com.keepit.common.util.BatchFetchable.{ Values, Keys }
import com.keepit.model._
import com.keepit.social.BasicUser
import play.api.libs.functional.{ Functor, ~, FunctionalCanBuild }

final case class BatchFetchable[T](keys: Keys, f: Values => T) {
  def map[S](g: T => S) = this.copy(f = f andThen g)
}

object BatchFetchable {
  final case class Keys(users: Set[Id[User]], libraries: Set[Id[Library]], orgs: Set[Id[Organization]]) {
    def ++(that: Keys) = Keys(this.users ++ that.users, this.libraries ++ that.libraries, this.orgs ++ that.orgs)
  }
  object Keys {
    val empty = Keys(Set.empty, Set.empty, Set.empty)
    def unions(ks: Traversable[Keys]): Keys = ks.fold(empty)(_ ++ _)
  }

  final case class Values(users: Map[Id[User], BasicUser], libs: Map[Id[Library], BasicLibrary], orgs: Map[Id[Organization], BasicOrganization])
  object Values {
    val empty = Values(Map.empty, Map.empty, Map.empty)
  }

  val empty = BatchFetchable[Unit](Keys.empty, _ => ())
  def user(id: Id[User]): BatchFetchable[Option[BasicUser]] = BatchFetchable(Keys(Set(id), Set.empty, Set.empty), _.users.get(id))
  def library(id: Id[Library]): BatchFetchable[Option[BasicLibrary]] = BatchFetchable(Keys(Set.empty, Set(id), Set.empty), _.libs.get(id))
  def org(id: Id[Organization]): BatchFetchable[Option[BasicOrganization]] = BatchFetchable(Keys(Set.empty, Set.empty, Set(id)), _.orgs.get(id))
  def seq[T](xs: Seq[BatchFetchable[T]]): BatchFetchable[Seq[T]] =
    BatchFetchable(Keys.unions(xs.map(_.keys)), vs => xs.map(_.f(vs)))

  implicit val bfFCB: FunctionalCanBuild[BatchFetchable] = new FunctionalCanBuild[BatchFetchable] {
    def apply[A, B](ma: BatchFetchable[A], mb: BatchFetchable[B]): BatchFetchable[~[A, B]] = {
      BatchFetchable[~[A, B]](ma.keys ++ mb.keys, vs => new ~(ma.f(vs), mb.f(vs)))
    }
  }
  implicit val bfFunctor: Functor[BatchFetchable] = new Functor[BatchFetchable] {
    def fmap[A, B](ma: BatchFetchable[A], f: A => B): BatchFetchable[B] = ma.map(f)
  }
}
